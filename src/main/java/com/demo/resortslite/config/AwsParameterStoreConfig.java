package com.demo.resortslite.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * AWS Systems Manager Parameter Store Configuration
 * 
 * This configuration class provides integration with AWS Systems Manager Parameter Store
 * for externalizing environment-specific configuration values.
 * 
 * FIXED cr-java-0071: Hard-coded Environment URLs
 * 
 * This class enables the application to retrieve configuration values from AWS Parameter Store
 * instead of hard-coding them in the application code or properties files.
 * 
 * Parameter Store Configuration:
 * - Store environment-specific URLs in Parameter Store with paths like:
 *   /config/resortslite/app.inventory.service.url
 *   /config/resortslite/app.reports.download.base.url
 *   /config/resortslite/app.payment.endpoint
 *   /config/resortslite/app.notification.endpoint
 * 
 * - Use SecureString type for sensitive values
 * - Use different parameter paths for different environments (dev, staging, prod)
 * 
 * Benefits:
 * - Centralized configuration management
 * - Environment-agnostic deployments
 * - No code changes required for environment-specific values
 * - Secure storage of sensitive configuration
 * - Version control and audit trail for configuration changes
 * - Integration with AWS IAM for access control
 * 
 * Usage:
 * 1. Create parameters in AWS Systems Manager Parameter Store
 * 2. Grant IAM permissions to the application's execution role:
 *    - ssm:GetParameter
 *    - ssm:GetParameters
 *    - ssm:GetParametersByPath
 * 3. Application will automatically retrieve values at startup
 * 
 * Example AWS CLI commands to create parameters:
 * 
 * aws ssm put-parameter \
 *   --name "/config/resortslite/app.inventory.service.url" \
 *   --value "https://inventory-service.prod.internal:8081" \
 *   --type "String" \
 *   --description "Inventory service base URL for production"
 * 
 * aws ssm put-parameter \
 *   --name "/config/resortslite/app.reports.download.base.url" \
 *   --value "https://reports.resorts-prod.com" \
 *   --type "String" \
 *   --description "Reports download base URL for production"
 */
@Configuration
public class AwsParameterStoreConfig {

    private static final Logger logger = LoggerFactory.getLogger(AwsParameterStoreConfig.class);

    @Value("${aws.parameterstore.enabled:true}")
    private boolean parameterStoreEnabled;

    @Value("${aws.parameterstore.region:us-east-1}")
    private String awsRegion;

    private SsmClient ssmClient;

    /**
     * Initialize AWS Systems Manager client.
     * Uses DefaultCredentialsProvider which supports:
     * - Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
     * - System properties
     * - AWS credentials file (~/.aws/credentials)
     * - IAM role for EC2/ECS/EKS (recommended for production)
     */
    @PostConstruct
    public void initSsmClient() {
        if (parameterStoreEnabled) {
            logger.info("Initializing AWS Systems Manager Parameter Store client for region: {}", awsRegion);
            this.ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
            logger.info("AWS Systems Manager Parameter Store client initialized successfully");
        } else {
            logger.info("AWS Systems Manager Parameter Store integration is disabled");
        }
    }

    /**
     * Clean up SSM client resources on application shutdown.
     */
    @PreDestroy
    public void closeSsmClient() {
        if (ssmClient != null) {
            logger.info("Closing AWS Systems Manager Parameter Store client");
            ssmClient.close();
        }
    }

    /**
     * Create SSM client bean for dependency injection.
     * 
     * @return SsmClient instance
     */
    @Bean
    public SsmClient ssmClient() {
        return this.ssmClient;
    }

    /**
     * Retrieve a parameter value from AWS Systems Manager Parameter Store.
     * 
     * @param parameterName The name/path of the parameter (e.g., /config/resortslite/app.inventory.service.url)
     * @param withDecryption Whether to decrypt SecureString parameters
     * @return The parameter value, or null if not found
     */
    public String getParameter(String parameterName, boolean withDecryption) {
        if (!parameterStoreEnabled || ssmClient == null) {
            logger.warn("Parameter Store is disabled or not initialized. Cannot retrieve parameter: {}", parameterName);
            return null;
        }

        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(withDecryption)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            String value = response.parameter().value();
            logger.info("Successfully retrieved parameter: {}", parameterName);
            return value;

        } catch (SsmException e) {
            logger.error("Error retrieving parameter '{}': {} - {}", 
                        parameterName, 
                        e.awsErrorDetails().errorCode(), 
                        e.awsErrorDetails().errorMessage());
            return null;
        }
    }

    /**
     * Retrieve a parameter value from AWS Systems Manager Parameter Store.
     * Defaults to decryption enabled for SecureString parameters.
     * 
     * @param parameterName The name/path of the parameter
     * @return The parameter value, or null if not found
     */
    public String getParameter(String parameterName) {
        return getParameter(parameterName, true);
    }
}
