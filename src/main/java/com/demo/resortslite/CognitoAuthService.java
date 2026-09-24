package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * cr-java-0090 REMEDIATION: File-based Authentication replaced with
 * AWS Secrets Manager and Amazon Cognito.
 *
 * <p>Previously, authentication credentials and user data were stored in
 * local files, which does not scale horizontally and creates security and
 * consistency issues in distributed cloud environments.</p>
 *
 * <p>This service provides centralized, encrypted, and auditable authentication
 * by delegating all user identity management to Amazon Cognito User Pools and
 * storing application secrets in AWS Secrets Manager. Key benefits:</p>
 * <ul>
 *   <li>Centralized user lifecycle management (create, authenticate, disable)</li>
 *   <li>Built-in MFA, password policies, and account recovery</li>
 *   <li>JWT-based token issuance (ID token, access token, refresh token)</li>
 *   <li>Audit trail via AWS CloudTrail for all authentication events</li>
 *   <li>Automatic credential rotation via Secrets Manager</li>
 *   <li>Stateless authentication — no local file or in-memory user store</li>
 * </ul>
 *
 * <p>Configure via environment variables or application.properties:</p>
 * <pre>
 *   AWS_REGION                    - AWS region (default: us-east-1)
 *   COGNITO_USER_POOL_ID          - Cognito User Pool ID
 *   COGNITO_APP_CLIENT_ID         - Cognito App Client ID
 *   COGNITO_APP_CLIENT_SECRET_NAME - Secrets Manager secret name for app client secret
 *                                    (default: resortslite/cognito/app-client-secret)
 * </pre>
 */
@Service
public class CognitoAuthService {

