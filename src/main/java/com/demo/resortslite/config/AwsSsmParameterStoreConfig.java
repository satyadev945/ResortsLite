package com.demo.resortslite.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

/**
 * AWS Systems Manager Parameter Store configuration.
 *
 * <p>cr-java-0071 fix: Replaces all hard-coded environment-specific URLs
 * (inventory service URL in {@code BookingController}, report download URL in
 * {@code ReportService}) with values retrieved from AWS SSM Parameter Store at
 * application startup.  This enables environment-agnostic deployments — the same
 * artifact can be promoted from dev → staging → production simply by updating the
 * SSM parameters in each environment, with no code changes required.</p>
 *
 * <p>cr-java-0077 fix: Replaces the hard-coded {@code SERVER_PORT = 8080} constant in
 * {@code ReportService} with a value retrieved from AWS SSM Parameter Store at startup.
 * This enables dynamic port assignment required by container orchestration platforms
 * (ECS, EKS, Elastic Beanstalk) and cloud service discovery mechanisms.</p>
 *
 * <p>Parameter Store paths used:</p>
 * <ul>
 *   <li>{@code /resortslite/inventory/url}  — inventory service base URL</li>
 *   <li>{@code /resortslite/reports/download-url} — report download base URL</li>
 *   <li>{@code /resortslite/server/port} — application server port</li>
 * </ul>
 *
 * <p>Override the parameter paths and AWS region via environment variables or
 * {@code application.properties}:</p>
 * <pre>
 *   AWS_REGION                          (default: us-east-1)
 *   SSM_INVENTORY_URL_PARAM             (default: /resortslite/inventory/url)
 *   SSM_REPORT_DOWNLOAD_URL_PARAM       (default: /resortslite/reports/download-url)
 *   SSM_SERVER_PORT_PARAM               (default: /resortslite/server/port)
 * </pre>
 *
 * <p>When SSM is unreachable (e.g. local development), the bean falls back to the
 * environment variables {@code INVENTORY_SERVICE_URL}, {@code REPORT_DOWNLOAD_BASE_URL},
 * and {@code SERVER_PORT} so the application can still start without AWS access.</p>
 */
