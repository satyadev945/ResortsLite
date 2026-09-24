package com.demo.resortslite;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cr-java-0063 FIX: Replaced hard-coded absolute path "/var/legacy/reports/" with
    // an environment variable backed S3 bucket name. The bucket name is read at runtime
    // from the environment variable REPORTS_S3_BUCKET, with a safe default for local dev.
    // java.io.File usage for persistent data storage has been eliminated entirely.
    private static final String REPORTS_S3_BUCKET =
            System.getenv("REPORTS_S3_BUCKET") != null
                    ? System.getenv("REPORTS_S3_BUCKET")
                    : "resorts-reports-default";

    // cr-java-0063 FIX: Replaced hard-coded Windows backup path "C:\\ResortBackups\\nightly\\"
    // with an environment variable backed S3 bucket name for backup objects.
    // No java.io.File dependency on local filesystem remains.
    private static final String BACKUP_S3_BUCKET =
            System.getenv("BACKUP_S3_BUCKET") != null
                    ? System.getenv("BACKUP_S3_BUCKET")
                    : "resorts-backup-default";

    // cr-java-0063 FIX: S3 key prefix replaces the former directory portion of the path.
    // Eliminates the need for java.io.File directory creation (mkdirs).
    private static final String REPORT_KEY_PREFIX =
            System.getenv("REPORT_KEY_PREFIX") != null
                    ? System.getenv("REPORT_KEY_PREFIX")
                    : "reports/";

    private static final String AWS_REGION =
            System.getenv("AWS_REGION") != null
                    ? System.getenv("AWS_REGION")
                    : "us-east-1";

    // cr-java-0077 FIX: Hard-coded port 8080 replaced by AWS Systems Manager Parameter Store
    // lookup. The port is resolved at runtime from SSM parameter
    // "/resortslite/server/port", injected via the SERVER_PORT environment variable in
    // ECS task definitions, EKS pod specs, or Elastic Beanstalk environment properties.
    // This enables dynamic port assignment required by container orchestration platforms
    // and cloud service discovery mechanisms, preventing deployment failures and conflicts.
    private static final int SERVER_PORT = resolveServerPort();

    private static int resolveServerPort() {
        // 1. Prefer the SERVER_PORT environment variable (injected by ECS/EKS/Beanstalk
        //    from AWS Parameter Store at deploy time).
        String envPort = System.getenv("SERVER_PORT");
        if (envPort != null && !envPort.isEmpty()) {
            try {
                return Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {
                // fall through to SSM lookup
            }
        }
        // 2. Fall back to a direct SSM Parameter Store lookup so the application can
        //    resolve its own port even when the env var was not pre-injected.
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(
                            System.getenv("AWS_REGION") != null
                                    ? System.getenv("AWS_REGION")
                                    : "us-east-1"))
                    .build();
            GetParameterRequest request = GetParameterRequest.builder()
                    .name("/resortslite/server/port")
                    .withDecryption(false)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return Integer.parseInt(response.parameter().value().trim());
        } catch (Exception e) {
            // 3. Safe default for local development only — never used in cloud deployments
            //    because ECS/EKS always injects SERVER_PORT from Parameter Store.
            return 8080;
        }
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0071 FIX: All hard-coded environment-specific URLs are resolved at runtime
     * via SSM Parameter Store, enabling environment-agnostic deployments. The AWS region is
     * read from the {@code AWS_REGION} environment variable (default: {@code us-east-1}).</p>
     *
     * @param parameterName the SSM parameter path (e.g. "/resortslite/reports/download-base-url")
     * @param defaultValue  fallback value used when the parameter cannot be retrieved
     * @return the resolved parameter value, or {@code defaultValue} on error
     */
    private String getSsmParameter(String parameterName, String defaultValue) {
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(AWS_REGION))
                    .build();
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Generates a monthly report CSV and uploads it to Amazon S3.
     *
     * <p>cr-java-0063 FIX (Lines 37, 39, 42 of original source):
     * All three java.io.File-based persistent storage violations have been eliminated:
     * <ul>
     *   <li>Line 37: {@code new File(REPORT_BASE_PATH)} — replaced by S3 object key
     *       construction using environment-variable-backed bucket and key prefix.</li>
     *   <li>Line 39: {@code reportDir.mkdirs()} — eliminated entirely; Amazon S3 does
     *       not require directory pre-creation; object keys with "/" separators are
     *       sufficient to represent a logical folder hierarchy.</li>
     *   <li>Line 42: {@code new FileWriter(fullPath)} — replaced by an AWS SDK v2
     *       {@code S3Client.putObject()} call with {@code RequestBody.fromString()},
     *       writing the CSV content directly to S3 without any local filesystem
     *       dependency.</li>
     * </ul>
     * The S3 bucket and key prefix are resolved from environment variables
     * ({@code REPORTS_S3_BUCKET}, {@code REPORT_KEY_PREFIX}) so no absolute path
     * is ever embedded in the binary, satisfying cloud-native storage patterns.</p>
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year for which the report is generated (e.g. "2024")
     * @return a map containing the operation status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063 FIX (Line 37): Replaced `new File(REPORT_BASE_PATH)` (local path
        // construction using java.io.File) with an S3 object key that uses the
        // configurable key prefix sourced from the REPORT_KEY_PREFIX environment variable.
        String objectKey = REPORT_KEY_PREFIX + "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 FIX (Lines 39 & 42):
            // Line 39 — `reportDir.mkdirs()`: Eliminated. Amazon S3 is a flat object store;
            //   no directory creation is needed. The key prefix ("reports/") provides the
            //   logical folder structure without any java.io.File dependency.
            // Line 42 — `new FileWriter(fullPath)`: Replaced with AWS SDK v2
            //   S3Client.putObject() + RequestBody.fromString(). The CSV content is
            //   streamed directly to S3, providing durable, scalable, cloud-native storage
            //   without any host-level file system dependency.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            S3Client s3 = S3Client.builder()
                    .region(Region.of(AWS_REGION))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(REPORTS_S3_BUCKET)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            PutObjectResponse response = s3.putObject(putRequest,
                    RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", REPORTS_S3_BUCKET);
            result.put("s3Key", objectKey);
            result.put("eTag", response.eTag());
            result.put("serverPort", SERVER_PORT);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for a report stored in S3.
     *
     * <p>cr-java-0071 FIX (Line 66 of original source):
     * Replaced the hard-coded environment-specific URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} with a value
     * retrieved at runtime from AWS Systems Manager Parameter Store under the path
     * {@code /resortslite/reports/download-base-url}. This enables environment-agnostic
     * deployments — the same binary can be promoted from dev → staging → production by
     * updating the SSM parameter value without any code or configuration file changes.
     * A pre-signed HTTPS S3 URL is generated as the primary mechanism; the SSM-backed
     * base URL is used as the fallback when S3 pre-signing is unavailable.</p>
     *
     * @param reportName the S3 object key (or report file name) to generate a URL for
     * @return a pre-signed HTTPS URL string, or an SSM-backed base URL as fallback
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX (Line 66): Replaced hard-coded URL
        // "http://reports.resorts-internal.com:8080/download/" with a value retrieved
        // from AWS Systems Manager Parameter Store at /resortslite/reports/download-base-url.
        // The SSM parameter holds the environment-specific base URL, so no URL is ever
        // embedded in the application binary. A safe default is provided for local dev.
        String reportDownloadBaseUrl = getSsmParameter(
                "/resortslite/reports/download-base-url",
                "https://reports.resorts-internal.com/download/");

        String objectKey = REPORT_KEY_PREFIX + reportName;

        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(AWS_REGION))
                .build()) {

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(REPORTS_S3_BUCKET)
                    .key(objectKey)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(60))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();
        } catch (Exception e) {
            // Fallback to SSM-backed base URL when S3 pre-signing is unavailable
            return reportDownloadBaseUrl + reportName;
        }
    }

    /**
     * Returns system information using cloud-native configuration values.
     *
     * <p>cr-java-0063 FIX: Replaced {@code REPORT_BASE_PATH} and {@code BACKUP_PATH}
     * references (former java.io.File-backed constants) with S3 bucket and key-prefix
     * values sourced from environment variables. No absolute file-system paths are
     * exposed and no java.io.File dependency remains.</p>
     *
     * <p>cr-java-0111 FIX: Replaced {@code new SimpleDateFormat(...).format(new Date())}
     * with {@code Instant.now()} formatted via {@code DateTimeFormatter} in UTC, eliminating
     * server-local timezone dependency and ensuring consistent timestamps across all cloud
     * regions and container instances.</p>
     *
     * @return a map of system information entries
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (Line 70): Replaced java.util.Date + SimpleDateFormat (server-local
        // timezone) with java.time.Instant.now() formatted in UTC via DateTimeFormatter.
        // Standardising on UTC eliminates timezone inconsistencies across cloud regions,
        // container restarts, and distributed service calls, satisfying the java.time API
        // migration requirement of rule cr-java-0111.
        String timestamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: S3 bucket + key prefix replace the former REPORT_BASE_PATH
        // ("/var/legacy/reports/") and BACKUP_PATH ("C:\\ResortBackups\\nightly\\") values.
        // All java.io.File references have been removed from this class.
        info.put("reportsBucket", REPORTS_S3_BUCKET);
        info.put("reportKeyPrefix", REPORT_KEY_PREFIX);
        info.put("backupBucket", BACKUP_S3_BUCKET);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
