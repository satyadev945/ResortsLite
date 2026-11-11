package com.demo.resortslite;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Reports are stored in Google Cloud Storage instead of the local file system.
    // Bucket and object prefixes are externalised via configuration / environment variables.
    private final String reportBucket;
    private final String reportBasePath;
    private final String backupPath;
    private final String reportDownloadBaseUrl;
    private volatile Storage storage;

    // Server port is resolved from configuration (server.port <- PORT env var injected by GCP Cloud Run / GKE).
    private final int serverPort;

    public ReportService(
            @Value("${app.report.bucket:${REPORT_BUCKET:resorts-reports}}") String reportBucket,
            @Value("${app.report.base-path:${REPORT_BASE_PATH:reports/}}") String reportBasePath,
            @Value("${app.report.backup-path:${REPORT_BACKUP_PATH:backups/nightly/}}") String backupPath,
            @Value("${app.report.download-base-url}") String reportDownloadBaseUrl,
            @Value("${server.port:${PORT:8080}}") int serverPort) {
        this.serverPort = serverPort;
        this.reportDownloadBaseUrl = reportDownloadBaseUrl.endsWith("/") ? reportDownloadBaseUrl : reportDownloadBaseUrl + "/";
        this.reportBucket = reportBucket;
        this.reportBasePath = normalizePrefix(reportBasePath);
        this.backupPath = normalizePrefix(backupPath);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private Storage storage() {
        Storage s = storage;
        if (s == null) {
            synchronized (this) {
                if (storage == null) {
                    storage = StorageOptions.getDefaultInstance().getService();
                }
                s = storage;
            }
        }
        return s;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String objectName = reportBasePath + fileName;
        String fullPath = "gs://" + reportBucket + "/" + objectName;

        Map<String, Object> result = new HashMap<>();

        try {
            String content = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(reportBucket, objectName))
                    .setContentType("text/csv")
                    .build();
            storage().create(blobInfo, content.getBytes(StandardCharsets.UTF_8));

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
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return reportDownloadBaseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // Standardise on UTC (ISO-8601) rather than server-local timezone
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", "gs://" + reportBucket + "/" + reportBasePath);
        info.put("backupPath", "gs://" + reportBucket + "/" + backupPath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
