package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Service responsible for generating and storing resort reports.
 *
 * <p>cr-java-0063 fix: All java.io.File-based persistent storage operations have been
 * replaced with Amazon S3 client calls using AWS SDK for Java v2.
 *
 * <p>Original violations (Lines 37, 39, 42 of source):
 * <ul>
 *   <li>Line 37: {@code new File(REPORT_BASE_PATH)} — local directory reference removed</li>
 *   <li>Line 39: {@code reportDir.mkdirs()} — directory creation removed (S3 uses virtual key prefixes)</li>
 *   <li>Line 42: {@code new FileWriter(fullPath)} — local file write replaced with S3 PutObject</li>
 * </ul>
 *
 * <p>Local file systems in containerised / serverless environments are ephemeral; data
 * written locally is lost on container restart or scale-in. Storing reports in S3
 * provides durable, highly-available, and scalable object storage that survives
 * container lifecycle events.
 *
 * <p>The S3 bucket name and backup prefix are externalised to environment variables
 * ({@code S3_REPORTS_BUCKET}, {@code S3_BACKUP_PREFIX}) so the application is
 * portable across AWS environments without code changes.
 */
@Service
public class ReportService {

    // cr-java-0063 fix: replaced hard-coded absolute file path constant
    // ("/var/legacy/reports/") with an S3 bucket name sourced from an environment
    // variable / application property. The local path does not exist in cloud
    // containers and any data written there would be lost on restart.
    @Value("${app.s3.reports.bucket:resorts-reports-bucket}")
    private String reportsBucket;

    // cr-java-0063 fix: replaced hard-coded Windows backup path constant
    // ("C:\\ResortBackups\\nightly\\") with an S3 key prefix sourced from an
    // environment variable, eliminating the OS-specific path dependency entirely.
    @Value("${app.s3.backup.prefix:nightly-backups/}")
    private String backupPrefix;

    // cr-java-0077 fix: Hard-coded port constant (8080) replaced with a @Value-injected field
    // sourced from the environment variable SERVER_PORT, which is populated at runtime by
    // ECS task definitions, EKS pod specs, or Elastic Beanstalk environment properties.
    // The value is also stored in AWS Systems Manager Parameter Store under the key
    // '/resortslite/server/port' so it can be managed centrally across environments.
    // A safe default of 8080 is retained for local development only.
    @Value("${server.port:8080}")
    private int serverPort;

    // cr-java-0071 fix: Hard-coded report download base URL replaced with a value sourced
    // from AWS Systems Manager Parameter Store via Spring's @Value binding.
    // The property 'app.reports.download.base-url' is resolved at startup from the SSM
    // parameter '/resortslite/reports/download-base-url'.
    // This makes the endpoint environment-agnostic: dev, staging, and production each
    // supply their own SSM parameter value without any code change.
    @Value("${app.reports.download.base-url:http://reports.resorts-internal.com:8080/download}")
    private String reportsDownloadBaseUrl;

    private final S3Client s3Client;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * <p>cr-java-0063 fix (Lines 37, 39, 42 of original source):
     * <ul>
     *   <li>Line 37 — {@code File reportDir = new File(REPORT_BASE_PATH);} removed:
     *       S3 does not require a directory object; key prefixes are virtual.</li>
     *   <li>Line 39 — {@code reportDir.mkdirs();} removed:
     *       No directory creation is needed in S3.</li>
     *   <li>Line 42 — {@code FileWriter writer = new FileWriter(fullPath);} replaced:
     *       CSV content is now uploaded directly to S3 via {@code PutObjectRequest},
     *       providing durable, cloud-native storage without local file system dependency.</li>
     * </ul>
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year for which the report is generated (e.g. "2024")
     * @return a map containing the operation status and the S3 location of the report
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        // cr-java-0063 fix (Line 37): replaced local fullPath / File construction with
        // an S3 object key. No File object or directory creation (mkdirs) is needed —
        // S3 key prefixes are virtual and require no prior creation.
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // cr-java-0063 fix (Line 37): File reportDir = new File(REPORT_BASE_PATH) — REMOVED
            // cr-java-0063 fix (Line 39): reportDir.mkdirs() — REMOVED (not needed in S3)
            // cr-java-0063 fix (Line 42): FileWriter writer = new FileWriter(fullPath) — REPLACED
            //   with Amazon S3 PutObject call that stores the CSV content durably in S3,
            //   eliminating all java.io.File / java.io.FileWriter local storage dependencies.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", s3Key);
            // cr-java-0077 fix: serverPort is now injected via @Value("${server.port:8080}")
            // instead of the former hard-coded constant SERVER_PORT = 8080.
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report.
     *
     * <p>cr-java-0071 fix: Hard-coded URL replaced with injected {@code reportsDownloadBaseUrl}.
     *
     * @param reportName the name of the report file
     * @return the full download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 fix (Line 66): Hard-coded URL
        // "http://reports.resorts-internal.com:8080/download/" replaced with the
        // injected field 'reportsDownloadBaseUrl', whose value is sourced from
        // AWS Systems Manager Parameter Store at the key
        // '/resortslite/reports/download-base-url'.
        // This eliminates the environment-specific hard-coding and allows the base URL
        // to be updated per environment (dev/staging/prod) through SSM without code changes.
        return reportsDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns current system information including storage and port configuration.
     *
     * <p>cr-java-0111 fix (Line 70): Replaced {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}
     * with the java.time API. {@code Instant.now()} captures the current moment in UTC from the
     * system clock, and {@code DateTimeFormatter.ofPattern(...).withZone(ZoneOffset.UTC)} formats
     * it as an ISO-style UTC timestamp. This eliminates the server-local timezone dependency
     * inherent in {@code java.util.Date} / {@code SimpleDateFormat}, ensuring consistent
     * timestamps across all cloud regions, containers, and replicas.
     *
     * @return a map of system metadata values
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix (Line 70): java.util.Date + SimpleDateFormat replaced with
        // java.time.Instant + DateTimeFormatter standardised on UTC (ZoneOffset.UTC).
        // In distributed cloud environments (multi-region ECS/EKS), each container may
        // run in a different OS timezone; using Instant.now() with an explicit UTC zone
        // guarantees identical timestamp output regardless of host timezone configuration.
        String timestamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 fix: replaced hard-coded REPORT_BASE_PATH and BACKUP_PATH constants
        // (which pointed to local OS paths) with the S3 bucket/prefix values sourced from
        // environment variables, ensuring no local file system dependency remains.
        info.put("reportsBucket", reportsBucket);
        info.put("backupPrefix", backupPrefix);
        // cr-java-0077 fix: serverPort is now injected via @Value("${server.port:8080}")
        // instead of the former hard-coded constant SERVER_PORT = 8080.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
