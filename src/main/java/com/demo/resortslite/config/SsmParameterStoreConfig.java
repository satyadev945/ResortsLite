package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

import javax.annotation.PostConstruct;
import java.util.logging.Logger;

/**
 * Spring configuration that provides AWS Systems Manager (SSM) Parameter Store
 * integration for externalising environment-specific URLs.
 *
 * <p>cr-java-0071 FIX: This class resolves hard-coded environment URLs by fetching
 * their values from AWS SSM Parameter Store at application startup. The resolved
 * values are then available as Spring properties, which are injected via {@code @Value}
 * annotations in the consuming beans (e.g., {@link com.demo.resortslite.BookingController}
 * and {@link com.demo.resortslite.ReportService}).
 *
 * <p>SSM parameter names are configured via application.properties / environment variables:
 * <ul>
 *   <li>{@code app.inventory.url.ssm-param} — SSM parameter name for the inventory service URL</li>
 *   <li>{@code app.report.download.base-url.ssm-param} — SSM parameter name for the report download base URL</li>
 * </ul>
 *
 * <p>Credentials are resolved automatically by the
 * {@link software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider}
 * chain (IAM role → environment variables → ~/.aws/credentials), which is the
 * recommended approach for cloud-native deployments on AWS (ECS, EKS, EC2, Lambda).
 */
@Configuration
public class SsmParameterStoreConfig {

    private static final Logger logger = Logger.getLogger(SsmParameterStoreConfig.class.getName());

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * SSM parameter name for the inventory service URL.
     * Defaults to the SSM path "/resortslite/inventory/url" if not overridden.
     */
    @Value("${app.inventory.url.ssm-param:/resortslite/inventory/url}")
    private String inventoryUrlSsmParam;

    /**
     * SSM parameter name for the report download base URL.
     * Defaults to the SSM path "/resortslite/report/download-base-url" if not overridden.
     */
    @Value("${app.report.download.base-url.ssm-param:/resortslite/report/download-base-url}")
    private String reportDownloadBaseUrlSsmParam;

    /**
     * SSM parameter name for the server port.
     * Defaults to the SSM path "/resortslite/server/port" if not overridden.
     * cr-java-0077 FIX: Externalises the hard-coded port 8080 to AWS SSM Parameter Store.
     */
    @Value("${app.server.port.ssm-param:/resortslite/server/port}")
    private String serverPortSsmParam;

    /**
     * Creates and returns a configured {@link SsmClient} instance.
     * Credentials are resolved via the default AWS credential provider chain.
     *
     * @return a fully configured SsmClient
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Resolves the server port from AWS SSM Parameter Store at application startup.
     *
     * <p>cr-java-0077 FIX: The hard-coded port constant {@code SERVER_PORT = 8080} has been
     * replaced with a dynamic value fetched from SSM Parameter Store. This enables ECS/EKS
     * container orchestration to assign ports dynamically and supports cloud service discovery.
     * The resolved value is set as the Spring property {@code app.server.port} so that all
     * consuming beans (e.g., {@link com.demo.resortslite.ReportService}) receive the correct
     * runtime port without any hard-coded values in application source code.
     *
     * <p>Falls back to the {@code SERVER_PORT} environment variable, then to {@code 8080}
     * for local/dev environments where SSM is not reachable.
     */
    @javax.annotation.PostConstruct
    public void resolveServerPortFromSsm() {
        SsmClient client = ssmClient();
        String fallback = System.getenv("SERVER_PORT") != null ? System.getenv("SERVER_PORT") : "8080";
        String resolvedPort = resolveFromSsm(client, serverPortSsmParam, fallback);
        // Publish the resolved port as a system property so Spring picks it up via ${app.server.port}
        System.setProperty("app.server.port", resolvedPort);
        logger.info("cr-java-0077: Server port resolved from SSM Parameter Store: " + resolvedPort
                + " (SSM param: " + serverPortSsmParam + ")");
    }

    /**
     * Fetches a parameter value from AWS SSM Parameter Store.
     *
     * <p>If the SSM call fails (e.g., parameter not found, insufficient permissions,
     * or running outside AWS), the method logs a warning and returns the provided
     * {@code fallbackValue} so the application can still start in local/dev environments.
     *
     * @param ssmClient     the SSM client to use
     * @param parameterName the SSM parameter name (path)
     * @param fallbackValue the value to use if SSM lookup fails
     * @return the resolved parameter value
     */
    public static String resolveFromSsm(SsmClient ssmClient, String parameterName, String fallbackValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            String value = response.parameter().value();
            logger.info("cr-java-0071: Resolved SSM parameter '" + parameterName + "' successfully.");
            return value;
        } catch (SsmException e) {
            logger.warning("cr-java-0071: Could not resolve SSM parameter '" + parameterName
                    + "' — using fallback value. Reason: " + e.getMessage());
            return fallbackValue;
        } catch (Exception e) {
            logger.warning("cr-java-0071: Unexpected error resolving SSM parameter '" + parameterName
                    + "' — using fallback value. Reason: " + e.getMessage());
            return fallbackValue;
        }
    }
}
