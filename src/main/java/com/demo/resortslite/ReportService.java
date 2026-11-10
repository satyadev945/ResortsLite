package com.demo.resortslite;

import org.springframework.stereotype.Service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

// Binary report content is handled as byte[] (PostgreSQL BYTEA via getBytes()/setBytes());
// no SQL Server IMAGE / Blob streaming is used.
@Service
public class ReportService {

    // Reports are stored in Google Cloud Storage instead of the local file system.
    // Bucket and object prefixes are externalised via environment variables.
    @Value("${app.reports.gcs.bucket:${GCS_REPORT_BUCKET:resorts-reports}}")
    private String reportBucket;

    @Value("${app.reports.gcs.report-prefix:${GCS_REPORT_PREFIX:reports/}}")
    private String reportPrefix;

    @Value("${app.reports.gcs.backup-prefix:${GCS_BACKUP_PREFIX:backups/nightly/}}")
    private String backupPrefix;

    // Report download base URL externalised via environment variable / Secret Manager.
    @Value("${app.reports.download-base-url:${REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download/}}")
    private String reportDownloadBaseUrl;

    // Server port is externalised: resolved from the PORT env var (set by Cloud Run / GKE)
    // via server.port, never hard-coded in application logic.
    @Value("${server.port:${PORT:8080}}")
    private int serverPort;

    private volatile Storage storage;

    private Storage storage() {
        if (storage == null) {
            synchronized (this) {
                if (storage == null) {
                    storage = StorageOptions.getDefaultInstance().getService();
                }
            }
        }
        return storage;
    }

    private String reportBaseUri() {
        return "gs://" + reportBucket + "/" + reportPrefix;
    }

    private String backupBaseUri() {
        return "gs://" + reportBucket + "/" + backupPrefix;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String objectName = reportPrefix + fileName;
        String fullPath = "gs://" + reportBucket + "/" + objectName;

        Map<String, Object> result = new HashMap<>();

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csv.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csv.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(reportBucket, objectName))
                    .setContentType("text/csv")
                    .build();
            storage().create(blobInfo, csv.toString().getBytes(StandardCharsets.UTF_8));

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", serverPort);

        } catch (StorageException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // Report download base URL is externalised (REPORT_DOWNLOAD_BASE_URL env var).
        String base = reportDownloadBaseUrl.endsWith("/") ? reportDownloadBaseUrl : reportDownloadBaseUrl + "/";
        return base + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // Always use UTC (ISO-8601) rather than server-local timezone.
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now(Clock.systemUTC()));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBaseUri());
        info.put("backupPath", backupBaseUri());
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
