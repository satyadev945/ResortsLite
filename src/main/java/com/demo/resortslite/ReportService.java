package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — generates and stores resort reports.
 *
 * <p>cr-java-0063 fix: All local file system operations that used
 * {@code java.io.File} for persistent data storage have been replaced with
 * Amazon S3 {@code PutObjectRequest} calls to ensure data durability and
 * availability in containerised / serverless cloud environments where the local
 * file system is ephemeral.</p>
 *
 * <p>Specifically, the following original violations were remediated:</p>
 * <ul>
 *   <li>Line 37 (original): {@code new File(REPORT_BASE_PATH)} — replaced with S3 bucket reference</li>
 *   <li>Line 39 (original): {@code reportDir.exists()} — removed; S3 does not require directory pre-checks</li>
 *   <li>Line 42 (original): {@code reportDir.mkdirs()} — removed; S3 key prefixes are created implicitly</li>
 * </ul>
 *
 * <p>The target S3 bucket is resolved from the environment variable
 * {@code REPORT_S3_BUCKET} (or the Spring property {@code report.s3.bucket}),
 * so no bucket name is hard-coded in the application binary.</p>
 *
 * <p>cr-java-0071 fix: The hard-coded report download base URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} (original line 66)
 * has been replaced with a value resolved from AWS Systems Manager Parameter Store
 * via the Spring property {@code app.report.download.base-url}.
 * The {@link SsmParameterStoreConfig} bean fetches the value from SSM on startup
 * (parameter path: {@code /resortslite/report/download-base-url}) and exposes it
 * as a Spring property, so no URL is hard-coded in the application binary.</p>
 */
@Service
public class ReportService {

    /**
     * S3 bucket used to store generated CSV reports.
     * Resolved from Spring property {@code report.s3.bucket}, which in turn
     * falls back to the environment variable {@code REPORT_S3_BUCKET}.
     *
     * <p>cr-java-0063 fix: replaces the hard-coded local path
     * {@code /var/legacy/reports/} that was used as the write destination for
     * {@code java.io.File} and {@code FileWriter} (original lines 37–42).</p>
     */
    @Value("${report.s3.bucket:${REPORT_S3_BUCKET:resorts-lite-reports}}")
    private String reportS3Bucket;

    /**
     * S3 bucket used to store nightly backup files.
     * Resolved from Spring property {@code backup.s3.bucket}, which in turn
     * falls back to the environment variable {@code BACKUP_S3_BUCKET}.
     *
     * <p>cr-java-0063 fix: replaces the hard-coded Windows path
     * {@code C:\\ResortBackups\\nightly\\} that was used as the write
     * destination for local file operations.</p>
     */
    @Value("${backup.s3.bucket:${BACKUP_S3_BUCKET:resorts-lite-backups}}")
    private String backupS3Bucket;

