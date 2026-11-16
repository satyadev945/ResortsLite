package com.demo.resortslite;

import org.springframework.stereotype.Service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Reports are stored in Azure Blob Storage instead of a local absolute path.
    // Configure via AZURE_STORAGE_CONNECTION_STRING and AZURE_STORAGE_REPORT_CONTAINER.
    @Value("${azure.storage.connection-string:${AZURE_STORAGE_CONNECTION_STRING:}}")
    private String storageConnectionString;

    @Value("${azure.storage.report-container:${AZURE_STORAGE_REPORT_CONTAINER:reports}}")
    private String reportContainerName;

    @Value("${azure.storage.backup-container:${AZURE_STORAGE_BACKUP_CONTAINER:resort-backups}}")
    private String backupContainerName;

    // cr-java-0077: port is resolved from environment (SERVER_PORT / PORT / WEBSITES_PORT) via server.port; no hardcoded default here
    @Value("${server.port}")
    private int serverPort;

    private BlobContainerClient getReportContainer() {
        BlobContainerClient container = new BlobServiceClientBuilder()
                .connectionString(storageConnectionString)
                .buildClient()
                .getBlobContainerClient(reportContainerName);
        if (!container.exists()) {
            container.create();
        }
        return container;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            String content = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";
            byte[] data = content.getBytes(StandardCharsets.UTF_8);

            BlobClient blob = getReportContainer().getBlobClient(fileName);
            blob.upload(new ByteArrayInputStream(data), data.length, true);

            result.put("status", "generated");
            result.put("path", blob.getBlobUrl());
            result.put("serverPort", serverPort);

        } catch (Exception e) {
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
        // cr-java-0111: timestamps are generated in UTC (timezone-agnostic) rather than server-local time,
        // so results are consistent across regions/instances.
        String timestamp = ZonedDateTime.now(Clock.systemUTC())
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportContainerName);
        info.put("backupPath", backupContainerName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
