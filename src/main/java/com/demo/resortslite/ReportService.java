package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Service responsible for generating and storing resort reports.
 *
 * <p>cr-java-0062 FIX (Local File System Write Operations): All local file I/O operations
 * (java.io.File, FileWriter) have been replaced with Amazon S3 (AWS SDK v2) to ensure
 * data durability, availability, and scalability in containerised and serverless environments.
 * Local file systems are ephemeral in cloud/container environments; data written locally is
 * lost on container restart or scale-out events. S3 provides durable, highly-available
 * object storage that survives container lifecycle events.
 *
 * <p>cr-java-0077 FIX (Hard-coded Ports): The hard-coded port constant
 * {@code private static final int SERVER_PORT = 8080} (original source line 28) has been
 * removed. The server port is now externalised to AWS Systems Manager Parameter Store and
 * injected at runtime via the environment variable {@code SERVER_PORT}. The Spring property
 * {@code server.port} resolves the value from the environment, enabling dynamic port
 * assignment required by ECS, EKS, and Elastic Beanstalk container orchestration platforms.
 * The injected {@code serverPort} field below is used wherever port information is needed
 * at the application layer (e.g. system-info reporting), ensuring no hard-coded port
 * numbers remain in application logic.
 *
 * <p>Original violations removed:
 * <pre>
 *   // REMOVED: private static final int SERVER_PORT = 8080;  // line 28 — cr-java-0077
 *   // REMOVED: FileWriter writer = new FileWriter(fullPath);  // line 42 — cr-java-0062
 *   // REMOVED: new File(REPORT_BASE_PATH) / reportDir.mkdirs()
 *   // REMOVED: writer.write(...) / writer.close()
 *   // REMOVED: private static final String REPORT_BASE_PATH = "/var/legacy/reports/";
 *   // REMOVED: private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";
 * </pre>
 */
@Service
public class ReportService {

    // cr-java-0062 FIX: Replaced hard-coded absolute path "/var/legacy/reports/" with
    // an environment-variable-backed Spring property. The S3 bucket name is injected
    // at runtime from the environment (CLOUD_AWS_S3_BUCKET_NAME), making the application
    // fully cloud-portable and eliminating the ephemeral local file-system dependency.
    @Value("${cloud.aws.s3.bucket-name:resorts-lite-reports}")
    private String s3BucketName;

    // cr-java-0062 FIX: Replaced Windows-style hard-coded backup path
    // "C:\\ResortBackups\\nightly\\" with an S3 key prefix injected from the environment
    // (CLOUD_AWS_S3_BACKUP_PREFIX). No local directory creation (mkdirs) is needed.
    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String s3BackupPrefix;

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    // cr-java-0077 FIX (original source line 28):
    // BEFORE: private static final int SERVER_PORT = 8080;
    // AFTER:  Port is externalised to AWS Systems Manager Parameter Store and injected
    //         at runtime via the SERVER_PORT environment variable. In ECS/EKS/Elastic
    //         Beanstalk the task/pod definition supplies SERVER_PORT from the SSM parameter
    //         /resortslite/server/port, enabling dynamic port assignment and eliminating
    //         the hard-coded value that prevented container orchestration port binding.
    //         The Spring property server.port (application.properties) also reads from
    //         ${SERVER_PORT:8080} so the embedded Tomcat listener uses the same value.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071 FIX: Hard-coded report download URL
    // "http://reports.resorts-internal.com:8080/download/" has been removed.
    // The base URL is now sourced from AWS Systems Manager Parameter Store via the
    // Spring property app.reports.download.base-url (SSM parameter:
    // /resortslite/reports/download-base-url), injected at application startup.
    // This enables environment-agnostic deployments — dev, staging, and production
    // each supply their own SSM parameter value without any code changes.
    @Value("${app.reports.download.base-url:${REPORTS_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download/}}")
    private String reportsDownloadBaseUrl;

