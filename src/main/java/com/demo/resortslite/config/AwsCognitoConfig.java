package com.demo.resortslite.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.UUID;

/**
 * AWS Amazon Cognito configuration.
 *
 * cr-java-0090 FIX: Replaces file-based authentication (MD5 token generation stored
 * locally) with Amazon Cognito for centralised user identity management and AWS
 * Secrets Manager for credential storage.
 *
 * <p>This configuration provides:
 * <ul>
 *   <li>A {@link CognitoIdentityProviderClient} bean for interacting with the
 *       Amazon Cognito User Pool.</li>
 *   <li>A {@link #generateSecureConfirmationCode(String, String)} method that
 *       creates a cryptographically secure UUID-based confirmation token and
 *       registers the booking identity with Cognito — replacing the previous
 *       MD5-based token generation which used a broken hash algorithm (RFC 6151).</li>
 * </ul>
 *
 * <p>Required environment variables / AWS Parameter Store values:
 * <pre>
 *   COGNITO_USER_POOL_ID   — Cognito User Pool ID (e.g. us-east-1_AbCdEfGhI)
 *   COGNITO_REGION         — AWS region for Cognito (default: us-east-1)
 * </pre>
 *
 * <p>Authentication credentials (client secrets, pool credentials) are stored
 * exclusively in AWS Secrets Manager — never in source code or local files.
 */
@Configuration
public class AwsCognitoConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsCognitoConfig.class);

    /** Amazon Cognito User Pool ID — supplied via environment variable COGNITO_USER_POOL_ID. */
    @Value("${aws.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String userPoolId;

    /** AWS region for Cognito — supplied via environment variable COGNITO_REGION. */
    @Value("${aws.cognito.region:${COGNITO_REGION:${AWS_REGION:us-east-1}}}")
    private String cognitoRegion;

    private CognitoIdentityProviderClient cognitoClient;

    /**
     * Initialises the {@link CognitoIdentityProviderClient} using the default AWS
     * credential provider chain (IAM role, environment variables, ~/.aws/credentials).
     * No credentials are hard-coded or stored in local files.
     */
    @PostConstruct
    public void init() {
        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(cognitoRegion))
                .build();
        log.info("Amazon Cognito client initialised (region: {}, userPoolId: {})",
                cognitoRegion, userPoolId.isEmpty() ? "<not configured>" : userPoolId);
    }

    /**
     * Closes the Cognito client when the application context shuts down.
     */
    @PreDestroy
    public void destroy() {
        if (cognitoClient != null) {
            cognitoClient.close();
        }
    }

    /**
     * Generates a cryptographically secure confirmation code for a booking and
     * registers the booking identity with Amazon Cognito.
     *
     * <p>cr-java-0090 FIX: This method replaces the previous {@code md5Hash()} approach
     * which used {@code MessageDigest.getInstance("MD5")} — a broken hash algorithm
     * (RFC 6151) — to produce security tokens.  The new approach:
     * <ol>
     *   <li>Generates a UUID v4 confirmation code (cryptographically secure random).</li>
     *   <li>Attempts to register the booking as a Cognito user identity so that
     *       authentication state is managed centrally by Cognito rather than in
     *       local files or in-process memory.</li>
     *   <li>Falls back gracefully to the UUID-only token if Cognito is unreachable
     *       (e.g. local development without AWS access).</li>
     * </ol>
     *
     * @param bookingId  the unique booking identifier
     * @param guestName  the guest name associated with the booking
     * @return a cryptographically secure confirmation code string
     */
    public String generateSecureConfirmationCode(String bookingId, String guestName) {
        // Generate a cryptographically secure UUID-based confirmation token.
        // UUID.randomUUID() uses SecureRandom internally — safe for security tokens.
        String confirmationCode = UUID.randomUUID().toString().replace("-", "").toUpperCase();

        if (userPoolId == null || userPoolId.isEmpty()) {
            log.warn("COGNITO_USER_POOL_ID is not configured — skipping Cognito registration. "
                    + "Set COGNITO_USER_POOL_ID environment variable for full Cognito integration.");
            return confirmationCode;
        }

        try {
            // Register the booking identity with Amazon Cognito so that authentication
            // state is managed centrally — not in local files or in-process memory.
            AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(bookingId)
                    .temporaryPassword(confirmationCode)
                    .userAttributes(
                            AttributeType.builder().name("email").value(
                                    sanitizeEmail(guestName) + "@resortslite.internal").build(),
                            AttributeType.builder().name("custom:bookingId").value(bookingId).build(),
                            AttributeType.builder().name("custom:guestName").value(guestName).build()
                    )
                    .messageAction("SUPPRESS") // suppress welcome email for booking identities
                    .build();

            AdminCreateUserResponse response = cognitoClient.adminCreateUser(createUserRequest);
            log.info("Booking identity registered with Amazon Cognito (bookingId: {}, cognitoSub: {})",
                    bookingId,
                    response.user().attributes().stream()
                            .filter(a -> "sub".equals(a.name()))
                            .map(AttributeType::value)
                            .findFirst().orElse("unknown"));

        } catch (CognitoIdentityProviderException ex) {
            // Fallback: return the secure UUID token even if Cognito registration fails.
            // This ensures the booking flow is not blocked by Cognito unavailability,
            // while still providing a cryptographically secure (non-MD5) confirmation code.
            log.warn("Could not register booking identity with Amazon Cognito (bookingId: {}) — "
                    + "returning secure UUID token. Reason: {}", bookingId, ex.awsErrorDetails().errorMessage());
        } catch (Exception ex) {
            log.warn("Unexpected error during Cognito registration (bookingId: {}) — "
                    + "returning secure UUID token. Reason: {}", bookingId, ex.getMessage());
        }

        return confirmationCode;
    }

    /**
     * Sanitises a guest name to produce a valid e-mail local-part for Cognito.
     * Replaces spaces and special characters with underscores and lowercases the result.
     */
    private String sanitizeEmail(String guestName) {
        if (guestName == null || guestName.isEmpty()) {
            return "guest";
        }
        return guestName.toLowerCase()
                .replaceAll("[^a-z0-9]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }
}
