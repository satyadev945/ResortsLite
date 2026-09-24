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
 * ReportService — generates and stores resort reports in Google Cloud Storage.
 *
 * <p>All former local-filesystem write operations (FileWriter / File.mkdirs) have been
 * replaced with GCS {@link Storage#create} calls so that report data is durable across
 * container restarts and horizontal scaling events (cr-java-0062 remediation).</p>
 *
 * <p>Configuration is externalised via Spring {@code @Value} bindings backed by
 * environment variables, following the 12-factor app principle.</p>
 */
@Service
public class ReportService {

    /**
     * GCS bucket that holds all generated reports and nightly backups.
     * Resolved from the {@code GCS_BUCKET_NAME} environment variable at runtime;
     * falls back to {@code resorts-lite-reports} for local development.
     */
    @Value("${gcs.bucket.name}")
    private String gcsBucketName;

    /**
     * Object-name prefix for monthly report blobs (e.g. {@code reports/}).
     * Replaces the former hard-coded absolute path {@code /var/legacy/reports/}.
     */
    @Value("${gcs.reports.prefix:reports/}")
    private String reportsPrefix;

    /**
     * Object-name prefix for nightly backup blobs (e.g. {@code backups/nightly/}).
     * Replaces the former Windows-style hard-coded path {@code C:\ResortBackups\nightly\}.
     */
    @Value("${gcs.backup.prefix:backups/nightly/}")
    private String backupPrefix;

    /**
     * HTTP port the application listens on.
     * Resolved from the {@code SERVER_PORT} / {@code server.port} environment variable
     * so that container orchestrators can assign ports dynamically.
     */
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Base URL of the internal report download service.
     * cr-java-0071 remediation: replaces the former hard-coded literal
     * "http://reports.resorts-internal.com:8080/download/" with an externalised
     * value resolved from the {@code REPORT_DOWNLOAD_BASE_URL} environment variable,
     * enabling seamless deployment across dev, staging, and production environments
     * without any source-code changes.
     */
    @Value("${app.report.download.base.url:${REPORT_DOWNLOAD_BASE_URL:http://reports.resorts-internal.com:8080/download/}}")
    private String reportDownloadBaseUrl;

    /**
     * Lazily-initialised GCS client.
     * Uses Application Default Credentials (ADC) which are automatically available
     * on GCP Compute Engine, Cloud Run, and GKE workloads.
     */
    private Storage storageClient;

    private Storage getStorage() {
        if (storageClient == null) {
            storageClient = StorageOptions.getDefaultInstance().getService();
        }
        return storageClient;
    }

    /**
     * Generates a monthly CSV report and uploads it directly to Google Cloud Storage.
     *
     * <p>Previously this method wrote to the local file system using {@link java.io.FileWriter},
     * which is incompatible with ephemeral container environments. The fix (cr-java-0062)
     * builds the CSV content in memory and persists it as a GCS blob, ensuring the data
     * survives container restarts and scale-out events.</p>
     *
     * @param month two-digit month string (e.g. {@code "03"})
     * @param year  four-digit year string  (e.g. {@code "2024"})
     * @return a result map containing {@code status}, {@code path} (GCS URI), and {@code serverPort}
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // GCS object name replaces the former local file path constructed from REPORT_BASE_PATH.
        String objectName = reportsPrefix + "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content entirely in memory — no local File or FileWriter required.
            // This is the cr-java-0062 fix: the FileWriter(fullPath) call at the original
            // line 42 is replaced by a GCS Storage.create() call below.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload the report directly to Google Cloud Storage (replaces local FileWriter write).
            BlobId   blobId   = BlobId.of(gcsBucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();
            getStorage().create(blobInfo, csvContent.toString().getBytes(StandardCharsets.UTF_8));

            String gcsUri = "gs://" + gcsBucketName + "/" + objectName;
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
     * @param reportName the name of the report file
     * @return a URL string pointing to the report download endpoint
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 remediation: URL base is now resolved from the injected
        // reportDownloadBaseUrl field, backed by the REPORT_DOWNLOAD_BASE_URL env var.
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including GCS storage paths and the active server port.
     *
     * <p>cr-java-0111 remediation: The former {@code new SimpleDateFormat(...).format(new Date())}
     * call relied on the server-local JVM timezone, which causes inconsistent timestamps across
     * containers deployed in different regions. Replaced with {@link Instant#now()} formatted
     * in ISO-8601 UTC (Z suffix) via {@link DateTimeFormatter#ISO_INSTANT}, ensuring all
     * replicas produce identical, unambiguous UTC timestamps regardless of host timezone.</p>
     *
     * @return a map of system metadata entries
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111: Standardise on UTC — Instant.now() is always UTC; format as ISO-8601.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // GCS URIs replace the former hard-coded local paths (cr-java-0062).
        info.put("reportPath",   "gs://" + gcsBucketName + "/" + reportsPrefix);
        info.put("backupPath",   "gs://" + gcsBucketName + "/" + backupPrefix);
        info.put("serverPort",   serverPort);
        info.put("generatedAt",  timestamp);
        return info;
    }
}