@Configuration
public class AwsSsmParameterStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsSsmParameterStoreConfig.class);

    /** AWS region where the SSM parameters are stored. */
    @Value("${cloud.aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /** SSM parameter path for the inventory service URL. */
    @Value("${aws.ssm.param.inventory-url:${SSM_INVENTORY_URL_PARAM:/resortslite/inventory/url}}")
    private String inventoryUrlParamName;

    /** SSM parameter path for the report download base URL. */
    @Value("${aws.ssm.param.report-download-url:${SSM_REPORT_DOWNLOAD_URL_PARAM:/resortslite/reports/download-url}}")
    private String reportDownloadUrlParamName;

    /**
     * SSM parameter path for the application server port.
     *
     * <p>cr-java-0077 fix: Externalises the hard-coded {@code SERVER_PORT = 8080} constant
     * from {@code ReportService} to AWS SSM Parameter Store.  The parameter path defaults to
     * {@code /resortslite/server/port} and can be overridden via the
     * {@code SSM_SERVER_PORT_PARAM} environment variable or the
     * {@code aws.ssm.param.server-port} application property.</p>
     */
    @Value("${aws.ssm.param.server-port:${SSM_SERVER_PORT_PARAM:/resortslite/server/port}}")
    private String serverPortParamName;

    /**
     * Provides an {@link SsmClient} bean scoped to the configured AWS region.
     * Credentials are resolved by the AWS Default Credential Provider Chain
     * (IAM role, environment variables, {@code ~/.aws/credentials}).
     *
     * @return a fully configured {@link SsmClient} instance
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Fetches the inventory service URL from AWS SSM Parameter Store.
     *
     * <p>cr-java-0071 fix (BookingController.java line 66):
     * The hard-coded literal {@code "http://inventory-service.internal:8081/rooms/available"}
     * is replaced by this bean, which reads the value from SSM at startup.  The URL is
     * injected into {@code BookingController} via {@code @Value("${app.inventory.service.url}")}
     * which is populated from this bean's resolved value.</p>
     *
     * <p>Fallback: if SSM is unreachable, the value of the environment variable
     * {@code INVENTORY_SERVICE_URL} is used instead.</p>
     *
     * @param ssmClient the {@link SsmClient} bean
     * @return the resolved inventory service URL string
     */
    @Bean(name = "inventoryServiceUrl")
    public String inventoryServiceUrl(SsmClient ssmClient) {
        return resolveParameter(
                ssmClient,
                inventoryUrlParamName,
                "INVENTORY_SERVICE_URL",
                "https://inventory-service.internal:8081/rooms/available"
        );
    }

    /**
     * Fetches the report download base URL from AWS SSM Parameter Store.
     *
     * <p>cr-java-0071 fix (ReportService.java line 66):
     * The hard-coded literal {@code "http://reports.resorts-internal.com:8080/download/"}
     * is replaced by this bean, which reads the value from SSM at startup.  The URL is
     * injected into {@code ReportService} via {@code @Value("${app.reports.download.base-url}")}
     * which is populated from this bean's resolved value.</p>
     *
     * <p>Fallback: if SSM is unreachable, the value of the environment variable
     * {@code REPORT_DOWNLOAD_BASE_URL} is used instead.</p>
     *
     * @param ssmClient the {@link SsmClient} bean
     * @return the resolved report download base URL string
     */
    @Bean(name = "reportDownloadBaseUrl")
    public String reportDownloadBaseUrl(SsmClient ssmClient) {
        return resolveParameter(
                ssmClient,
                reportDownloadUrlParamName,
                "REPORT_DOWNLOAD_BASE_URL",
                "https://reports.resorts-internal.com:8080/download"
        );
    }

    /**
     * Fetches the application server port from AWS SSM Parameter Store.
     *
     * <p>cr-java-0077 fix (ReportService.java line 28):
     * The hard-coded constant {@code SERVER_PORT = 8080} is replaced by this bean, which
     * reads the port value from the SSM parameter {@code /resortslite/server/port} at
     * application startup.  This enables dynamic port assignment required by container
     * orchestration platforms (ECS, EKS, Elastic Beanstalk) and cloud service discovery
     * mechanisms, preventing deployment failures and service conflicts caused by fixed ports.</p>
     *
     * <p>Fallback chain:</p>
     * <ol>
     *   <li>SSM parameter {@code /resortslite/server/port} (configurable via
     *       {@code aws.ssm.param.server-port} property or {@code SSM_SERVER_PORT_PARAM} env var)</li>
     *   <li>Environment variable {@code SERVER_PORT}</li>
     *   <li>Built-in default {@code "8080"} (local development only)</li>
     * </ol>
     *
     * @param ssmClient the {@link SsmClient} bean
     * @return the resolved server port as a String
     */
    @Bean(name = "serverPort")
    public String serverPort(SsmClient ssmClient) {
        return resolveParameter(
                ssmClient,
                serverPortParamName,
                "SERVER_PORT",
                "8080"
        );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Retrieves a single SSM parameter value, falling back to an environment variable
     * and then to a hard-coded default when SSM is unavailable.
     *
     * @param ssmClient    the SSM client
     * @param paramName    the SSM parameter path (e.g. {@code /resortslite/inventory/url})
     * @param envVarName   fallback environment variable name
     * @param defaultValue last-resort default (used only in local dev)
     * @return the resolved parameter value
     */
    private String resolveParameter(SsmClient ssmClient,
                                    String paramName,
                                    String envVarName,
                                    String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            String value = response.parameter().value();
            log.info("Resolved SSM parameter '{}' successfully.", paramName);
            return value;

        } catch (Exception ex) {
            log.warn("Could not retrieve SSM parameter '{}' — falling back to env var '{}'. Reason: {}",
                    paramName, envVarName, ex.getMessage());

            String envValue = System.getenv(envVarName);
            if (envValue != null && !envValue.isBlank()) {
                log.info("Using environment variable '{}' as fallback for SSM parameter '{}'.",
                        envVarName, paramName);
                return envValue;
            }

            log.warn("Environment variable '{}' not set — using built-in default value for '{}'.",
                    envVarName, paramName);
            return defaultValue;
        }
    }
}