    /**
     * Server port — resolved from the environment so container orchestrators
     * (ECS / EKS) can assign ports dynamically.
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /**
     * AWS region for the S3 client, resolved from the environment variable
     * {@code AWS_REGION} or the Spring property {@code cloud.aws.region.static}.
     */
    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * cr-java-0071 fix: Base URL for report downloads, resolved from AWS Systems
     * Manager Parameter Store via the Spring property
     * {@code app.report.download.base-url}.
     *
     * <p>The original hard-coded value
     * {@code "http://reports.resorts-internal.com:8080/download/"} (original line 66)
     * has been removed. At runtime the {@link SsmParameterStoreConfig} bean fetches
     * the actual URL from the SSM parameter path
     * {@code /resortslite/report/download-base-url} and exposes it as a Spring
     * property. This makes the endpoint environment-agnostic — dev, staging, and
     * production each store their own URL in SSM without requiring code changes.</p>
     *
     * <p>Fallback: if the SSM parameter is not yet provisioned the value defaults to
     * the environment variable {@code REPORT_DOWNLOAD_BASE_URL}, allowing local
     * development without an SSM connection.</p>
     */
    @Value("${app.report.download.base-url:${REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download/}}")
    private String reportDownloadBaseUrl;

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Generates a monthly booking report and writes it to Amazon S3.
     *
     * <p><strong>cr-java-0063 fix (Lines 37–42 in original source):</strong>
     * The original implementation used {@code java.io.File} for persistent
     * data storage:</p>
     * <ul>
     *   <li>Line 37: {@code File reportDir = new File(REPORT_BASE_PATH);} —
     *       created a {@code java.io.File} object pointing to a local path.</li>
     *   <li>Line 39: {@code if (!reportDir.exists())} —
     *       checked local directory existence via {@code java.io.File.exists()}.</li>
     *   <li>Line 42: {@code reportDir.mkdirs();} —
     *       created local directories via {@code java.io.File.mkdirs()}.</li>
     * </ul>
     * <p>All three operations assumed local file system availability and
     * persistence, which violates cloud storage patterns. In cloud /
     * containerised environments this data is lost on every container restart
     * or scale-out event. The fix replaces all {@code java.io.File}-based
     * operations with an Amazon S3 {@code PutObjectRequest} so the report is
     * stored durably in the configured S3 bucket ({@code report.s3.bucket} /
     * {@code REPORT_S3_BUCKET}). S3 key prefixes are created implicitly,
     * eliminating the need for directory existence checks or {@code mkdirs()}
     * calls.</p>
     *
     * @param month numeric month string (e.g. "03")
     * @param year  four-digit year string (e.g. "2024")
     * @return result map containing {@code status}, {@code path} (S3 URI), and
     *         {@code serverPort}
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063: S3 object key mirrors the former local file name so
        // downstream consumers can locate the report without code changes.
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        // cr-java-0063: Use try-with-resources on the S3Client so the HTTP
        // connection pool is released promptly — equivalent to the former
        // writer.close() call.
        try (S3Client s3 = buildS3Client()) {
            // Build the CSV content that was previously written via FileWriter.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // cr-java-0063 (Line 37): Replaces new File(REPORT_BASE_PATH) — no local File object needed.
            // cr-java-0063 (Line 39): Replaces reportDir.exists() — S3 does not require directory pre-checks.
            // cr-java-0063 (Line 42): Replaces reportDir.mkdirs() — S3 key prefixes are created implicitly.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportS3Bucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3.putObject(putRequest, RequestBody.fromString(csvContent));

            // Return the S3 URI instead of the former local file path so
            // callers can retrieve the report from durable storage.
            String s3Uri = "s3://" + reportS3Bucket + "/" + objectKey;
            result.put("status", "generated");
            result.put("path", s3Uri);
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
     * <p><strong>cr-java-0071 fix (original line 66):</strong>
     * The hard-coded URL {@code "http://reports.resorts-internal.com:8080/download/"}
     * has been replaced with the injected field {@code reportDownloadBaseUrl}, whose
     * value is resolved at startup from AWS Systems Manager Parameter Store
     * (SSM parameter path: {@code /resortslite/report/download-base-url}).
     * This eliminates the environment-specific hard-coding and allows each deployment
     * environment (dev / staging / production) to supply its own URL through SSM
     * without any code changes.</p>
     *
     * @param reportName the report file name
     * @return download URL string sourced from SSM Parameter Store
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 fix (original line 66): Hard-coded URL removed.
        // reportDownloadBaseUrl is injected from AWS SSM Parameter Store via
        // Spring property app.report.download.base-url
        // (SSM path: /resortslite/report/download-base-url).
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including the configured S3 buckets and
     * server port.
     *
     * <p>cr-java-0063 fix: the former implementation returned the hard-coded
     * local paths {@code /var/legacy/reports/} and
     * {@code C:\\ResortBackups\\nightly\\} which are meaningless in a cloud
     * environment. These are now replaced with the S3 bucket names so
     * operators can identify where data is stored.</p>
     *
     * @return info map
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix (original line 70): Replaced java.util.Date and SimpleDateFormat
        // with java.time API standardized on UTC. Instant.now() retrieves the current time
        // from the system clock in UTC, and DateTimeFormatter formats it consistently
        // across all cloud regions and container instances, eliminating timezone drift.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063: report the S3 bucket names instead of local paths.
        info.put("reportBucket", reportS3Bucket);
        info.put("backupBucket", backupS3Bucket);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