    private static final Logger logger = Logger.getLogger(CognitoAuthService.class.getName());

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.cognito.user-pool-id:}")
    private String userPoolId;

    @Value("${aws.cognito.app-client-id:}")
    private String appClientId;

    /**
     * Secrets Manager secret name that holds the Cognito App Client Secret.
     * Storing the client secret in Secrets Manager (rather than in properties files
     * or environment variables) prevents accidental exposure in logs or config dumps.
     */
    @Value("${aws.cognito.app-client-secret-name:resortslite/cognito/app-client-secret}")
    private String appClientSecretName;

    private CognitoIdentityProviderClient cognitoClient;
    private SecretsManagerClient secretsManagerClient;

    /**
     * Initialises the AWS SDK clients after Spring injects all property values.
     * Using @PostConstruct ensures the region and other config values are available.
     */
    @PostConstruct
    public void init() {
        Region region = Region.of(awsRegion);
        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(region)
                .build();
        this.secretsManagerClient = SecretsManagerClient.builder()
                .region(region)
                .build();
        logger.info("CognitoAuthService initialised for region: " + awsRegion
                + ", userPoolId: " + userPoolId);
    }

    /**
     * Authenticates a user against Amazon Cognito User Pool using the
     * ADMIN_USER_PASSWORD_AUTH flow (server-side authentication).
     *
     * <p>Replaces any previous file-based credential lookup. Credentials are
     * never stored locally; Cognito validates them and returns JWT tokens.</p>
     *
     * @param username the Cognito username (typically the guest's email)
     * @param password the user's password
     * @return a map containing {@code idToken}, {@code accessToken},
     *         {@code refreshToken}, and {@code tokenType} on success,
     *         or an {@code error} key on failure
     */
    public Map<String, String> authenticateUser(String username, String password) {
        Map<String, String> result = new HashMap<>();
        try {
            // Retrieve the Cognito App Client Secret from AWS Secrets Manager
            // so it is never hard-coded or stored in local files.
            String clientSecret = fetchClientSecretFromSecretsManager();

            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);
            if (clientSecret != null && !clientSecret.isEmpty()) {
                // Cognito requires a SECRET_HASH when the app client has a secret configured.
                authParams.put("SECRET_HASH", computeSecretHash(username, appClientId, clientSecret));
            }

            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .userPoolId(userPoolId)
                    .clientId(appClientId)
                    .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                    .authParameters(authParams)
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

            if (authResponse.authenticationResult() != null) {
                result.put("idToken",      authResponse.authenticationResult().idToken());
                result.put("accessToken",  authResponse.authenticationResult().accessToken());
                result.put("refreshToken", authResponse.authenticationResult().refreshToken());
                result.put("tokenType",    authResponse.authenticationResult().tokenType());
                logger.info("User authenticated successfully via Amazon Cognito: " + username);
            } else {
                // Cognito may return a challenge (e.g., NEW_PASSWORD_REQUIRED)
                result.put("challengeName", authResponse.challengeNameAsString());
                result.put("session",       authResponse.session());
                logger.info("Cognito authentication challenge for user: " + username
                        + " — challenge: " + authResponse.challengeNameAsString());
            }
        } catch (NotAuthorizedException e) {
            logger.warning("Authentication failed for user: " + username + " — " + e.getMessage());
            result.put("error", "Invalid username or password.");
        } catch (UserNotFoundException e) {
            logger.warning("User not found in Cognito User Pool: " + username);
            result.put("error", "User not found.");
        } catch (CognitoIdentityProviderException e) {
            logger.severe("Cognito authentication error for user: " + username + " — " + e.getMessage());
            result.put("error", "Authentication service error: " + e.awsErrorDetails().errorMessage());
        } catch (Exception e) {
            logger.severe("Unexpected error during Cognito authentication: " + e.getMessage());
            result.put("error", "Authentication failed due to an internal error.");
        }
        return result;
    }

    /**
     * Registers a new guest user in the Amazon Cognito User Pool.
     *
     * <p>Replaces any previous file-based user registration that wrote user
     * records to local files. User data is now managed centrally in Cognito
     * with built-in lifecycle management and audit logging.</p>
     *
     * @param username  the desired username (typically the guest's email)
     * @param email     the guest's email address
     * @param tempPassword a temporary password; Cognito will prompt the user to change it
     * @return a map with {@code userId} and {@code status} on success,
     *         or an {@code error} key on failure
     */
    public Map<String, String> registerUser(String username, String email, String tempPassword) {
        Map<String, String> result = new HashMap<>();
        try {
            AdminCreateUserRequest createRequest = AdminCreateUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .temporaryPassword(tempPassword)
                    .userAttributes(
                            AttributeType.builder().name("email").value(email).build(),
                            AttributeType.builder().name("email_verified").value("true").build()
                    )
                    .messageAction("SUPPRESS") // suppress the welcome email for programmatic creation
                    .build();

            AdminCreateUserResponse createResponse = cognitoClient.adminCreateUser(createRequest);

            result.put("userId", createResponse.user().username());
            result.put("status", createResponse.user().userStatusAsString());
            logger.info("User registered in Amazon Cognito User Pool: " + username);
        } catch (CognitoIdentityProviderException e) {
            logger.severe("Failed to register user in Cognito: " + username + " — " + e.getMessage());
            result.put("error", "User registration failed: " + e.awsErrorDetails().errorMessage());
        } catch (Exception e) {
            logger.severe("Unexpected error during Cognito user registration: " + e.getMessage());
            result.put("error", "User registration failed due to an internal error.");
        }
        return result;
    }

    /**
     * Retrieves user details from the Amazon Cognito User Pool.
     *
     * <p>Replaces any previous file-based user lookup. User attributes
     * (email, status, custom attributes) are fetched directly from Cognito.</p>
     *
     * @param username the Cognito username to look up
     * @return a map of user attributes, or an {@code error} key on failure
     */
    public Map<String, String> getUserDetails(String username) {
        Map<String, String> result = new HashMap<>();
        try {
            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            result.put("username", getUserResponse.username());
            result.put("status",   getUserResponse.userStatusAsString());
            getUserResponse.userAttributes().forEach(attr ->
                    result.put(attr.name(), attr.value()));
            logger.info("User details retrieved from Amazon Cognito: " + username);
        } catch (UserNotFoundException e) {
            logger.warning("User not found in Cognito User Pool: " + username);
            result.put("error", "User not found.");
        } catch (CognitoIdentityProviderException e) {
            logger.severe("Failed to retrieve user from Cognito: " + username + " — " + e.getMessage());
            result.put("error", "User lookup failed: " + e.awsErrorDetails().errorMessage());
        } catch (Exception e) {
            logger.severe("Unexpected error during Cognito user lookup: " + e.getMessage());
            result.put("error", "User lookup failed due to an internal error.");
        }
        return result;
    }

    /**
     * Fetches the Cognito App Client Secret from AWS Secrets Manager.
     *
     * <p>The client secret is stored in Secrets Manager (not in properties files
     * or environment variables) to prevent accidental exposure. This method is
     * called at authentication time so the secret can be rotated without
     * redeploying the application.</p>
     *
     * @return the plain-text client secret, or an empty string if unavailable
     */
    private String fetchClientSecretFromSecretsManager() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(appClientSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secret = response.secretString();
            logger.fine("Cognito app client secret retrieved from AWS Secrets Manager.");
            return secret != null ? secret.trim() : "";
        } catch (Exception e) {
            logger.warning("Could not retrieve Cognito client secret from Secrets Manager ("
                    + e.getMessage() + "). Proceeding without SECRET_HASH.");
            return "";
        }
    }

    /**
     * Computes the HMAC-SHA256 SECRET_HASH required by Cognito when the app
     * client has a client secret configured.
     *
     * <p>Formula: Base64( HMAC-SHA256( clientSecret, username + clientId ) )</p>
     *
     * @param username  the Cognito username
     * @param clientId  the Cognito App Client ID
     * @param clientSecret the App Client Secret retrieved from Secrets Manager
     * @return the Base64-encoded HMAC-SHA256 hash
     */
    private String computeSecretHash(String username, String clientId, String clientSecret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey =
                    new javax.crypto.spec.SecretKeySpec(
                            clientSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "HmacSHA256");
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal((username + clientId)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(rawHmac);
        } catch (Exception e) {
            logger.severe("Failed to compute Cognito SECRET_HASH: " + e.getMessage());
            throw new RuntimeException("Failed to compute Cognito SECRET_HASH", e);
        }
    }

    /**
     * Closes the AWS SDK clients when the Spring context is destroyed.
     */
    @PreDestroy
    public void destroy() {
        if (cognitoClient != null) {
            cognitoClient.close();
        }
        if (secretsManagerClient != null) {
            secretsManagerClient.close();
        }
    }
}
