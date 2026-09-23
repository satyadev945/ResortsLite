package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

import javax.annotation.PostConstruct;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility component that retrieves environment-specific URLs from
 * AWS Systems Manager (SSM) Parameter Store.
 *
 * <p>Remediation for rule <strong>cr-java-0071</strong>
 * (Hard-coded Environment URLs): all hard-coded environment-specific
 * URLs are replaced with values fetched from SSM Parameter Store at
 * application start-up, enabling environment-agnostic deployments
 * across dev, staging, and production without code changes.</p>
 *
 * <p>Configure the SSM parameter names via the following application
 * properties (or their corresponding environment-variable overrides):
 * <ul>
 *   <li>{@code aws.ssm.inventory.url.param} /
 *       {@code SSM_INVENTORY_URL_PARAM} — path to the inventory-service URL</li>
 *   <li>{@code aws.ssm.report.download.url.param} /
 *       {@code SSM_REPORT_DOWNLOAD_URL_PARAM} — path to the report-download URL</li>
 *   <li>{@code aws.region} / {@code AWS_REGION} — AWS region (default: us-east-1)</li>
 * </ul>
 * </p>
 */
@Component
public class AwsSsmParameterStoreUtil {

    private static final Logger LOGGER =
            Logger.getLogger(AwsSsmParameterStoreUtil.class.getName());

    /** SSM parameter name for the inventory-service URL. */
    @Value("${aws.ssm.inventory.url.param:/resortslite/inventory/service-url}")
    private String inventoryUrlParam;

    /** SSM parameter name for the report-download base URL. */
    @Value("${aws.ssm.report.download.url.param:/resortslite/reports/download-url}")
    private String reportDownloadUrlParam;

    /** AWS region where the SSM parameters are stored. */
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // cr-java-0071: cached URL values fetched from SSM Parameter Store at startup
    private String inventoryServiceUrl;
    private String reportDownloadBaseUrl;

    /**
     * Fetches and caches environment-specific URLs from AWS SSM Parameter Store
     * at application startup.
     *
     * <p>If SSM is unreachable (e.g., running locally without AWS credentials),
     * the method logs a warning and falls back to environment variables so that
     * local development is not broken.</p>
     */
    @PostConstruct
    public void loadParameters() {
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            // cr-java-0071: fetch inventory service URL from SSM Parameter Store
            inventoryServiceUrl = fetchParameter(ssmClient, inventoryUrlParam,
                    "INVENTORY_SERVICE_URL",
                    "https://inventory-service.internal:8081/rooms/available");

            // cr-java-0071: fetch report download base URL from SSM Parameter Store
            reportDownloadBaseUrl = fetchParameter(ssmClient, reportDownloadUrlParam,
                    "REPORT_DOWNLOAD_BASE_URL",
                    "https://reports.resorts-internal.com:8443/download");

            LOGGER.info("Environment URLs successfully loaded from AWS SSM Parameter Store.");

        } catch (Exception ex) {
            LOGGER.log(Level.WARNING,
                    "Could not load environment URLs from AWS SSM Parameter Store. "
                            + "Falling back to environment variables. Reason: " + ex.getMessage());
            inventoryServiceUrl = resolveEnvFallback("INVENTORY_SERVICE_URL",
                    "https://inventory-service.internal:8081/rooms/available");
            reportDownloadBaseUrl = resolveEnvFallback("REPORT_DOWNLOAD_BASE_URL",
                    "https://reports.resorts-internal.com:8443/download");
        }
    }

    /**
     * Fetches a single SecureString or String parameter from SSM Parameter Store.
     * Falls back to an environment variable, then to the supplied default value.
     *
     * @param ssmClient    the SSM client to use
     * @param paramName    the SSM parameter path/name
     * @param envVarName   fallback environment variable name
     * @param defaultValue last-resort default (for local development only)
     * @return the resolved parameter value
     */
    private String fetchParameter(SsmClient ssmClient,
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
            LOGGER.info("Loaded SSM parameter '" + paramName + "'.");
            return value;
        } catch (SsmException ex) {
            LOGGER.log(Level.WARNING,
                    "SSM parameter '" + paramName + "' not found or inaccessible: "
                            + ex.getMessage() + ". Falling back to env var '" + envVarName + "'.");
            return resolveEnvFallback(envVarName, defaultValue);
        }
    }

    /**
     * Resolves a value from an environment variable, falling back to the supplied default.
     *
     * @param envVarName   environment variable name
     * @param defaultValue fallback value when the env var is not set
     * @return the resolved value
     */
    private String resolveEnvFallback(String envVarName, String defaultValue) {
        String envValue = System.getenv(envVarName);
        return (envValue != null && !envValue.isEmpty()) ? envValue : defaultValue;
    }

    /**
     * Returns the inventory-service URL loaded from SSM Parameter Store.
     *
     * <p>cr-java-0071: replaces the hard-coded literal
     * {@code "http://inventory-service.internal:8081/rooms/available"}
     * that was previously embedded in {@code BookingController.checkAvailability()}.</p>
     *
     * @return the inventory-service URL
     */
    public String getInventoryServiceUrl() {
        return inventoryServiceUrl;
    }

    /**
     * Returns the report-download base URL loaded from SSM Parameter Store.
     *
     * <p>cr-java-0071: replaces the hard-coded literal
     * {@code "http://reports.resorts-internal.com:8080/download/"}
     * that was previously embedded in {@code ReportService.buildReportDownloadUrl()}.</p>
     *
     * @return the report-download base URL
     */
    public String getReportDownloadBaseUrl() {
        return reportDownloadBaseUrl;
    }
}
