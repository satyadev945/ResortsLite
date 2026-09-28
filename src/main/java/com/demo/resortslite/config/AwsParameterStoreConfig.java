package com.demo.resortslite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * AWS Systems Manager Parameter Store Configuration.
 * 
 * This configuration class retrieves environment-specific URLs and configuration
 * from AWS Systems Manager Parameter Store, enabling environment-agnostic deployments.
 * 
 * FIXED cr-java-0071: Replaced hard-coded environment URLs with Parameter Store retrieval.
 * 
 * Parameter Store hierarchy:
 * - /resortslite/inventory/service/url
 * - /resortslite/reports/download/url
 * - /resortslite/payment/endpoint
 * - /resortslite/notification/endpoint
 */
@Configuration
public class AwsParameterStoreConfig {

    private static final Logger logger = Logger.getLogger(AwsParameterStoreConfig.class.getName());
    
    private SsmClient ssmClient;
    private Map<String, String> parameterCache = new HashMap<>();
    
    private static final String INVENTORY_SERVICE_URL_PARAM = "/resortslite/inventory/service/url";
    private static final String REPORTS_DOWNLOAD_URL_PARAM = "/resortslite/reports/download/url";
    private static final String PAYMENT_ENDPOINT_PARAM = "/resortslite/payment/endpoint";
    private static final String NOTIFICATION_ENDPOINT_PARAM = "/resortslite/notification/endpoint";
    
    // Default values for local development (when Parameter Store is not available)
    private static final String DEFAULT_INVENTORY_URL = "https://inventory-service.internal:8081/rooms/available";
    private static final String DEFAULT_REPORTS_URL = "https://reports.resorts-internal.com:8080/download";
    private static final String DEFAULT_PAYMENT_ENDPOINT = "https://payment-svc.internal:9090/charge";
    private static final String DEFAULT_NOTIFICATION_ENDPOINT = "https://notify.internal:7070/send";

    @PostConstruct
    public void init() {
        String awsRegion = System.getenv("AWS_REGION");
        if (awsRegion == null || awsRegion.isEmpty()) {
            awsRegion = "us-east-1";
        }
        
        this.ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        
        // Pre-load commonly used parameters
        loadParameter(INVENTORY_SERVICE_URL_PARAM, DEFAULT_INVENTORY_URL);
        loadParameter(REPORTS_DOWNLOAD_URL_PARAM, DEFAULT_REPORTS_URL);
        loadParameter(PAYMENT_ENDPOINT_PARAM, DEFAULT_PAYMENT_ENDPOINT);
        loadParameter(NOTIFICATION_ENDPOINT_PARAM, DEFAULT_NOTIFICATION_ENDPOINT);
    }

    /**
     * Load parameter from AWS Systems Manager Parameter Store.
     * Falls back to default value if parameter is not found or SSM is unavailable.
     * 
     * @param parameterName Parameter Store parameter name
     * @param defaultValue Default value to use if parameter is not found
     */
    private void loadParameter(String parameterName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(false)
                    .build();
            
            GetParameterResponse response = ssmClient.getParameter(request);
            String value = response.parameter().value();
            parameterCache.put(parameterName, value);
            logger.info("Loaded parameter from AWS Parameter Store: " + parameterName);
        } catch (SsmException e) {
            logger.warning("Failed to load parameter " + parameterName + " from Parameter Store: " 
                    + e.getMessage() + ". Using default value: " + defaultValue);
            parameterCache.put(parameterName, defaultValue);
        } catch (Exception e) {
            logger.warning("Unexpected error loading parameter " + parameterName + ": " 
                    + e.getMessage() + ". Using default value: " + defaultValue);
            parameterCache.put(parameterName, defaultValue);
        }
    }

    /**
     * Get inventory service URL from Parameter Store.
     * FIXED cr-java-0071: Externalized hard-coded inventory service URL.
     * 
     * @return Inventory service URL
     */
    public String getInventoryServiceUrl() {
        return parameterCache.getOrDefault(INVENTORY_SERVICE_URL_PARAM, DEFAULT_INVENTORY_URL);
    }

    /**
     * Get reports download base URL from Parameter Store.
     * FIXED cr-java-0071: Externalized hard-coded reports download URL.
     * 
     * @return Reports download base URL
     */
    public String getReportsDownloadUrl() {
        return parameterCache.getOrDefault(REPORTS_DOWNLOAD_URL_PARAM, DEFAULT_REPORTS_URL);
    }

    /**
     * Get payment endpoint from Parameter Store.
     * 
     * @return Payment endpoint URL
     */
    public String getPaymentEndpoint() {
        return parameterCache.getOrDefault(PAYMENT_ENDPOINT_PARAM, DEFAULT_PAYMENT_ENDPOINT);
    }

    /**
     * Get notification endpoint from Parameter Store.
     * 
     * @return Notification endpoint URL
     */
    public String getNotificationEndpoint() {
        return parameterCache.getOrDefault(NOTIFICATION_ENDPOINT_PARAM, DEFAULT_NOTIFICATION_ENDPOINT);
    }

    /**
     * Refresh a specific parameter from Parameter Store.
     * Useful for updating configuration without restarting the application.
     * 
     * @param parameterName Parameter name to refresh
     */
    public void refreshParameter(String parameterName) {
        String defaultValue = parameterCache.getOrDefault(parameterName, "");
        loadParameter(parameterName, defaultValue);
    }

    /**
     * Refresh all parameters from Parameter Store.
     */
    public void refreshAllParameters() {
        loadParameter(INVENTORY_SERVICE_URL_PARAM, DEFAULT_INVENTORY_URL);
        loadParameter(REPORTS_DOWNLOAD_URL_PARAM, DEFAULT_REPORTS_URL);
        loadParameter(PAYMENT_ENDPOINT_PARAM, DEFAULT_PAYMENT_ENDPOINT);
        loadParameter(NOTIFICATION_ENDPOINT_PARAM, DEFAULT_NOTIFICATION_ENDPOINT);
    }

    @Bean
    public SsmClient ssmClient() {
        return this.ssmClient;
    }
}
