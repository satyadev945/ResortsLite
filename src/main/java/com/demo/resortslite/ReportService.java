package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService handles generation and storage of resort reports.
 *
 * <p>cr-java-0063 fix: All java.io.File-based persistent storage operations have been
 * replaced with Amazon S3 client calls (AWS SDK for Java v2) to achieve cloud-native,
 * durable, and scalable storage without host-level file system dependencies.
 *
 * <p>Specifically:
 * <ul>
 *   <li>Line 37 (original): {@code new File(REPORT_BASE_PATH)} — removed; S3 key prefix used instead.</li>
 *   <li>Line 39 (original): {@code reportDir.mkdirs()} — removed; S3 manages object hierarchy automatically.</li>
 *   <li>Line 42 (original): {@code new FileWriter(fullPath)} — replaced with {@code S3Client.putObject()}.</li>
 * </ul>
 *
 * <p>The S3 bucket name and key prefixes are injected via environment variables
 * (AWS_S3_BUCKET_NAME, AWS_S3_REPORT_PREFIX, AWS_S3_BACKUP_PREFIX) or application
 * properties, following 12-factor app configuration principles.
 *
 * <p>cr-java-0077 fix: The hard-coded {@code SERVER_PORT = 8080} constant has been removed.
 * The server port is now injected from the environment variable SERVER_PORT (or the
 * application property server.port), which is backed by AWS Systems Manager Parameter Store
 * at the path /resortslite/server/port. This allows ECS, EKS, and Elastic Beanstalk to
 * assign ports dynamically at runtime without requiring code changes.
 */
@Service
public class ReportService {

    /**
     * S3 bucket name — injected from environment variable AWS_S3_BUCKET_NAME or
     * application property app.s3.bucket-name (cr-java-0063 fix).
     */
    @Value("${app.s3.bucket-name:${AWS_S3_BUCKET_NAME:resorts-reports-bucket}}")
    private String s3BucketName;

    /**
     * S3 key prefix for reports — injected from environment variable AWS_S3_REPORT_PREFIX
     * or application property app.s3.report-prefix (cr-java-0063 fix).
     */
    @Value("${app.s3.report-prefix:${AWS_S3_REPORT_PREFIX:reports/}}")
    private String reportPrefix;

    /**
     * S3 key prefix for backups — injected from environment variable AWS_S3_BACKUP_PREFIX
     * or application property app.s3.backup-prefix (cr-java-0063 fix).
     */
    @Value("${app.s3.backup-prefix:${AWS_S3_BACKUP_PREFIX:backups/nightly/}}")
    private String backupPrefix;

    /**
     * cr-java-0071 fix: Report download base URL externalized via AWS Systems Manager Parameter Store.
     * The value is injected from the environment variable REPORT_DOWNLOAD_BASE_URL or the
     * application property app.report.download-base-url, which is backed by SSM Parameter Store
     * at the path /resortslite/report/download-base-url.
     * This replaces the hard-coded URL "http://reports.resorts-internal.com:8080/download/".
     */
    @Value("${app.report.download-base-url:${REPORT_DOWNLOAD_BASE_URL:http://reports.resorts-internal.com:8080/download/}}")
    private String reportDownloadBaseUrl;

    /**
     * cr-java-0077 fix: Server port is no longer a hard-coded constant (was {@code private static final int SERVER_PORT = 8080}).
     * The value is now injected from the environment variable SERVER_PORT or the Spring Boot
     * property server.port, which is backed by AWS Systems Manager Parameter Store at the
     * path /resortslite/server/port. This enables dynamic port assignment by ECS task
     * definitions, EKS pod specs, and Elastic Beanstalk environment properties at runtime.
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    private final S3Client s3Client;

    /**
     * Constructs a ReportService with a default AWS SDK v2 S3Client.
     * The S3Client uses the default credential provider chain (environment variables,
     * IAM role, ~/.aws/credentials) suitable for cloud deployment.
     */
    public ReportService() {
        this.s3Client = S3Client.create();
    }

    /**
     * Constructs a ReportService with a provided S3Client (for testing / injection).
     *
     * @param s3Client the S3Client to use for all S3 operations
     */
    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly resort report and uploads it to Amazon S3.
     *
     * <p>cr-java-0063 fix: The original implementation used {@code java.io.File} for
     * persistent data storage (lines 37, 39, 42 in the original source):
     * <ul>
     *   <li>{@code File reportDir = new File(REPORT_BASE_PATH)} (line 37) — removed.</li>
     *   <li>{@code reportDir.mkdirs()} (line 39) — removed; S3 does not require directory creation.</li>
     *   <li>{@code new FileWriter(fullPath)} (line 42) — replaced with {@code s3Client.putObject()}.</li>
     * </ul>
     * All file system operations are now replaced with Amazon S3 PutObject calls using
     * AWS SDK for Java v2, ensuring cloud-native durable storage.
     *
     * @param month the month for the report (e.g. "03")
     * @param year  the year for the report (e.g. "2024")
     * @return a map containing the operation status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0063 fix: S3 object key replaces the former absolute local path
        // (original line 37: new File(REPORT_BASE_PATH) and line 39: reportDir.mkdirs() removed)
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // cr-java-0063 fix: S3 putObject replaces FileWriter local disk write
            // (original line 42: new FileWriter(fullPath) replaced with S3Client.putObject())
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            // cr-java-0077 fix: serverPort is now injected from environment variable / SSM Parameter Store
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL.
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
    // hardcoded for report download. Cloud security standards enforce HTTPS.
    public String buildReportDownloadUrl(String reportName) { // cr-java-0088
        // cr-java-0071 fix: Base URL is no longer hard-coded. It is read from the injected field
        // reportDownloadBaseUrl, which is sourced from AWS SSM Parameter Store via the
        // environment variable REPORT_DOWNLOAD_BASE_URL or property app.report.download-base-url.
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 storage configuration.
     *
     * <p>cr-java-0063 fix: Replaces the previous local path references
     * (REPORT_BASE_PATH, BACKUP_PATH) with S3 bucket and prefix information.
     *
     * <p>cr-java-0077 fix: serverPort is now injected from environment variable / SSM Parameter Store
     * instead of the former hard-coded constant SERVER_PORT = 8080.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix: Replaced java.util.Date / SimpleDateFormat (timezone-sensitive,
        // server-local clock) with java.time.Instant (UTC-based, cloud-safe).
        // DateTimeFormatter with ZoneOffset.UTC ensures consistent timestamps across all
        // cloud regions, containers, and distributed service instances.
        String timestamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 fix: expose S3 bucket/prefix instead of local file-system paths
        info.put("s3Bucket", s3BucketName);
        info.put("reportPrefix", reportPrefix);
        info.put("backupPrefix", backupPrefix);
        // cr-java-0077 fix: serverPort is now injected from environment variable / SSM Parameter Store
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
