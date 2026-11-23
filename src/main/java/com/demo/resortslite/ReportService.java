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
    // Bucket and object prefixes are externalised via environment variables / Spring properties.
    @Value("${app.reports.gcs.bucket:${GCS_REPORT_BUCKET:resorts-reports}}")
    private String reportBucket;

    @Value("${app.reports.gcs.prefix:${GCS_REPORT_PREFIX:reports/}}")
    private String reportPrefix;

    @Value("${app.reports.gcs.backup-prefix:${GCS_BACKUP_PREFIX:backups/nightly/}}")
    private String backupPrefix;

    // Server port is externalised: resolved from server.port / PORT (injected by Cloud Run / GKE).
    @Value("${server.port:${PORT:8080}}")
    private int serverPort;

    private volatile Storage storage;

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

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private String reportBaseLocation() {
        return "gs://" + reportBucket + "/" + normalizePrefix(reportPrefix);
    }

    private String backupLocation() {
        return "gs://" + reportBucket + "/" + normalizePrefix(backupPrefix);
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String objectName = normalizePrefix(reportPrefix) + fileName;
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
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // Standardised on UTC (ISO-8601) so output is independent of server-local timezone.
        String timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBaseLocation());
        info.put("backupPath", backupLocation());
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