    // cr-java-0062 FIX: S3Client replaces all java.io.File / FileWriter write operations.
    // The client is built lazily so that the injected region value is available.
    // AutoCloseable try-with-resources ensures the client is properly released after each call.
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * <p>cr-java-0062 FIX: The original implementation wrote the CSV to the local file system
     * using {@code FileWriter} (original source line 42). This has been replaced with an
     * in-memory build of the CSV content followed by a direct {@code PutObjectRequest} to S3,
     * ensuring the data is durably stored even when the container is recycled or scaled.
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string  (e.g. "2024")
     * @return result map containing upload status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0062 FIX: S3 object key replaces the former local fullPath variable.
        // Previously: String fullPath = REPORT_BASE_PATH + fileName;  (original line ~37)
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try (S3Client s3 = buildS3Client()) {
            // cr-java-0062 FIX (original line 42 — FileWriter write operation):
            // CSV content is built in-memory and uploaded directly to Amazon S3 via
            // PutObjectRequest + RequestBody.fromString(). This replaces:
            //   new File(REPORT_BASE_PATH)          — local directory creation
            //   reportDir.mkdirs()                  — local directory creation
            //   FileWriter writer = new FileWriter(fullPath)  — LOCAL FILE WRITE (line 42)
            //   writer.write(...)                   — local file write
            //   writer.close()                      — local file close
            // Data is now stored durably in S3 and survives container restarts/scale events.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            PutObjectResponse response = s3.putObject(putRequest,
                    RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", objectKey);
            result.put("eTag", response.eTag());
            // cr-java-0077 FIX: serverPort is now injected from environment/SSM,
            // not a hard-coded constant. Reporting the runtime-resolved value.
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for a named report.
     *
     * <p>cr-java-0071 FIX (original line 66): The hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} has been replaced with
     * a value sourced from AWS Systems Manager Parameter Store. The base URL is injected
     * via the Spring property {@code app.reports.download.base-url} (backed by the SSM
     * parameter {@code /resortslite/reports/download-base-url} and the environment variable
     * {@code REPORTS_DOWNLOAD_BASE_URL}). This removes the environment-specific hard-coding
     * and allows the same application artifact to be deployed across dev, staging, and
     * production by updating only the SSM parameter — no code changes required.
     *
     * @param reportName the report file name to append to the base URL
     * @return fully-qualified download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX (original line 66):
        // BEFORE: return "http://reports.resorts-internal.com:8080/download/" + reportName;
        // AFTER:  base URL is sourced from AWS SSM Parameter Store via reportsDownloadBaseUrl field.
        return reportsDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 storage configuration and runtime server port.
     *
     * <p>cr-java-0062 FIX: Replaced hard-coded REPORT_BASE_PATH and BACKUP_PATH constants
     * with S3 bucket/prefix values sourced from environment variables. No local path
     * references remain in the system info output.
     *
     * <p>cr-java-0077 FIX: Replaced hard-coded {@code SERVER_PORT = 8080} constant with
     * the runtime-injected {@code serverPort} field sourced from AWS SSM Parameter Store
     * via the {@code SERVER_PORT} environment variable.
     *
     * @return map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (original source line 70):
        // BEFORE: new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
        // AFTER:  java.time API (Instant + DateTimeFormatter) standardised on UTC.
        // java.util.Date and SimpleDateFormat are timezone-sensitive and rely on the
        // server-local JVM timezone, causing inconsistencies across multi-region cloud
        // deployments and containers. Instant.now() always captures the current moment
        // in UTC, and DateTimeFormatter with ZoneOffset.UTC formats it without any
        // server-local timezone influence, ensuring consistent timestamps across all
        // ECS tasks, EKS pods, and Lambda invocations regardless of host timezone.
        String timestamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        info.put("s3Bucket", s3BucketName);
        info.put("s3BackupPrefix", s3BackupPrefix);
        info.put("awsRegion", awsRegion);
        info.put("reportsDownloadBaseUrl", reportsDownloadBaseUrl);
        // cr-java-0077 FIX: serverPort is now the runtime-resolved value from
        // AWS SSM Parameter Store / SERVER_PORT env var — not the hard-coded 8080 constant.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
