package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SsmParameterStoreConfig — cr-java-0071 fix.
 *
 * <p>Fetches environment-specific URLs and port configuration from AWS Systems
 * Manager (SSM) Parameter Store on application startup and registers them as
 * Spring properties so that no hard-coded URL or port number appears in the
 * application binary.</p>
 *
 * <p>The following SSM parameters are loaded:</p>
 * <ul>
 *   <li>{@code /resortslite/inventory/url} → Spring property
 *       {@code app.inventory.url} (used by {@link BookingController})</li>
 *   <li>{@code /resortslite/report/download-base-url} → Spring property
 *       {@code app.report.download.base-url} (used by {@link ReportService})</li>
 *   <li>{@code /resortslite/server/port} → Spring property
 *       {@code server.port} (cr-java-0077 fix: eliminates hard-coded port 8080
 *       so ECS/EKS can assign ports dynamically)</li>
 * </ul>
 *
 * <p>If an SSM parameter cannot be retrieved (e.g. in a local development
 * environment without SSM access), the property is left unset and the
 * {@code @Value} fallback in each consuming bean takes effect.</p>
 *
 * <p>The SSM parameter paths are themselves configurable via Spring properties
 * so they can be overridden per environment without code changes:</p>
 * <ul>
 *   <li>{@code app.inventory.url.ssm-path} (default:
 *       {@code /resortslite/inventory/url})</li>
 *   <li>{@code app.report.download.base-url.ssm-path} (default:
 *       {@code /resortslite/report/download-base-url})</li>
 * </ul>
 *
 * <p>cr-java-0077 fix: The SSM parameter path for the server port is
 * configurable via {@code server.port.ssm-path} (default:
 * {@code /resortslite/server/port}).</p>
 */
@Configuration
public class SsmParameterStoreConfig {

    private static final Logger LOGGER = Logger.getLogger(SsmParameterStoreConfig.class.getName());

    /** Property source name registered in the Spring Environment. */
    private static final String SSM_PROPERTY_SOURCE_NAME = "awsSsmParameterStore";

    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * SSM parameter path for the inventory service URL.
     * Corresponds to the cr-java-0071 violation in {@link BookingController} line 66.
     */
    @Value("${app.inventory.url.ssm-path:/resortslite/inventory/url}")
    private String inventoryUrlSsmPath;

    /**
     * SSM parameter path for the report download base URL.
     * Corresponds to the cr-java-0071 violation in {@link ReportService} line 66.
     */
    @Value("${app.report.download.base-url.ssm-path:/resortslite/report/download-base-url}")
    private String reportDownloadBaseUrlSsmPath;

    /**
     * SSM parameter path for the server port.
     * cr-java-0077 fix: replaces the hard-coded {@code server.port=8080} in
     * application.properties (original line 28 of ReportService.java source).
     * ECS task definitions, EKS pod specs, and Elastic Beanstalk environments
     * set the {@code SERVER_PORT} environment variable; SSM provides the
     * authoritative value for production deployments.
     */
    @Value("${server.port.ssm-path:/resortslite/server/port}")
    private String serverPortSsmPath;

    private final ConfigurableEnvironment environment;

    public SsmParameterStoreConfig(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    /**
     * Loads SSM parameters into the Spring {@link ConfigurableEnvironment} as a
     * high-priority {@link MapPropertySource}.
     *
     * <p>This runs after all {@code @Value} fields in this class are injected but
     * before any other beans that depend on the resolved properties are
     * initialised.</p>
     */
    @PostConstruct
    public void loadSsmParameters() {
        Map<String, Object> ssmProperties = new HashMap<>();

        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            // cr-java-0071: Load inventory service URL from SSM
            fetchAndStore(ssmClient, inventoryUrlSsmPath, "app.inventory.url", ssmProperties);

            // cr-java-0071: Load report download base URL from SSM
            fetchAndStore(ssmClient, reportDownloadBaseUrlSsmPath, "app.report.download.base-url", ssmProperties);

            // cr-java-0077: Load server port from SSM so no port is hard-coded
            fetchAndStore(ssmClient, serverPortSsmPath, "server.port", ssmProperties);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Could not initialise SSM client — SSM-sourced properties will use fallback values. "
                            + "Cause: " + e.getMessage());
        }

        if (!ssmProperties.isEmpty()) {
            // Register as the highest-priority property source so SSM values
            // override application.properties but can still be overridden by
            // system properties or environment variables if needed.
            environment.getPropertySources()
                    .addFirst(new MapPropertySource(SSM_PROPERTY_SOURCE_NAME, ssmProperties));
            LOGGER.info("Loaded " + ssmProperties.size() + " parameter(s) from AWS SSM Parameter Store.");
        }
    }

    /**
     * Fetches a single SSM parameter and stores it in the provided map.
     *
     * @param ssmClient    the SSM client
     * @param ssmPath      the SSM parameter path (e.g. {@code /resortslite/inventory/url})
     * @param propertyName the Spring property name to register the value under
     * @param target       the map to store the resolved property in
     */
    private void fetchAndStore(SsmClient ssmClient,
                               String ssmPath,
                               String propertyName,
                               Map<String, Object> target) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(ssmPath)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            String value = response.parameter().value();
            target.put(propertyName, value);
            LOGGER.info("Loaded SSM parameter '" + ssmPath + "' → Spring property '" + propertyName + "'");

        } catch (ParameterNotFoundException e) {
            LOGGER.log(Level.WARNING,
                    "SSM parameter '" + ssmPath + "' not found — property '" + propertyName
                            + "' will use its configured fallback value.");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Failed to fetch SSM parameter '" + ssmPath + "': " + e.getMessage()
                            + " — property '" + propertyName + "' will use its configured fallback value.");
        }
    }
}
