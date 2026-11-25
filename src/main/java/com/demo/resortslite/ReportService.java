package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

// Reports are stored as GCS blobs (byte[] via getBytes()); no SQL IMAGE/Blob columns are used.
// Any binary DB column maps to PostgreSQL BYTEA via getBytes()/setBytes().
@Service
public class ReportService {

    // Reports are persisted to Google Cloud Storage (durable) instead of the ephemeral container
    // filesystem. Bucket/prefix are externalised (REPORT_GCS_BUCKET / REPORT_GCS_PREFIX env vars).
    // Authentication uses Application Default Credentials (workload identity / service account).
    private final String reportBucket;
    private final String reportBasePath;
    private final String backupPath;
    private volatile Storage storage;

    // Server port is externalised; Cloud Run / GKE inject PORT dynamically.
    private final int serverPort;

    public ReportService(
            @Value("${app.report.gcs-bucket:}") String reportBucket,
            @Value("${app.report.base-path:reports}") String reportBasePath,
            @Value("${app.report.backup-path:backups}") String backupPath,
            @Value("${server.port:${PORT:8080}}") int serverPort) {
        this.reportBucket = reportBucket;
        this.reportBasePath = reportBasePath;
        this.backupPath = backupPath;
        this.serverPort = serverPort;
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
        String prefix = reportBasePath.endsWith("/") ? reportBasePath : reportBasePath + "/";
        String objectName = prefix + fileName;

        Map<String, Object> result = new HashMap<>();

        if (reportBucket == null || reportBucket.trim().isEmpty()) {
            result.put("status", "error");
            result.put("message", "Report bucket not configured (set REPORT_GCS_BUCKET)");
            return result;
        }

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
            result.put("path", "gs://" + reportBucket + "/" + objectName);
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
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
