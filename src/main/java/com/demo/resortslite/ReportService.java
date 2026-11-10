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
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Reports and backups are stored in Azure Blob Storage (virtual prefixes instead of
    // absolute file system paths). Prefixes and connection details are externalised.
    @Value("${app.report.blob.prefix:reports/}")
    private String reportBasePath;

    @Value("${app.report.backup.blob.prefix:backups/nightly/}")
    private String backupPath;

    // Report download base URL is loaded from Azure App Configuration (/resortslite/app.report.download-base-url)
    @Value("${app.report.download-base-url:}")
    private String reportDownloadBaseUrl;

    @Value("${app.azure.storage.connection-string:}")
    private String storageConnectionString;

    @Value("${app.azure.storage.endpoint:}")
    private String storageEndpoint;

    @Value("${app.azure.storage.container:resort-reports}")
    private String containerName;

    private volatile BlobContainerClient containerClient;

    private BlobContainerClient getContainerClient() {
        if (containerClient == null) {
            synchronized (this) {
                if (containerClient == null) {
                    BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
                    if (storageConnectionString != null && !storageConnectionString.isEmpty()) {
                        builder.connectionString(storageConnectionString);
                    } else {
                        // Managed identity / environment credentials when no connection string is provided
                        builder.endpoint(storageEndpoint)
                               .credential(new com.azure.identity.DefaultAzureCredentialBuilder().build());
                    }
                    BlobContainerClient client = builder.buildClient().getBlobContainerClient(containerName);
                    if (!client.exists()) {
                        client.create();
                    }
                    containerClient = client;
                }
            }
        }
        return containerClient;
    }

    // Server port is externalised: resolved from Azure App Configuration (/resortslite/app.server.port)
    // or the PORT / SERVER_PORT environment variables; no hard-coded port in application logic.
    @Value("${app.server.port:${server.port:8080}}")
    private int serverPort;

    // UTC clock: avoids dependence on server-local timezone across regions/instances.
    // Deferred/scheduled work should be delivered through Azure Service Bus scheduled messages
    // rather than local java.util.Timer instances.
    private final Clock clock = Clock.systemUTC();

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csv.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csv.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            byte[] data = csv.toString().getBytes(StandardCharsets.UTF_8);

            BlobClient blob = getContainerClient().getBlobClient(fullPath);
            blob.upload(new ByteArrayInputStream(data), data.length, true);

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", serverPort);

        } catch (RuntimeException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        return reportDownloadBaseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // Timezone-agnostic: always UTC via an injectable Clock, never the server-local timezone
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC).format(clock.instant());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp + " UTC");
        return info;
    }
}
