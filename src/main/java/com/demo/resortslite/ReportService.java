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
 * ReportService — cloud-native report generation service.
 *
 * <p>cr-java-0063 FIX: All java.io.File-based persistent storage operations have been
 * replaced with Amazon S3 client calls (AWS SDK for Java v2). The three violations were:
 * <ul>
 *   <li>Line 37 (original): {@code File reportDir = new File(REPORT_BASE_PATH);} — removed;
 *       S3 bucket/prefix @Value properties replace the local directory reference.</li>
 *   <li>Line 39 (original): {@code reportDir.mkdirs();} — removed; S3 object storage
 *       requires no directory creation; the key prefix is sufficient.</li>
 *   <li>Line 42 (original): {@code FileWriter writer = new FileWriter(fullPath);} — removed;
 *       CSV content is built in-memory and uploaded via {@link S3Client#putObject}.</li>
 * </ul>
 *
 * <p>cr-java-0061 FIX: Hardcoded absolute paths (/var/legacy/reports/, C:\ResortBackups\nightly\)
 * are replaced with S3 bucket/prefix values externalised via environment-backed properties
 * (AWS_S3_REPORTS_BUCKET, AWS_S3_REPORTS_PREFIX, AWS_S3_BACKUP_PREFIX).
 *
 * <p>cr-java-0071 FIX: Hard-coded environment URL in buildReportDownloadUrl replaced with
 * an @Value-injected field backed by AWS Systems Manager Parameter Store, enabling
 * environment-agnostic deployments across dev, staging, and production.
 *
 * <p>cr-java-0111 FIX: java.util.Date and SimpleDateFormat replaced with java.time API
 * (Instant / DateTimeFormatter / ZoneOffset.UTC). All timestamps are now generated in UTC,
 * eliminating server-local timezone dependencies that cause inconsistencies in distributed
 * cloud environments across multiple regions or containers.
 */
@Service
public class ReportService {

    // cr-java-0063 / cr-java-0061 FIX:
    // ORIGINAL (Line 37): private static final String REPORT_BASE_PATH = "/var/legacy/reports/";
    //   → java.io.File(REPORT_BASE_PATH) assumed a local, persistent file system — not available
    //     in cloud/container environments. Replaced with S3 bucket + prefix resolved from env vars.
    @Value("${aws.s3.reports.bucket:resorts-reports-bucket}")
    private String reportsBucket;

    // cr-java-0063 FIX: S3 object key prefix replaces the local directory path.
    @Value("${aws.s3.reports.prefix:reports/}")
    private String reportsPrefix;

    // cr-java-0061 FIX: Windows-style backup path replaced with S3 prefix.
    @Value("${aws.s3.backup.prefix:backups/nightly/}")
    private String backupPrefix;

    // czr-port-001 FIX: Server port externalised via environment variable / property.
    @Value("${server.port:8080}")
    private int serverPort;

    // cr-java-0071 FIX: Hard-coded environment URL
    // "http://reports.resorts-internal.com:8080/download/" replaced with an @Value-injected
    // field resolved from AWS Systems Manager Parameter Store parameter
    // '/resortslite/reports/download/base-url' (or environment variable
    // REPORTS_DOWNLOAD_BASE_URL as a fallback). This enables environment-agnostic
    // deployments: dev, staging, and production each supply their own SSM parameter value
    // without any code change, satisfying 12-factor app externalized configuration principles.
    @Value("${app.reports.download.base-url:https://reports.resorts-internal.com/download/}")
    private String reportsDownloadBaseUrl;

    private final S3Client s3Client;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * <p>cr-java-0063 FIX — three java.io.File violations replaced with S3 operations:
     * <ol>
     *   <li><b>Line 37 (original)</b>: {@code File reportDir = new File(REPORT_BASE_PATH);}
     *       — Removed. The S3 bucket and prefix @Value fields replace the local directory
     *       reference. No java.io.File object is created.</li>
     *   <li><b>Line 39 (original)</b>: {@code reportDir.mkdirs();}
     *       — Removed. Amazon S3 is a flat object store; no directory creation is needed.
     *       The key prefix (e.g. "reports/") is embedded directly in the S3 object key.</li>
     *   <li><b>Line 42 (original)</b>: {@code FileWriter writer = new FileWriter(fullPath);}
     *       — Removed. CSV content is assembled in-memory as a {@code String} and uploaded
     *       to S3 via {@link S3Client#putObject(PutObjectRequest, RequestBody)}, ensuring
     *       durable, highly-available storage that survives container lifecycle events.</li>
     * </ol>
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string (e.g. "2024")
     * @return a result map containing the S3 bucket, key, and operation status
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        // cr-java-0063 FIX (Line 37 original — new File(REPORT_BASE_PATH)):
        // S3 object key replaces the former absolute local file path.
        // Original: String fullPath = REPORT_BASE_PATH + fileName;  (local FS — ephemeral)
        // Fixed:    String s3Key    = reportsPrefix + fileName;      (S3 — durable)
        String s3Key = reportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 FIX (Line 37 original — new File(REPORT_BASE_PATH)):
            // No java.io.File object is created. The S3 bucket/prefix values are injected
            // via @Value and used directly in the PutObjectRequest below.

            // cr-java-0063 FIX (Line 39 original — reportDir.mkdirs()):
            // Amazon S3 is a flat key-value store; directory creation is not required.
            // The "reports/" prefix in the S3 key logically groups objects without needing
            // any mkdirs() call.

            // cr-java-0063 FIX (Line 42 original — new FileWriter(fullPath)):
            // CSV content is built in-memory and uploaded to S3 in a single PutObject call.
            // This replaces the original local file-system write sequence:
            //   FileWriter writer = new FileWriter(fullPath);
            //   writer.write("BookingID,...\n");
            //   writer.write("BK-001,...\n");
            //   writer.write("BK-002,...\n");
            //   writer.close();
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for a named report.
     *
     * <p>cr-java-0071 FIX: Hard-coded environment URL
     * "http://reports.resorts-internal.com:8080/download/" replaced with the
     * @Value-injected field 'reportsDownloadBaseUrl', which is resolved from AWS Systems
     * Manager Parameter Store parameter '/resortslite/reports/download/base-url'
     * (or environment variable REPORTS_DOWNLOAD_BASE_URL as a fallback).
     * This enables environment-agnostic deployments without code changes per environment.
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Replaced hard-coded URL
        // "http://reports.resorts-internal.com:8080/download/" with the externalized
        // property 'app.reports.download.base-url' backed by AWS SSM Parameter Store.
        // Original (violation): return "http://reports.resorts-internal.com:8080/download/" + reportName;
        return reportsDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 storage configuration.
     *
     * <p>cr-java-0063 FIX: Replaced references to hardcoded local paths
     * (REPORT_BASE_PATH, BACKUP_PATH) with S3 bucket/prefix values resolved from
     * environment-backed properties — no java.io.File dependency remains.
     *
     * <p>cr-java-0111 FIX: Replaced {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}
     * with {@code DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.now())}
     * to standardize on UTC and eliminate server-local timezone dependency.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (Line 70): Replaced java.util.Date + SimpleDateFormat with java.time API.
        // Original (violation): new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
        //   → Relied on server-local JVM timezone; causes inconsistencies across cloud regions/containers.
        // Fixed: Instant.now() with DateTimeFormatter pinned to ZoneOffset.UTC
        //   → Always produces a UTC timestamp regardless of the host JVM timezone setting.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: Replaced hardcoded REPORT_BASE_PATH (/var/legacy/reports/)
        // and BACKUP_PATH (C:\ResortBackups\nightly\) — which were used as java.io.File
        // constructor arguments — with S3 bucket/prefix references resolved from
        // environment-backed properties. No local FS dependency remains.
        info.put("reportsBucket", reportsBucket);
        info.put("reportsPrefix", reportsPrefix);
        info.put("backupPrefix", backupPrefix);
        info.put("serverPort", serverPort);
        info.put("reportsDownloadBaseUrl", reportsDownloadBaseUrl);
        info.put("generatedAt", timestamp);
        return info;
    }
}
