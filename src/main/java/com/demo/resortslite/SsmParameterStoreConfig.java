package com.demo.resortslite;

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
 * <p>cr-java-0071 FIX (Hard-coded Environment URLs):
 * Provides an {@link SsmClient} bean and a helper method used by
 * {@link BookingController} and {@link ReportService} to retrieve
 * environment-specific URLs from AWS SSM Parameter Store at runtime,
 * replacing all hard-coded endpoint strings.
 *
 * <p>Required environment variables / application properties:
 * <ul>
 *   <li>{@code cloud.aws.region} – AWS region (default: us-east-1)</li>
 *   <li>{@code ssm.inventory.url.param} – SSM parameter name for the inventory service URL</li>
 *   <li>{@code ssm.report.download.url.param} – SSM parameter name for the report download base URL</li>
 * </ul>
 *
 * <p>Credentials are resolved automatically by the AWS Default Credential Provider Chain
 * (IAM role, environment variables, ~/.aws/credentials, etc.) — no hard-coded secrets.
 */
@Configuration
public class SsmParameterStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(SsmParameterStoreConfig.class);

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Provides a {@link SsmClient} bean scoped to the configured AWS region.
     *
     * @return configured {@link SsmClient}
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Fetches a plain-text or SecureString parameter value from AWS SSM Parameter Store.
     *
     * <p>cr-java-0071 FIX: Called by {@link BookingController} and {@link ReportService}
     * to resolve environment-specific URLs that were previously hard-coded in source code.
     *
     * @param client        the {@link SsmClient} bean
     * @param parameterName the SSM parameter name (e.g. {@code /resortslite/prod/inventory-url})
     * @param defaultValue  fallback value used when the parameter cannot be retrieved
     *                      (e.g. during local development without AWS access)
     * @return the parameter value from SSM, or {@code defaultValue} on error
     */
    public static String getParameter(SsmClient client, String parameterName, String defaultValue) {
        try {
            log.info("Fetching SSM parameter: {}", parameterName);
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = client.getParameter(request);
            String value = response.parameter().value();
            log.info("SSM parameter '{}' resolved successfully.", parameterName);
            return value;
        } catch (Exception ex) {
            log.warn("Could not retrieve SSM parameter '{}': {}. Using default value.",
                    parameterName, ex.getMessage());
            return defaultValue;
        }
    }
}
