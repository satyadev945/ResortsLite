package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Reports and backups are stored in Azure Blob Storage instead of local file system paths.
    private final String connectionString;
    private final String accountEndpoint;
    private final String containerName;
    private final String reportBasePath;
    private final String backupPath;
    // Report download base URL resolved from Azure App Configuration (app.reports.download-base-url).
    private final String reportDownloadBaseUrl;

    // Server port resolved from configuration (Azure App Configuration / SERVER_PORT / PORT env var).
    private final int serverPort;

    public ReportService(
            @Value("${azure.storage.connection-string:}") String connectionString,
            @Value("${azure.storage.account-endpoint:}") String accountEndpoint,
            @Value("${azure.storage.reports-container:resort-reports}") String containerName,
            @Value("${azure.storage.reports-prefix:reports/}") String reportBasePath,
            @Value("${azure.storage.backup-prefix:backups/nightly/}") String backupPath,
            @Value("${app.reports.download-base-url:}") String reportDownloadBaseUrl,
            @Value("${server.port}") int serverPort) {
        this.connectionString = connectionString;
        this.accountEndpoint = accountEndpoint;
        this.containerName = containerName;
        this.reportBasePath = reportBasePath;
        this.backupPath = backupPath;
        this.reportDownloadBaseUrl = reportDownloadBaseUrl;
        this.serverPort = serverPort;
    }

    private BlobContainerClient getContainerClient() {
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
        if (connectionString != null && !connectionString.isEmpty()) {
            builder.connectionString(connectionString);
        } else if (accountEndpoint != null && !accountEndpoint.isEmpty()) {
            builder.endpoint(accountEndpoint).credential(new DefaultAzureCredentialBuilder().build());
        } else {
            throw new IllegalStateException(
                    "Azure Storage not configured: set AZURE_STORAGE_CONNECTION_STRING or AZURE_STORAGE_ACCOUNT_ENDPOINT");
        }
        BlobServiceClient serviceClient = builder.buildClient();
        BlobContainerClient container = serviceClient.getBlobContainerClient(containerName);
        if (!container.exists()) {
            container.create();
        }
        return container;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String blobName = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csv.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csv.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            byte[] data = csv.toString().getBytes(StandardCharsets.UTF_8);

            BlobContainerClient container = getContainerClient();
            BlobClient blobClient = container.getBlobClient(blobName);
            blobClient.upload(new ByteArrayInputStream(data), data.length, true);

            result.put("status", "generated");
            result.put("path", blobClient.getBlobUrl());
            result.put("serverPort", serverPort);

        } catch (RuntimeException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        if (reportDownloadBaseUrl == null || reportDownloadBaseUrl.isEmpty()) {
            throw new IllegalStateException(
                    "Report download URL not configured: set app.reports.download-base-url in Azure App Configuration or REPORTS_DOWNLOAD_BASE_URL");
        }
        String base = reportDownloadBaseUrl.endsWith("/") ? reportDownloadBaseUrl : reportDownloadBaseUrl + "/";
        return base + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // Timezone-agnostic UTC timestamp (ISO-8601) instead of server-local time.
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", containerName + "/" + reportBasePath);
        info.put("backupPath", containerName + "/" + backupPath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
