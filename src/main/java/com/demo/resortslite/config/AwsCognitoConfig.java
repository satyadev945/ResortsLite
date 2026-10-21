package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

import java.util.logging.Logger;

/**
 * AWS Cognito Configuration
 * 
 * This configuration class integrates Amazon Cognito for user authentication and identity management,
 * replacing file-based authentication mechanisms (cr-java-0090).
 * 
 * Migration from file-based authentication:
 * - Removed: Hardcoded credentials and file-based user storage
 * - Added: AWS Cognito integration for centralized user identity management
 * - Benefits: 
 *   - Centralized user authentication and authorization
 *   - Built-in user lifecycle management (registration, password reset, MFA)
 *   - Encrypted credential storage with automatic rotation support
 *   - Audit logging and compliance features
 *   - Horizontal scalability for distributed cloud environments
 * 
 * AWS Cognito User Pool Configuration:
 * - User Pool ID: Stored in AWS Parameter Store or environment variable
 * - Client ID: Application client ID for authentication
 * - Region: AWS region where Cognito User Pool is deployed
 * 
 * Environment Variables Required:
 * - AWS_COGNITO_USER_POOL_ID: Cognito User Pool identifier
 * - AWS_COGNITO_CLIENT_ID: Application client ID
 * - AWS_REGION: AWS region (default: us-east-1)
 * 
 * IAM Permissions Required:
 * - cognito-idp:AdminInitiateAuth
 * - cognito-idp:AdminGetUser
 * - cognito-idp:AdminCreateUser
 * - cognito-idp:AdminSetUserPassword
 * - cognito-idp:ListUsers
 * 
 * Integration with AWS Secrets Manager:
 * - Database credentials are stored in AWS Secrets Manager
 * - User authentication is handled by AWS Cognito
 * - Application secrets (API keys, tokens) are stored in Secrets Manager
 */
@Configuration
@Profile("!test")
public class AwsCognitoConfig {

    private static final Logger logger = Logger.getLogger(AwsCognitoConfig.class.getName());

    @Value("${aws.cognito.enabled:false}")
    private boolean cognitoEnabled;

    @Value("${aws.cognito.user-pool-id:}")
    private String userPoolId;

    @Value("${aws.cognito.client-id:}")
    private String clientId;

    @Value("${aws.cognito.region:us-east-1}")
    private String region;

    /**
     * Creates AWS Cognito Identity Provider client for user authentication operations.
     * This client is used to authenticate users, manage user lifecycle, and validate tokens.
     * 
     * @return CognitoIdentityProviderClient configured with default credentials and region
     */
    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient() {
        if (!cognitoEnabled) {
            logger.info("AWS Cognito integration is disabled. Using default authentication.");
        } else {
            logger.info("Initializing AWS Cognito Identity Provider client");
            logger.info("User Pool ID: " + maskSensitiveValue(userPoolId));
            logger.info("Client ID: " + maskSensitiveValue(clientId));
            logger.info("Region: " + region);
        }

        return CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Mask sensitive values for logging (show first 4 and last 4 characters only)
     */
    private String maskSensitiveValue(String value) {
        if (value == null || value.isEmpty()) {
            return "***";
        }
        if (value.length() <= 8) {
            return "***";
        }
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }

    public String getUserPoolId() {
        return userPoolId;
    }

    public String getClientId() {
        return clientId;
    }

    public boolean isCognitoEnabled() {
        return cognitoEnabled;
    }
}
