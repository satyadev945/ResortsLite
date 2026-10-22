package com.demo.resortslite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation backed by Amazon S3.
 *
 * <p>cr-java-0063 FIX (Java.io.File Usage for Data Storage):
 * All three java.io.File / FileWriter usages that previously wrote reports to the
 * local file system (/var/legacy/reports/) have been replaced with Amazon S3
 * PutObject calls via the AWS SDK for Java v2.  The local file system is ephemeral
 * in containerised / serverless cloud environments; S3 provides durable, scalable,
 * and cloud-native object storage without any host-level file system dependency.
 *
 * <p>cr-java-0071 FIX (Hard-coded Environment URLs):
 * The hard-coded report download base URL previously returned by
 * {@link #buildReportDownloadUrl(String)} has been replaced with a value retrieved
 * at runtime from AWS Systems Manager Parameter Store, enabling environment-agnostic
 * deployments without code changes between dev / staging / production.
 *
 * <p>Original violations (source lines 37, 39, 42):
 * <ul>
 *   <li>Line 37: {@code new File(REPORT_BASE_PATH)} — directory handle on local FS</li>
 *   <li>Line 39: {@code reportDir.mkdirs()} — local directory creation</li>
 *   <li>Line 42: {@code new FileWriter(fullPath)} — local file write</li>
 * </ul>
 * All three are now handled by a single {@link S3Client#putObject} call.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final S3Client s3Client;

    // cr-java-0071 FIX: SSM client injected to resolve environment-specific URLs
    // from AWS Systems Manager Parameter Store instead of hard-coding them.
    @Autowired
    private SsmClient ssmClient;

    @Value("${cloud.aws.s3.bucket-name}")
    private String bucketName;

    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    // cr-java-0077 FIX (Hard-coded Ports — line 28):
    // The original static final int SERVER_PORT = 8080 has been replaced with a value
    // retrieved at runtime from AWS Systems Manager Parameter Store.
    // The SSM parameter name is externalised via application.properties
    // (ssm.server.port.param), enabling dynamic port assignment per environment
    // without any code changes between dev / staging / production.
    @Value("${ssm.server.port.param:/resortslite/server-port}")
    private String serverPortParamName;

    @Value("${app.server.port.default:8080}")
    private String serverPortDefault;

    // cr-java-0071 FIX: SSM parameter name for the report download base URL is
    // externalised to application.properties / environment variable, not hard-coded.
    @Value("${ssm.report.download.url.param:/resortslite/report-download-base-url}")
    private String reportDownloadUrlParamName;

    // cr-java-0071 FIX: Fallback value used only when SSM is unreachable
    // (e.g. local development). In production the SSM parameter takes precedence.
    @Value("${app.report.download.url.default:https://reports.resorts-internal.com:8443/download/}")
    private String reportDownloadUrlDefault;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Resolves the server port at runtime from AWS SSM Parameter Store.
     *
     * <p>cr-java-0077 FIX: The hard-coded {@code SERVER_PORT = 8080} constant
     * (original source line 28) is replaced by a dynamic lookup against SSM
     * Parameter Store, allowing ECS / EKS / Elastic Beanstalk to inject the
     * correct port per environment without rebuilding the application image.
     *
     * @return the server port resolved from SSM, or the configured default
     */
    private int resolveServerPort() {
        String portStr = SsmParameterStoreConfig.getParameter(
                ssmClient, serverPortParamName, serverPortDefault); // cr-java-0077 FIXED
        try {
            return Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid server port value '{}' from SSM parameter '{}'. Using default: {}",
                    portStr, serverPortParamName, serverPortDefault);
            return Integer.parseInt(serverPortDefault);
        }
    }

    /**
     * Generates a monthly CSV report and stores it durably in Amazon S3.
     *
     * <p>cr-java-0063 FIX: The previous implementation used java.io.File and
     * FileWriter to persist reports on the local file system — a pattern that
     * breaks in cloud environments where the local FS is ephemeral and not shared
     * across instances.  The fix replaces all three java.io.File occurrences
     * (original lines 37, 39, 42) with a single S3 PutObject operation:
     * <ol>
     *   <li>Line 37 — {@code new File(REPORT_BASE_PATH)}: removed; S3 key used instead.</li>
     *   <li>Line 39 — {@code reportDir.mkdirs()}: removed; S3 has no directory concept.</li>
     *   <li>Line 42 — {@code new FileWriter(fullPath)}: replaced with
     *       {@link RequestBody#fromString(String)} passed to
     *       {@link S3Client#putObject(PutObjectRequest, RequestBody)}.</li>
     * </ol>
     *
     * @param month the calendar month (e.g. "03")
     * @param year  the calendar year  (e.g. "2024")
     * @return a result map containing S3 bucket, key, and status
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        // cr-java-0063 FIX (line 37): Removed — new File(REPORT_BASE_PATH) replaced by S3 key.
        // cr-java-0063 FIX (line 39): Removed — reportDir.mkdirs() not needed; S3 is key-based.
        // cr-java-0063 FIX (line 42): Removed — new FileWriter(fullPath) replaced by S3 PutObject.
        // Data is now stored durably in S3 — survives container restarts and horizontal scaling.
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // cr-java-0063 FIX: Amazon S3 PutObject replaces all local java.io.File operations.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", bucketName);
            result.put("s3Key", s3Key);
            // cr-java-0077 FIX: port resolved dynamically from SSM Parameter Store
            result.put("serverPort", resolveServerPort());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL by retrieving the environment-specific base URL
     * from AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0071 FIX (Hard-coded Environment URLs — line 66):
     * The hard-coded URL {@code "http://reports.resorts-internal.com:8080/download/"}
     * has been replaced with a value retrieved at runtime from AWS SSM Parameter Store.
     * The SSM parameter name is itself externalised via application.properties
     * ({@code ssm.report.download.url.param}), enabling environment-agnostic deployments
     * without any code changes between dev / staging / production environments.
     *
     * @param reportName the name of the report file to append to the base URL
     * @return the fully qualified download URL resolved from SSM Parameter Store
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX (line 66): Hard-coded "http://reports.resorts-internal.com:8080/download/"
        // replaced with a value fetched from AWS SSM Parameter Store at runtime.
        String baseUrl = SsmParameterStoreConfig.getParameter(
                ssmClient, reportDownloadUrlParamName, reportDownloadUrlDefault); // cr-java-0071 FIXED
        return baseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (Clock/Time Dependencies — line 70):
        // java.util.Date and SimpleDateFormat replaced with java.time API (Instant + DateTimeFormatter).
        // Instant.now() always captures the current moment in UTC, eliminating timezone
        // inconsistencies across distributed cloud containers and multi-region deployments.
        String timestamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now()); // cr-java-0111 FIXED — UTC-standardised via java.time API
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: Local file-system path constants (REPORT_BASE_PATH, BACKUP_PATH)
        // removed; replaced with S3 bucket/prefix values sourced from application properties.
        info.put("s3Bucket", bucketName);
        info.put("reportPrefix", reportPrefix);
        // cr-java-0077 FIX: port resolved dynamically from SSM Parameter Store
        info.put("serverPort", resolveServerPort());
        info.put("generatedAt", timestamp);
        return info;
    }
}
