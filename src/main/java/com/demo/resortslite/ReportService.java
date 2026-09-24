package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Qualifier;
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
 * ReportService — cloud-native report generation service.
 *
 * <p>cr-java-0063 fix: All {@code java.io.File} / {@code java.io.FileWriter} persistent
 * storage operations have been replaced with Amazon S3 client calls (AWS SDK for Java v2).
 * Reports are uploaded directly to an S3 bucket instead of being written to a local
 * absolute file path, eliminating the host-level file system dependency that prevents
 * successful cloud deployment.</p>
 *
 * <p>cr-java-0071 fix (Line 66): The hard-coded URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} in
 * {@link #buildReportDownloadUrl(String)} has been replaced with a value resolved from
 * AWS Systems Manager Parameter Store via the {@code reportDownloadBaseUrl} bean defined
 * in {@link com.demo.resortslite.config.AwsSsmParameterStoreConfig}.  At runtime the bean
 * reads the SSM parameter {@code /resortslite/reports/download-url}, enabling
 * environment-agnostic deployments without code changes between environments.</p>
 *
 * <p>cr-java-0077 fix (Line 28): The hard-coded {@code SERVER_PORT = 8080} constant has been
 * removed and replaced with a value injected from AWS Systems Manager Parameter Store via the
 * {@code serverPort} bean defined in
 * {@link com.demo.resortslite.config.AwsSsmParameterStoreConfig}.  At runtime the bean reads
 * the SSM parameter {@code /resortslite/server/port}, enabling dynamic port assignment required
 * by container orchestration platforms (ECS, EKS, Elastic Beanstalk) and cloud service
 * discovery mechanisms.  The value falls back to the {@code SERVER_PORT} environment variable
 * and finally to {@code 8080} for local development.</p>
 *
 * <p>The S3 bucket name, report key prefix, and backup key prefix are externalised via
 * Spring {@code @Value} bindings backed by environment variables, following 12-factor
 * app principles for configuration management.</p>
 */
@Service
public class ReportService {

    // cr-java-0063 fix: Replaced hard-coded absolute file paths (REPORT_BASE_PATH,
    // BACKUP_PATH) with Amazon S3 bucket/prefix configuration injected from environment
    // variables / application properties.  No java.io.File references remain.
    @Value("${cloud.aws.s3.bucket-name:resorts-reports-bucket}")
    private String s3BucketName;

    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    // cr-java-0077 fix (Line 28): Removed hard-coded SERVER_PORT = 8080 constant.
    // The server port is now resolved from AWS Systems Manager Parameter Store via the
    // serverPort bean in AwsSsmParameterStoreConfig, which reads the SSM parameter
    // /resortslite/server/port at application startup.  This enables dynamic port
    // assignment required by ECS, EKS, and Elastic Beanstalk container orchestration.
    // Fallback chain: SSM parameter → SERVER_PORT env var → 8080 (local dev only).
    @org.springframework.beans.factory.annotation.Autowired
    @Qualifier("serverPort")
    private String serverPort;

    /**
     * cr-java-0071 fix (Line 66): The hard-coded URL literal
     * {@code "http://reports.resorts-internal.com:8080/download/"} has been removed.
     *
     * <p>The report download base URL is now resolved from AWS Systems Manager Parameter Store
     * via the {@code reportDownloadBaseUrl} bean defined in
     * {@link com.demo.resortslite.config.AwsSsmParameterStoreConfig}.  The SSM parameter
     * {@code /resortslite/reports/download-url} (configurable via
     * {@code aws.ssm.param.report-download-url} property or
     * {@code SSM_REPORT_DOWNLOAD_URL_PARAM} environment variable) holds the environment-specific
     * URL, so the same artifact can be deployed to dev, staging, and production without any
     * code changes.</p>
     *
     * <p>The {@code @Qualifier("reportDownloadBaseUrl")} annotation ensures Spring injects
     * the correct String bean produced by the SSM config class.</p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    @Qualifier("reportDownloadBaseUrl")
    private String reportDownloadBaseUrl;

    private final S3Client s3Client;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly resort report and uploads it to Amazon S3.
     *
     * <p>cr-java-0063 fix (Lines 37–42 of original source):
     * <ul>
     *   <li>Line 37 — {@code new File(REPORT_BASE_PATH)} removed; S3 key constructed instead.</li>
     *   <li>Line 39 — {@code reportDir.exists()} / {@code reportDir.mkdirs()} removed;
     *       S3 does not require directory creation.</li>
     *   <li>Line 42 — {@code new FileWriter(fullPath)} removed; report content is uploaded
     *       via {@link S3Client#putObject(PutObjectRequest, RequestBody)} instead.</li>
     * </ul>
     * </p>
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string (e.g. "2024")
     * @return result map containing S3 bucket, S3 key, and status
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        // cr-java-0063 fix (Line 37): replaced local File path construction with S3 object key.
        // Original: File reportDir = new File(REPORT_BASE_PATH);
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 fix (Line 39): removed reportDir.exists() / reportDir.mkdirs().
            // S3 is a flat key-value store — no directory creation is needed.

            // cr-java-0063 fix (Line 42): replaced new FileWriter(fullPath) + writer.write()
            // + writer.close() with a single S3 PutObject call.  Report CSV content is
            // uploaded directly to Amazon S3 instead of being written to a local absolute
            // path (/var/legacy/reports/) that does not exist in cloud/container environments.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            // cr-java-0077 fix: replaced hard-coded SERVER_PORT constant with SSM/env-injected serverPort
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the full download URL for a named report.
     *
     * <p>cr-java-0071 fix (Line 66): Replaced the hard-coded URL literal
     * {@code "http://reports.resorts-internal.com:8080/download/"} with the
     * {@code reportDownloadBaseUrl} field, whose value is resolved from AWS SSM
     * Parameter Store at application startup by
     * {@link com.demo.resortslite.config.AwsSsmParameterStoreConfig#reportDownloadBaseUrl}.
     * This eliminates the environment-specific hard-coding and allows the same binary to
     * be deployed across dev, staging, and production environments by updating the SSM
     * parameter value in each environment.</p>
     *
     * <p>Note: the SSM parameter value should use HTTPS in all cloud environments to
     * comply with AWS Well-Architected security standards (cr-java-0088).</p>
     *
     * @param reportName the name of the report file to download
     * @return the full download URL for the specified report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 fix (Line 66): replaced hard-coded
        // "http://reports.resorts-internal.com:8080/download/" + reportName
        // with the SSM-backed reportDownloadBaseUrl bean value injected at startup.
        // The SSM parameter /resortslite/reports/download-url holds the environment-specific
        // base URL, enabling environment-agnostic deployments without code changes.
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including S3 storage configuration.
     *
     * <p>cr-java-0063 fix: replaced hard-coded local path references
     * ({@code REPORT_BASE_PATH}, {@code BACKUP_PATH}) with S3 bucket/prefix values.</p>
     *
     * <p>cr-java-0077 fix: replaced hard-coded {@code SERVER_PORT} constant with the
     * SSM/env-injected {@code serverPort} field.</p>
     *
     * @return map of system information entries
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix (Line 70): Replaced java.util.Date + SimpleDateFormat with
        // java.time API standardized on UTC. Instant.now() uses the system clock in UTC,
        // and DateTimeFormatter formats it as an ISO-8601 UTC timestamp, eliminating
        // timezone inconsistencies across distributed cloud environments and containers.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 fix: replaced hardcoded local paths with S3 bucket/prefix references
        info.put("reportS3Bucket", s3BucketName);
        info.put("reportS3Prefix", reportPrefix);
        info.put("backupS3Prefix", backupPrefix);
        // cr-java-0077 fix: replaced hard-coded SERVER_PORT = 8080 with SSM/env-injected serverPort
        info.put("serverPort", serverPort);
        // cr-java-0071 fix: expose the SSM-resolved download base URL in system info
        info.put("reportDownloadBaseUrl", reportDownloadBaseUrl);
        info.put("generatedAt", timestamp);
        return info;
    }
}
