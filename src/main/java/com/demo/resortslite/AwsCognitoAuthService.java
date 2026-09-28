package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Cognito Authentication Service
 * FIXED cr-java-0090: Replaces file-based authentication with AWS Cognito
 * for centralized, encrypted, and auditable user authentication.
 */
@Service
public class AwsCognitoAuthService {

    @Value("${aws.cognito.userPoolId:}")
    private String userPoolId;

    @Value("${aws.cognito.clientId:}")
    private String clientId;

    @Value("${aws.cognito.clientSecret:}")
    private String clientSecret;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    private CognitoIdentityProviderClient cognitoClient;

    @PostConstruct
    public void init() {
        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Authenticates a user using AWS Cognito
     * @param username The username
     * @param password The password
     * @return Authentication result containing tokens
     */
    public Map<String, String> authenticateUser(String username, String password) {
        try {
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);
            
            if (clientSecret != null && !clientSecret.isEmpty()) {
                authParams.put("SECRET_HASH", calculateSecretHash(username));
            }

            InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
                    .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                    .clientId(clientId)
                    .authParameters(authParams)
                    .build();

            InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);
            AuthenticationResultType authResult = authResponse.authenticationResult();

            Map<String, String> result = new HashMap<>();
            result.put("accessToken", authResult.accessToken());
            result.put("idToken", authResult.idToken());
            result.put("refreshToken", authResult.refreshToken());
            result.put("tokenType", authResult.tokenType());
            result.put("expiresIn", String.valueOf(authResult.expiresIn()));

            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to authenticate user with AWS Cognito", e);
        }
    }

    /**
     * Registers a new user in AWS Cognito
     * @param username The username
     * @param password The password
     * @param email The user's email
     * @return User sub (unique identifier)
     */
    public String registerUser(String username, String password, String email) {
        try {
            AttributeType emailAttribute = AttributeType.builder()
                    .name("email")
                    .value(email)
                    .build();

            SignUpRequest signUpRequest = SignUpRequest.builder()
                    .clientId(clientId)
                    .username(username)
                    .password(password)
                    .userAttributes(emailAttribute)
                    .build();

            if (clientSecret != null && !clientSecret.isEmpty()) {
                String secretHash = calculateSecretHash(username);
                signUpRequest = signUpRequest.toBuilder()
                        .secretHash(secretHash)
                        .build();
            }

            SignUpResponse signUpResponse = cognitoClient.signUp(signUpRequest);
            return signUpResponse.userSub();
        } catch (Exception e) {
            throw new RuntimeException("Failed to register user with AWS Cognito", e);
        }
    }

    /**
     * Verifies a user's email using confirmation code
     * @param username The username
     * @param confirmationCode The confirmation code sent to user's email
     */
    public void confirmUserRegistration(String username, String confirmationCode) {
        try {
            ConfirmSignUpRequest confirmRequest = ConfirmSignUpRequest.builder()
                    .clientId(clientId)
                    .username(username)
                    .confirmationCode(confirmationCode)
                    .build();

            if (clientSecret != null && !clientSecret.isEmpty()) {
                String secretHash = calculateSecretHash(username);
                confirmRequest = confirmRequest.toBuilder()
                        .secretHash(secretHash)
                        .build();
            }

            cognitoClient.confirmSignUp(confirmRequest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to confirm user registration with AWS Cognito", e);
        }
    }

    /**
     * Generates a secure confirmation code using SHA-256
     * FIXED cr-java-0090: Replaces MD5 with SHA-256 for secure hash generation
     * @param input The input string to hash
     * @return Secure hash string
     */
    public String generateSecureConfirmationCode(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16).toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Calculates the secret hash for Cognito authentication
     * Required when client secret is configured
     */
    private String calculateSecretHash(String username) {
        try {
            String message = username + clientId;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to calculate secret hash", e);
        }
    }

    /**
     * Validates an access token
     * @param accessToken The access token to validate
     * @return User information if token is valid
     */
    public Map<String, String> validateToken(String accessToken) {
        try {
            GetUserRequest getUserRequest = GetUserRequest.builder()
                    .accessToken(accessToken)
                    .build();

            GetUserResponse getUserResponse = cognitoClient.getUser(getUserRequest);
            
            Map<String, String> userInfo = new HashMap<>();
            userInfo.put("username", getUserResponse.username());
            
            for (AttributeType attribute : getUserResponse.userAttributes()) {
                userInfo.put(attribute.name(), attribute.value());
            }
            
            return userInfo;
        } catch (Exception e) {
            throw new RuntimeException("Failed to validate token with AWS Cognito", e);
        }
    }
}
