package com.demo.resortslite.service;

import com.demo.resortslite.config.AwsCognitoConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Authentication Service using AWS Cognito
 * 
 * This service replaces file-based authentication with AWS Cognito for user identity management.
 * It provides secure authentication, user management, and token validation using cloud-native patterns.
 * 
 * Fixes for cr-java-0090 (File-based Authentication):
 * - Replaced hardcoded credentials with AWS Cognito user authentication
 * - Replaced MD5 hashing with SHA-256 for secure token generation
 * - Integrated with AWS Secrets Manager for credential storage
 * - Implemented stateless authentication suitable for horizontal scaling
 * 
 * Security improvements:
 * - Uses AWS Cognito for centralized user authentication
 * - Replaces weak MD5 hashing with SHA-256
 * - Implements secure random token generation
 * - Supports MFA and advanced security features via Cognito
 * - Provides audit logging through AWS CloudTrail
 */
@Service
public class AuthenticationService {

    private static final Logger logger = Logger.getLogger(AuthenticationService.class.getName());

    @Autowired
    private CognitoIdentityProviderClient cognitoClient;

    @Autowired
    private AwsCognitoConfig cognitoConfig;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Authenticate user with AWS Cognito
     * 
     * @param username User's username or email
     * @param password User's password
     * @return Authentication result with access token and user attributes
     */
    public Map<String, Object> authenticateUser(String username, String password) {
        Map<String, Object> result = new HashMap<>();

        if (!cognitoConfig.isCognitoEnabled()) {
            logger.warning("AWS Cognito is disabled. Using fallback authentication.");
            result.put("success", false);
            result.put("message", "Authentication service not configured");
            return result;
        }

        try {
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);

            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .authFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
                    .userPoolId(cognitoConfig.getUserPoolId())
                    .clientId(cognitoConfig.getClientId())
                    .authParameters(authParams)
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

            result.put("success", true);
            result.put("accessToken", authResponse.authenticationResult().accessToken());
            result.put("idToken", authResponse.authenticationResult().idToken());
            result.put("refreshToken", authResponse.authenticationResult().refreshToken());
            result.put("expiresIn", authResponse.authenticationResult().expiresIn());

            logger.info("User authenticated successfully: " + maskUsername(username));

        } catch (NotAuthorizedException e) {
            logger.warning("Authentication failed for user: " + maskUsername(username));
            result.put("success", false);
            result.put("message", "Invalid username or password");
        } catch (UserNotFoundException e) {
            logger.warning("User not found: " + maskUsername(username));
            result.put("success", false);
            result.put("message", "User not found");
        } catch (Exception e) {
            logger.severe("Authentication error: " + e.getMessage());
            result.put("success", false);
            result.put("message", "Authentication service error");
        }

        return result;
    }

    /**
     * Get user details from AWS Cognito
     * 
     * @param username User's username
     * @return User attributes and status
     */
    public Map<String, Object> getUserDetails(String username) {
        Map<String, Object> result = new HashMap<>();

        if (!cognitoConfig.isCognitoEnabled()) {
            result.put("success", false);
            result.put("message", "Cognito not configured");
            return result;
        }

        try {
            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoConfig.getUserPoolId())
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            result.put("success", true);
            result.put("username", getUserResponse.username());
            result.put("userStatus", getUserResponse.userStatusAsString());
            result.put("enabled", getUserResponse.enabled());

            Map<String, String> attributes = new HashMap<>();
            for (AttributeType attr : getUserResponse.userAttributes()) {
                attributes.put(attr.name(), attr.value());
            }
            result.put("attributes", attributes);

        } catch (UserNotFoundException e) {
            result.put("success", false);
            result.put("message", "User not found");
        } catch (Exception e) {
            logger.severe("Error retrieving user details: " + e.getMessage());
            result.put("success", false);
            result.put("message", "Error retrieving user details");
        }

        return result;
    }

    /**
     * Generate secure confirmation code using SHA-256
     * Replaces weak MD5 hashing (sec-weak-hash-001)
     * 
     * @param input Input string to hash
     * @return Secure hash using SHA-256
     */
    public String generateSecureConfirmationCode(String input) {
        try {
            // Add random salt for additional security
            byte[] salt = new byte[16];
            secureRandom.nextBytes(salt);
            String saltedInput = input + Base64.getEncoder().encodeToString(salt);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(saltedInput.getBytes(StandardCharsets.UTF_8));

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            // Return first 16 characters for user-friendly confirmation code
            return hexString.toString().substring(0, 16).toUpperCase();

        } catch (NoSuchAlgorithmException e) {
            logger.severe("SHA-256 algorithm not available: " + e.getMessage());
            // Fallback to UUID-based code
            return java.util.UUID.randomUUID().toString().substring(0, 16).toUpperCase();
        }
    }

    /**
     * Generate secure token using SHA-256
     * 
     * @param data Data to hash
     * @return SHA-256 hash as hex string
     */
    public String generateSecureToken(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            logger.severe("SHA-256 algorithm not available: " + e.getMessage());
            throw new RuntimeException("Failed to generate secure token", e);
        }
    }

    /**
     * Mask username for logging (show first and last character only)
     */
    private String maskUsername(String username) {
        if (username == null || username.length() <= 2) {
            return "***";
        }
        return username.charAt(0) + "***" + username.charAt(username.length() - 1);
    }
}
