package com.demo.resortslite;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — generates and stores resort reports using Google Cloud Storage.
 *
 * <p>All java.io.File / FileWriter operations have been replaced with GCS uploads to ensure
 * data durability across container restarts and horizontal scaling events (cr-java-0063).
 * Bucket name and object prefixes are externalised via environment variables so that
 * no path is hard-coded in the application binary.</p>
 */
@Service
public class ReportService {

    /**
     * GCS bucket name — injected from the {@code GCS_BUCKET_NAME} environment variable.
     * Falls back to {@code resorts-reports-bucket} when the variable is absent (local dev).
     *
     * <p>cr-java-0063 FIX: replaces the hard-coded local path {@code /var/legacy/reports/}
     * that was previously stored in the {@code REPORT_BASE_PATH} constant.</p>
     */
    @Value("${gcs.bucket.name:resorts-reports-bucket}")
    private String gcsBucketName;

    /**
     * Object-key prefix for monthly report files inside the GCS bucket.
     *
     * <p>cr-java-0063 FIX: replaces the hard-coded local path {@code /var/legacy/reports/}
     * (line 37 of original source — {@code new File(REPORT_BASE_PATH)}).</p>
     */
    @Value("${gcs.report.prefix:reports/}")
    private String gcsReportPrefix;

    /**
     * Object-key prefix for nightly backup files inside the GCS bucket.
     *
     * <p>cr-java-0063 FIX: replaces the hard-coded Windows path
     * {@code C:\ResortBackups\nightly\} that was stored in {@code BACKUP_PATH}.</p>
     */
    @Value("${gcs.backup.prefix:backups/nightly/}")
    private String gcsBackupPrefix;

    /**
     * Server port — injected from the {@code SERVER_PORT} environment variable.
     * Falls back to {@code 8080} when the variable is absent (local dev).
     *
     * <p>cr-java-0077 FIX (line 28): replaces the hard-coded {@code private static final int SERVER_PORT = 8080}
     * constant. The actual HTTP listener port is controlled by {@code server.port} in
     * application.properties (itself resolved from the {@code PORT} env-var for GCP Cloud Run
     * compatibility), while this field is used only for informational/URL-building purposes.</p>
     */
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Generates a monthly booking report and uploads it to Google Cloud Storage.
     *
     * <p>cr-java-0063 FIX (lines 37–42 of original source):
     * <ul>
     *   <li>Line 37 — {@code new File(REPORT_BASE_PATH)} replaced by GCS BlobId construction.</li>
     *   <li>Line 39 — {@code reportDir.mkdirs()} removed; GCS creates object paths implicitly.</li>
     *   <li>Line 42 — {@code new FileWriter(fullPath)} replaced by {@code storage.create()} call.</li>
     * </ul>
     * Report content is built entirely in memory and uploaded to GCS, ensuring data durability
     * across container restarts and accessibility to all scaled-out instances.</p>
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string  (e.g. "2024")
     * @return result map containing {@code status} and the GCS URI of the report
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0063 FIX (line 37): GCS object key replaces the ephemeral local path
        // Previously: String fullPath = REPORT_BASE_PATH + fileName;
        String gcsObjectName = gcsReportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content entirely in memory — no local file-system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // cr-java-0063 FIX (lines 37, 39, 42):
            //   Line 37 — new File(REPORT_BASE_PATH)  → BlobId.of(gcsBucketName, gcsObjectName)
            //   Line 39 — reportDir.mkdirs()          → removed (GCS creates paths implicitly)
            //   Line 42 — new FileWriter(fullPath)    → storage.create(blobInfo, bytes)
            Storage storage = StorageOptions.getDefaultInstance().getService();
            BlobId blobId = BlobId.of(gcsBucketName, gcsObjectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();
            storage.create(blobInfo, csvContent.toString().getBytes(StandardCharsets.UTF_8));

            result.put("status", "generated");
            result.put("path", "gs://" + gcsBucketName + "/" + gcsObjectName);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name.
     *
     * @param reportName the name of the report file
     * @return download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0088 — plain HTTP; should be upgraded to HTTPS in a follow-up fix
        return "http://reports.resorts-internal.com:" + serverPort + "/download/" + reportName;
    }

    /**
     * Returns system information including GCS storage paths and server port.
     *
     * <p>cr-java-0063 FIX: Previously returned hard-coded local paths
     * ({@code REPORT_BASE_PATH} and {@code BACKUP_PATH}). Now returns the GCS URIs
     * derived from externalised configuration properties.</p>
     *
     * @return map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (line 70 of original source):
        // Replaced server-local SimpleDateFormat + new Date() with UTC-based java.time API.
        // Using Instant.now() with explicit UTC offset (ZoneOffset.UTC) ensures consistent
        // timestamps across all cloud regions and container instances, eliminating server-local
        // timezone dependencies that cause scheduling failures in distributed GCP deployments.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: GCS URIs replace hard-coded local paths
        info.put("reportPath", "gs://" + gcsBucketName + "/" + gcsReportPrefix);
        info.put("backupPath", "gs://" + gcsBucketName + "/" + gcsBackupPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
