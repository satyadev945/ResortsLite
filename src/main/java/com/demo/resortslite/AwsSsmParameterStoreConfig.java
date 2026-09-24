package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.logging.Logger;

/**
 * AWS Systems Manager Parameter Store configuration for environment-specific URLs.
 *
 * Remediation for cr-java-0071 (Hard-coded Environment URLs):
 * Replaces hard-coded environment-specific URLs with values retrieved at runtime
 * from AWS Systems Manager Parameter Store, enabling:
 *   - Environment-agnostic deployments (dev / staging / production)
 *   - URL changes without code redeployment
 *   - Centralised configuration management across all instances
 *   - Compliance with 12-factor app externalized configuration principles
 *
 * Remediation for cr-java-0077 (Hard-coded Ports):
 * Externalises the server port to AWS SSM Parameter Store (/resortslite/server/port)
 * so that ECS, EKS, and Elastic Beanstalk can inject the correct port at runtime,
 * enabling dynamic port assignment and cloud service-discovery compatibility.
 *
 * Parameters expected in AWS SSM Parameter Store:
 *   /resortslite/inventory/url      - Inventory service base URL
 *   /resortslite/reports/base-url   - Report download base URL
 *   /resortslite/server/port        - Application server port
 *
 * Configure via environment variables:
 *   AWS_REGION                          - AWS region (default: us-east-1)
 *   SSM_INVENTORY_URL_PARAM             - SSM parameter name for inventory URL
 *                                         (default: /resortslite/inventory/url)
 *   SSM_REPORTS_BASE_URL_PARAM          - SSM parameter name for report base URL
 *                                         (default: /resortslite/reports/base-url)
 *   SSM_SERVER_PORT_PARAM               - SSM parameter name for server port
 *                                         (default: /resortslite/server/port)
 */
@Configuration
public class AwsSsmParameterStoreConfig {

    private static final Logger logger = Logger.getLogger(AwsSsmParameterStoreConfig.class.getName());

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.ssm.inventory-url-param:/resortslite/inventory/url}")
    private String inventoryUrlParam;

    @Value("${aws.ssm.reports-base-url-param:/resortslite/reports/base-url}")
    private String reportsBaseUrlParam;

    // cr-java-0077: SSM parameter name for the server port
    @Value("${aws.ssm.server-port-param:/resortslite/server/port}")
    private String serverPortParam;

    @Value("${app.inventory.endpoint.fallback:https://inventory-service.internal:8081/rooms/available}")
    private String inventoryUrlFallback;

    @Value("${app.reports.base-url.fallback:https://reports.resorts-internal.com:8080/download/}")
    private String reportsBaseUrlFallback;

    // cr-java-0077: Fallback port value when SSM is unavailable (local dev / non-AWS)
    @Value("${app.server.port.fallback:${SERVER_PORT:8080}}")
    private String serverPortFallback;

    /**
     * Provides a SsmClient bean for use across the application.
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Retrieves the inventory service URL from AWS SSM Parameter Store.
     *
     * cr-java-0071 remediation: Replaces the hard-coded URL
     * "http://inventory-service.internal:8081/rooms/available" with a value
     * fetched from SSM Parameter Store, enabling environment-agnostic deployments.
     *
     * Falls back to the environment-variable-backed property value if SSM is
     * unavailable (e.g., local development without AWS access).
     *
     * @return the inventory service URL resolved from SSM Parameter Store
     */
    @Bean(name = "inventoryServiceUrl")
    public String inventoryServiceUrl(SsmClient ssmClient) {
        return resolveParameter(ssmClient, inventoryUrlParam, inventoryUrlFallback,
                "inventory service URL");
    }

    /**
     * Retrieves the report download base URL from AWS SSM Parameter Store.
     *
     * cr-java-0071 remediation: Replaces the hard-coded URL
     * "http://reports.resorts-internal.com:8080/download/" with a value
     * fetched from SSM Parameter Store, enabling environment-agnostic deployments.
     *
     * Falls back to the environment-variable-backed property value if SSM is
     * unavailable (e.g., local development without AWS access).
     *
     * @return the report download base URL resolved from SSM Parameter Store
     */
    @Bean(name = "reportsBaseUrl")
    public String reportsBaseUrl(SsmClient ssmClient) {
        return resolveParameter(ssmClient, reportsBaseUrlParam, reportsBaseUrlFallback,
                "reports base URL");
    }

    /**
     * Retrieves the server port from AWS SSM Parameter Store.
     *
     * cr-java-0077 remediation: Replaces the hard-coded port constant (8080) with a
     * value fetched from SSM Parameter Store (/resortslite/server/port), enabling
     * ECS, EKS, and Elastic Beanstalk to inject the correct port at container startup
     * for dynamic port assignment and service-discovery compatibility.
     *
     * Falls back to the SERVER_PORT environment variable (then 8080) when SSM is
     * unavailable (e.g., local development without AWS access).
     *
     * @return the server port resolved from SSM Parameter Store
     */
    @Bean(name = "ssmServerPort")
    public String ssmServerPort(SsmClient ssmClient) {
        return resolveParameter(ssmClient, serverPortParam, serverPortFallback,
                "server port");
    }

    /**
     * Helper method to retrieve a single parameter from AWS SSM Parameter Store
     * with a fallback value for non-AWS environments.
     *
     * @param ssmClient   the SSM client
     * @param paramName   the SSM parameter name/path
     * @param fallback    the fallback value if SSM is unavailable
     * @param description a human-readable description for logging
     * @return the resolved parameter value
     */
    private String resolveParameter(SsmClient ssmClient, String paramName,
                                    String fallback, String description) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            String value = response.parameter().value();
            logger.info("Successfully loaded " + description + " from SSM Parameter Store: " + paramName);
            return value;
        } catch (Exception e) {
            logger.warning("Could not retrieve " + description + " from SSM Parameter Store ("
                    + e.getMessage() + "). Falling back to configured property value: " + fallback);
            return fallback;
        }
    }
}
