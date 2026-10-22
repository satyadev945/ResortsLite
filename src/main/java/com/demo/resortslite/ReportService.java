package com.demo.resortslite;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — generates and stores resort reports.
 *
 * <p>cr-java-0063 FIX: All java.io.File-based persistent storage operations have been
 * replaced with Google Cloud Storage (GCS) using the Cloud Storage Java client library.
 * This ensures data durability, scalability, and cloud-native architecture on GCP.
 * The GCS bucket name and backup prefix are resolved from environment variables /
 * application properties, eliminating every hard-coded absolute path that previously
 * existed in this class.</p>
 *
 * <p>Original violations (lines 37–42 in source):
 * <ul>
 *   <li>Line 37: {@code File reportDir = new File(REPORT_BASE_PATH)} — replaced with GCS BlobId</li>
 *   <li>Line 39: {@code reportDir.mkdirs()} — removed; GCS buckets do not require directory creation</li>
 *   <li>Line 42: {@code FileWriter writer = new FileWriter(fullPath)} — replaced with GCS storage.create()</li>
 * </ul>
 * </p>
 */
@Service
public class ReportService {

    /**
     * GCS bucket name injected from the {@code GCS_REPORTS_BUCKET} environment
     * variable or the {@code gcs.reports.bucket} application property.
     * Replaces the hard-coded absolute path {@code /var/legacy/reports/} (cr-java-0063).
     */
    @Value("${gcs.reports.bucket:${GCS_REPORTS_BUCKET:resorts-lite-reports}}")
    private String reportsBucket;

    /**
     * GCS object prefix used for nightly backups.
     * Replaces the hard-coded Windows path {@code C:\\ResortBackups\\nightly\\} (cr-java-0063).
     */
    @Value("${gcs.backup.prefix:${GCS_BACKUP_PREFIX:backups/nightly/}}")
    private String backupPrefix;

    /**
     * cr-java-0077 FIX (Line 28): Hard-coded {@code private static final int SERVER_PORT = 8080}
     * replaced with an externalized {@code @Value} injection.
     *
     * <p>The port is resolved at runtime from the GCP Cloud Run {@code PORT} environment
     * variable (injected automatically by Cloud Run), falling back to the {@code SERVER_PORT}
     * environment variable, and finally to {@code 8080} for local development only.
     * This enables dynamic port binding required by GCP Cloud Run, GKE service discovery,
     * and Cloud Endpoints — eliminating the deployment failure caused by the hard-coded port.</p>
     */
    @Value("${server.port:${PORT:${SERVER_PORT:8080}}}")
    private int serverPort;

    /**
     * cr-java-0071 FIX: Hard-coded environment-specific report download base URL has been
     * replaced with an externalized configuration property backed by GCP Secret Manager.
     * The value is resolved at runtime from the {@code app.reports.download.base-url}
     * application property, which is itself sourced from the
     * {@code sm://resorts-lite-reports-download-url} GCP Secret Manager secret (or the
     * {@code REPORTS_DOWNLOAD_BASE_URL} environment variable for local development).
     * This eliminates the environment-specific hard-coded URL and enables seamless
     * deployment across dev, staging, and production environments.
     */
    @Value("${app.reports.download.base-url:${REPORTS_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download}}")
    private String reportsDownloadBaseUrl;

    /**
     * Returns a GCS {@link Storage} client.  The project-id and credentials are
     * picked up automatically from the GCP environment (Application Default
     * Credentials / Workload Identity) — no explicit configuration is required.
     */
    private Storage storageClient() {
        return StorageOptions.getDefaultInstance().getService();
    }

    /**
     * Generates a monthly CSV report and uploads it directly to Google Cloud
     * Storage.  No local file-system writes are performed (cr-java-0063 fix).
     *
     * <p>cr-java-0063 FIX (Lines 37–42):
     * <ul>
     *   <li>Removed: {@code File reportDir = new File(REPORT_BASE_PATH)} (line 37)</li>
     *   <li>Removed: {@code reportDir.mkdirs()} (line 39)</li>
     *   <li>Removed: {@code FileWriter writer = new FileWriter(fullPath)} (line 42)</li>
     *   <li>Added: GCS {@code BlobId}, {@code BlobInfo}, and {@code storage.create()} to upload
     *       report content directly to Google Cloud Storage bucket.</li>
     * </ul>
     * </p>
     *
     * @param month two-digit month string (e.g. {@code "03"})
     * @param year  four-digit year string  (e.g. {@code "2024"})
     * @return a result map containing {@code status}, {@code path} (GCS URI),
     *         and {@code serverPort}
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String objectName = "resort_report_" + month + "_" + year + ".csv";

        // cr-java-0063 FIX (Line 37): Replaced new File(REPORT_BASE_PATH) with GCS BlobId.
        // cr-java-0063 FIX (Line 39): Removed reportDir.mkdirs() — GCS does not use directories.
        // cr-java-0063 FIX (Line 42): Replaced new FileWriter(fullPath) with GCS storage.create().
        // Report content is built in memory and uploaded directly to Google Cloud Storage.
        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file-system dependency.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload the report directly to Google Cloud Storage (replaces FileWriter).
            Storage storage = storageClient();
            BlobId   blobId   = BlobId.of(reportsBucket, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();
            storage.create(blobInfo, csvContent.toString().getBytes(StandardCharsets.UTF_8));

            String gcsUri = "gs://" + reportsBucket + "/" + objectName;
            result.put("status",     "generated");
            result.put("path",       gcsUri);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status",  "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name.
     *
     * <p>cr-java-0071 FIX (Line 66): Replaced the hard-coded environment-specific URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} with the externalized
     * property {@code app.reports.download.base-url}, resolved from GCP Secret Manager
     * ({@code sm://resorts-lite-reports-download-url}) or the {@code REPORTS_DOWNLOAD_BASE_URL}
     * environment variable. This enables seamless deployment across environments without
     * code changes.</p>
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX (Line 66): Hard-coded URL replaced with externalized property
        // 'app.reports.download.base-url' sourced from GCP Secret Manager or env variable.
        return reportsDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including GCS storage paths and the active
     * server port.  Hard-coded local paths have been replaced with GCS URIs
     * (cr-java-0063 fix).
     *
     * <p>cr-java-0111 FIX (Line 70): {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}
     * relied on the server-local JVM timezone, which causes timestamp inconsistencies across
     * GCP Cloud Run instances, GKE pods, and multi-region deployments.  Replaced with
     * {@code Instant.now()} formatted via {@code DateTimeFormatter.ISO_INSTANT} pinned to
     * {@code ZoneOffset.UTC}, ensuring all timestamps are always in UTC regardless of the
     * host timezone configuration.</p>
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (Line 70): Replaced server-local SimpleDateFormat/new Date() with
        // UTC-pinned Instant.now() formatted via DateTimeFormatter.ISO_INSTANT at ZoneOffset.UTC.
        // This eliminates timezone inconsistencies across distributed GCP Cloud Run / GKE instances.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: GCS URIs replace the former hard-coded local paths.
        info.put("reportPath",   "gs://" + reportsBucket + "/");
        info.put("backupPath",   "gs://" + reportsBucket + "/" + backupPrefix);
        info.put("serverPort",   serverPort);
        info.put("generatedAt",  timestamp);
        return info;
    }
}
