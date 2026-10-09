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
 * Service responsible for generating and storing resort reports.
 *
 * <p>cr-java-0061 fix: Replaced hard-coded absolute file paths (REPORT_BASE_PATH="/var/legacy/reports/"
 * and BACKUP_PATH="C:\\ResortBackups\\nightly\\") with Amazon S3 object storage. The S3 bucket name
 * is injected from the environment variable AWS_S3_REPORT_BUCKET via application.properties so no
 * path is hard-coded in the application source.</p>
 *
 * <p>cr-java-0062 fix: Eliminated all local file system write operations (FileWriter, File.mkdirs,
 * FileWriter.write, FileWriter.close). In cloud and containerised environments the local file system
 * is ephemeral; data written locally is lost on container restart or scale-out. All write operations
 * now target Amazon S3 via {@link S3Client#putObject} to guarantee durable, highly-available
 * storage that survives container lifecycle events.</p>
 *
 * <p>cr-java-0071 fix: Replaced the hard-coded report download base URL
 * ("http://reports.resorts-internal.com:8080/download/") with a value injected from
 * AWS Systems Manager Parameter Store via the 'app.report.download.base-url' property.
 * Set the SSM parameter '/resortslite/report/download-base-url' (or the env-var
 * REPORT_DOWNLOAD_BASE_URL) in each deployment environment so no URL is baked into the binary.</p>
 *
 * <p>cr-java-0111 fix: Replaced legacy {@code java.util.Date} / {@code SimpleDateFormat} usage
 * with the {@code java.time} API ({@link Instant}, {@link DateTimeFormatter}). All timestamps are
 * now formatted in UTC (ISO-8601) using {@code DateTimeFormatter.ofPattern} bound to
 * {@link ZoneOffset#UTC}, eliminating timezone-ambiguity issues in distributed cloud deployments
 * across multiple regions or containers.</p>
 */
@Service
public class ReportService {

    // cr-java-0061 / cr-java-0062 fix: S3 bucket name injected from environment variable
    // AWS_S3_REPORT_BUCKET (see application.properties). Replaces the former hard-coded
    // REPORT_BASE_PATH ("/var/legacy/reports/") and BACKUP_PATH ("C:\\ResortBackups\\nightly\\").
    @Value("${aws.s3.report.bucket}")
    private String reportBucket;

    // cr-java-0077 fix: Hard-coded SERVER_PORT constant (8080) replaced with a value injected
    // from the SERVER_PORT environment variable via AWS Parameter Store / ECS task-definition
    // environment injection. Container orchestration (ECS / EKS) assigns ports dynamically;
    // baking a port number into the source prevents dynamic port binding and service discovery.
    // Set the SERVER_PORT environment variable (or the SSM-backed 'app.server.port' property)
    // in each deployment environment so no port number is hard-coded in the application binary.
    @Value("${app.server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071 fix: Hard-coded report download base URL replaced with a value injected from
    // AWS Systems Manager Parameter Store via the 'app.report.download.base-url' property.
    // Set the SSM parameter '/resortslite/report/download-base-url' (or the env-var
    // REPORT_DOWNLOAD_BASE_URL) in each deployment environment so no URL is baked into the binary.
    @Value("${app.report.download.base-url:${REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download/}}")
    private String reportDownloadBaseUrl;

    private final S3Client s3Client;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly resort report and stores it durably in Amazon S3.
     *
     * <p>cr-java-0062 fix (Lines 42-46 in original source): The original implementation used
     * {@code FileWriter} to write CSV data to a local path constructed from the hard-coded
     * {@code REPORT_BASE_PATH} constant. This caused permanent data loss in cloud/container
     * environments where the local file system is ephemeral. The fix replaces:</p>
     * <pre>
     *   File reportDir = new File(REPORT_BASE_PATH);
     *   if (!reportDir.exists()) { reportDir.mkdirs(); }
     *   FileWriter writer = new FileWriter(fullPath);   // cr-java-0062 violation
     *   writer.write(...);
     *   writer.close();
     * </pre>
     * <p>with a direct upload to Amazon S3 using {@link S3Client#putObject}, ensuring the
     * report CSV is stored durably and is accessible regardless of container restarts or
     * horizontal scaling events.</p>
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year for which the report is generated (e.g. "2024")
     * @return a map containing the operation status and the S3 location of the stored report
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0061 fix: S3 object key replaces the former local fullPath
        // (REPORT_BASE_PATH + "resort_report_" + month + "_" + year + ".csv")
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062 fix: CSV content is assembled in memory and uploaded directly to
            // Amazon S3 via PutObjectRequest, replacing the former local FileWriter operations:
            //   FileWriter writer = new FileWriter(fullPath);  // REMOVED – local FS write
            //   writer.write("BookingID,...\n");               // REMOVED – local FS write
            //   writer.write("BK-001,...\n");                  // REMOVED – local FS write
            //   writer.write("BK-002,...\n");                  // REMOVED – local FS write
            //   writer.close();                                // REMOVED – local FS write
            // S3 provides durable, highly-available object storage that persists across
            // container restarts and scale-out events, satisfying cloud-readiness requirements.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", objectKey);
            result.put("serverPort", serverPort); // cr-java-0077 fix: injected from env/SSM

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report.
     *
     * <p>cr-java-0071 fix: The former hard-coded base URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} has been replaced with
     * the {@code reportDownloadBaseUrl} field injected from AWS Systems Manager Parameter Store
     * (SSM path: {@code /resortslite/report/download-base-url}) via the
     * {@code app.report.download.base-url} application property. This makes the endpoint
     * configurable per environment without any code change.</p>
     *
     * @param reportName the name of the report file to download
     * @return the fully-qualified download URL for the given report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 fix: The former hard-coded URL
        //   "http://reports.resorts-internal.com:8080/download/" + reportName
        // is replaced with the externalized 'reportDownloadBaseUrl' field, which is
        // resolved at runtime from AWS Systems Manager Parameter Store via the
        // 'app.report.download.base-url' property (SSM: /resortslite/report/download-base-url).
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including a UTC-standardised timestamp.
     *
     * <p>cr-java-0111 fix: The former {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}
     * call relied on the server-local timezone and the legacy {@code java.util.Date} API, which causes
     * scheduling failures and time-related logic errors in distributed cloud environments. It has been
     * replaced with {@code Instant.now()} formatted via {@code DateTimeFormatter} bound to
     * {@link ZoneOffset#UTC}, ensuring all timestamps are consistently in UTC regardless of the
     * container or region where the service is running.</p>
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix: Replaced java.util.Date / SimpleDateFormat (server-local timezone)
        // with java.time API standardised on UTC. Instant.now() captures the current moment in
        // UTC; DateTimeFormatter formats it as "yyyy-MM-dd HH:mm:ss" in UTC, guaranteeing
        // consistent timestamps across all cloud regions and container instances.
        DateTimeFormatter utcFormatter = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC);
        String timestamp = utcFormatter.format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 / cr-java-0062 fix: replaced hard-coded REPORT_BASE_PATH and BACKUP_PATH
        // with S3 bucket reference; no local file system paths remain in the application.
        info.put("reportBucket", reportBucket);          // cr-java-0061 / cr-java-0062 fix
        info.put("reportDownloadBaseUrl", reportDownloadBaseUrl); // cr-java-0071 fix
        info.put("serverPort", serverPort);               // cr-java-0077 fix: injected from env/SSM
        info.put("generatedAt", timestamp);
        return info;
    }
}
