package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * cr-java-0090 FIX: Replaces file-based authentication with Amazon Cognito.
 *
 * <p>Previously, authentication credentials (DB_USER, DB_PASS) were stored as
 * static final String constants directly in BookingService.java source code.
 * User identity was managed locally with no lifecycle management, no audit trail,
 * and no ability to rotate credentials without a code change and redeployment.
 *
 * <p>This service delegates all user identity operations to Amazon Cognito User Pools,
 * providing:
 * <ul>
 *   <li>Centralized, encrypted credential storage — no credentials in source code</li>
 *   <li>Built-in user lifecycle management (create, disable, delete, password reset)</li>
 *   <li>Auditable authentication events via AWS CloudTrail</li>
 *   <li>JWT-based tokens (ID token, access token, refresh token) for stateless auth</li>
 *   <li>Automatic token expiry and refresh without application code changes</li>
 *   <li>MFA, password policies, and account lockout managed by Cognito</li>
 * </ul>
 *
 * <p>Required environment variables / AWS SSM parameters:
 * <pre>
 *   COGNITO_USER_POOL_ID   — Cognito User Pool ID  (e.g. us-east-1_AbCdEfGhI)
 *   COGNITO_CLIENT_ID      — App client ID within the User Pool
 *   CLOUD_AWS_REGION       — AWS region (e.g. us-east-1)
 * </pre>
 */
@Service
public class CognitoAuthService {

    private static final Logger logger = Logger.getLogger(CognitoAuthService.class.getName());

    // cr-java-0090 FIX: Cognito User Pool ID externalised to environment variable.
    // No user data or credentials are stored in source code.
    @Value("${cognito.user-pool-id:#{environment['COGNITO_USER_POOL_ID']}}")
    private String userPoolId;

    // cr-java-0090 FIX: Cognito App Client ID externalised to environment variable.
    @Value("${cognito.client-id:#{environment['COGNITO_CLIENT_ID']}}")
    private String clientId;

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Authenticates a user against Amazon Cognito User Pool using ADMIN_USER_PASSWORD_AUTH flow.
     *
     * <p>Returns a map containing Cognito JWT tokens on success:
     * <ul>
     *   <li>{@code idToken}      — JWT ID token (contains user claims)</li>
     *   <li>{@code accessToken}  — JWT access token (used for API authorization)</li>
     *   <li>{@code refreshToken} — Refresh token for obtaining new tokens</li>
     *   <li>{@code expiresIn}    — Token expiry in seconds</li>
     * </ul>
     *
     * @param username Cognito username (typically the guest's email address)
     * @param password User's password (never stored locally — passed directly to Cognito)
     * @return Map of token fields on successful authentication
     * @throws RuntimeException if authentication fails or Cognito is unreachable
     */
    public Map<String, String> authenticateUser(String username, String password) {
        Map<String, String> authParams = new HashMap<>();
        authParams.put("USERNAME", username);
        authParams.put("PASSWORD", password);

        try (CognitoIdentityProviderClient cognitoClient = buildCognitoClient()) {
            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .userPoolId(userPoolId)
                    .clientId(clientId)
                    .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                    .authParameters(authParams)
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

            Map<String, String> tokens = new HashMap<>();
            tokens.put("idToken",      authResponse.authenticationResult().idToken());
            tokens.put("accessToken",  authResponse.authenticationResult().accessToken());
            tokens.put("refreshToken", authResponse.authenticationResult().refreshToken());
            tokens.put("expiresIn",    String.valueOf(authResponse.authenticationResult().expiresIn()));

            logger.info("Cognito authentication successful for user: " + username);
            return tokens;

        } catch (NotAuthorizedException e) {
            logger.warning("Cognito authentication failed — invalid credentials for user: " + username);
            throw new RuntimeException("Authentication failed: invalid username or password", e);
        } catch (UserNotFoundException e) {
            logger.warning("Cognito authentication failed — user not found: " + username);
            throw new RuntimeException("Authentication failed: user not found", e);
        } catch (Exception e) {
            logger.severe("Cognito authentication error for user " + username + ": " + e.getMessage());
            throw new RuntimeException("Authentication service unavailable", e);
        }
    }

    /**
     * Retrieves user profile attributes from Amazon Cognito User Pool.
     *
     * <p>Replaces the previous pattern of reading user data from local files or
     * hardcoded maps. All user attributes (email, name, loyalty tier, etc.) are
     * managed centrally in Cognito and fetched on demand.
     *
     * @param username Cognito username to look up
     * @return Map of Cognito user attribute names to values
     * @throws RuntimeException if the user is not found or Cognito is unreachable
     */
    public Map<String, String> getUserAttributes(String username) {
        try (CognitoIdentityProviderClient cognitoClient = buildCognitoClient()) {
            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            Map<String, String> attributes = new HashMap<>();
            getUserResponse.userAttributes().forEach(attr ->
                    attributes.put(attr.name(), attr.value()));

            logger.info("Retrieved Cognito user attributes for: " + username);
            return attributes;

        } catch (UserNotFoundException e) {
            logger.warning("Cognito user not found: " + username);
            throw new RuntimeException("User not found in identity provider: " + username, e);
        } catch (Exception e) {
            logger.severe("Failed to retrieve Cognito user attributes for " + username
                    + ": " + e.getMessage());
            throw new RuntimeException("Identity service unavailable", e);
        }
    }

    /**
     * Validates whether a given Cognito access token is still active.
     *
     * <p>This replaces the previous pattern of checking credentials stored in local
     * files or session maps. Token validation is delegated entirely to Cognito,
     * which enforces expiry, revocation, and scope checks automatically.
     *
     * @param accessToken JWT access token issued by Cognito
     * @return {@code true} if the token is valid and not expired; {@code false} otherwise
     */
    public boolean isTokenValid(String accessToken) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            return false;
        }
        try (CognitoIdentityProviderClient cognitoClient = buildCognitoClient()) {
            // GetUser call with the access token validates it against Cognito
            cognitoClient.getUser(req -> req.accessToken(accessToken));
            return true;
        } catch (NotAuthorizedException e) {
            logger.fine("Cognito token validation failed — token expired or revoked");
            return false;
        } catch (Exception e) {
            logger.warning("Cognito token validation error: " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a Cognito Identity Provider client scoped to the configured AWS region.
     * The client is created per-request (AutoCloseable) to avoid connection leaks in
     * cloud environments where the IAM role or network topology may change.
     */
    private CognitoIdentityProviderClient buildCognitoClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
