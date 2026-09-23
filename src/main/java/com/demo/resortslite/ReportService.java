package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService generates monthly resort reports and stores them in Amazon S3.
 *
 * <p>All local file-system write operations have been replaced with Amazon S3
 * object storage to ensure data durability and cloud portability:</p>
 * <ul>
 *   <li>cr-java-0061: Hardcoded local file-system paths replaced with S3 bucket/prefix
 *       injected from environment variables.</li>
 *   <li>cr-java-0062: {@code FileWriter} write to local path (original line 42) replaced
 *       with {@link S3Client#putObject} so data is never written to the ephemeral
 *       container file system.</li>
 *   <li>cr-java-0071: Hard-coded report-download URL replaced with a value retrieved
 *       from AWS Systems Manager Parameter Store via {@link AwsSsmParameterStoreUtil}.</li>
 *   <li>cr-java-0077: Hard-coded port constant {@code SERVER_PORT = 8080} replaced with
 *       a value injected from the environment variable {@code SERVER_PORT} (or the Spring
 *       property {@code server.port}), enabling dynamic port assignment required by
 *       container orchestration platforms (ECS / EKS / Elastic Beanstalk).</li>
 * </ul>
 */
@Service
public class ReportService {

    /**
     * S3 bucket name injected from environment variable {@code AWS_S3_REPORTS_BUCKET}
     * or application property {@code app.s3.reports.bucket}.
     *
     * <p>cr-java-0061 / cr-java-0062: replaces hardcoded {@code REPORT_BASE_PATH}
     * ("/var/legacy/reports/") and {@code BACKUP_PATH} ("C:\\ResortBackups\\nightly\\")
     * that caused local file-system write failures in containerised environments.</p>
     */
    @Value("${app.s3.reports.bucket}")
    private String reportsBucket;

    /**
     * S3 key prefix injected from environment variable {@code AWS_S3_REPORTS_PREFIX}
     * or application property {@code app.s3.reports.prefix} (default: {@code reports/}).
     *
     * <p>cr-java-0061 / cr-java-0062: replaces the hardcoded OS-specific path prefix
     * used when constructing the local {@code fullPath} for {@code FileWriter}.</p>
     */
    @Value("${app.s3.reports.prefix:reports/}")
    private String reportsPrefix;

    /**
     * cr-java-0077 FIX: The hard-coded {@code private static final int SERVER_PORT = 8080}
     * constant (original line 28) has been removed and replaced with this Spring
     * {@code @Value}-injected field.
     *
     * <p>At runtime the port is resolved in the following priority order:</p>
     * <ol>
     *   <li>Spring property {@code server.port} (set via {@code application.properties}
     *       or an environment variable override {@code SERVER_PORT}).</li>
     *   <li>Environment variable {@code SERVER_PORT} injected directly by the ECS task
     *       definition, EKS pod spec, or Elastic Beanstalk environment configuration.</li>
     *   <li>Default value {@code 8080} used only for local development when neither of
     *       the above is present.</li>
     * </ol>
     *
     * <p>This eliminates the hard-coded literal and enables dynamic port assignment
     * required by container orchestration platforms and AWS service discovery.</p>
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    private final S3Client s3Client;

    /**
     * cr-java-0071: Provides environment-specific URLs fetched from
     * AWS Systems Manager Parameter Store at application startup.
     * Replaces the hard-coded report-download URL literal in this class.
     */
    @Autowired
    private AwsSsmParameterStoreUtil ssmParameterStoreUtil;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly CSV report and uploads it directly to Amazon S3.
     *
     * <p><strong>cr-java-0062 fix (original line 42):</strong> The original code used
     * {@code new FileWriter(fullPath)} to write the report to the local file system
     * ({@code /var/legacy/reports/}). In cloud/container environments the local file
     * system is ephemeral — data written there is lost on container restart or scale-out.
     * The fix builds the CSV content in memory and uploads it to S3 via
     * {@link S3Client#putObject}, ensuring durable, cloud-native storage.</p>
     *
     * @param month the month for the report (e.g. "03")
     * @param year  the year for the report (e.g. "2024")
     * @return a map containing the operation status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        // cr-java-0062: S3 object key replaces the former local fullPath
        // (was: REPORT_BASE_PATH + fileName → "/var/legacy/reports/resort_report_MM_YYYY.csv")
        String s3Key = reportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062: CSV content is built in memory — no File/FileWriter/mkdirs needed.
            // Original violation: new FileWriter(fullPath) at line 42 wrote to the local
            // ephemeral container file system. Replaced with S3 PutObject for durable storage.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // cr-java-0062: Upload report directly to Amazon S3 — replaces FileWriter write
            // operations (original lines 42-46: new FileWriter, writer.write x2, writer.close).
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", s3Key);
            // cr-java-0077: serverPort is now injected via @Value / environment variable
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    /**
     * Builds a fully-qualified report download URL for the given report name.
     *
     * <p><strong>cr-java-0071 fix (original line 66):</strong> The hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} has been replaced
     * with a value retrieved from AWS Systems Manager Parameter Store via
     * {@link AwsSsmParameterStoreUtil#getReportDownloadBaseUrl()}. The SSM parameter
     * path is configured via the property
     * {@code aws.ssm.report.download.url.param}
     * (default: {@code /resortslite/reports/download-url}), enabling
     * environment-agnostic deployments without code changes.</p>
     *
     * @param reportName the name of the report file to download
     * @return the fully-qualified download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // cr-java-0071 FIX: Hard-coded URL "http://reports.resorts-internal.com:8080/download/"
        // replaced with a value retrieved from AWS Systems Manager Parameter Store.
        // The base URL is fetched at startup by AwsSsmParameterStoreUtil and cached in memory.
        String reportDownloadBaseUrl = ssmParameterStoreUtil.getReportDownloadBaseUrl(); // cr-java-0071
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including S3 storage configuration.
     *
     * <p>cr-java-0062: report location is now an S3 bucket/prefix rather than a
     * local file-system path, so no local path metadata is exposed here.</p>
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 FIX: Replaced java.util.Date + SimpleDateFormat (server-local timezone)
        // with java.time.ZonedDateTime using ZoneOffset.UTC and DateTimeFormatter.
        // Standardized on UTC to eliminate timezone inconsistencies across distributed
        // cloud environments (multiple regions / containers).
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // cr-java-0062: report location is now an S3 bucket/prefix, not a local path
        info.put("reportsBucket", reportsBucket);   // cr-java-0062
        info.put("reportsPrefix", reportsPrefix);   // cr-java-0062
        // cr-java-0077: serverPort is now injected via @Value / environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
