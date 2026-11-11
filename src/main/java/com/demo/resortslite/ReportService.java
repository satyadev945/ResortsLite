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
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
// PostgreSQL: binary report content is stored as BYTEA (byte[] via setBytes()/getBytes()),
// replacing the SQL Server IMAGE type.

@Service
public class ReportService {

    // Reports and backups are stored in Azure Blob Storage instead of local file system paths.
    @Value("${app.storage.azure.connection-string:}")
    private String connectionString;

    @Value("${app.storage.azure.endpoint:}")
    private String storageEndpoint;

    @Value("${app.storage.azure.reports-container:resort-reports}")
    private String reportsContainer;

    // Blob name prefix (virtual folder) replacing the former /var/legacy/reports/ path
    @Value("${app.storage.azure.reports-prefix:reports/}")
    private String reportsPrefix;

    // Blob name prefix (virtual folder) replacing the former C:\ResortBackups\nightly\ path
    @Value("${app.storage.azure.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    // Port is injected from the environment (PORT / SERVER_PORT) via Azure App Configuration; no fixed default port
    @Value("${server.port}")
    private int serverPort;

    // Report download service location and port are externalised (Azure App Configuration / env vars)
    @Value("${app.reports.download.host:reports.resorts-internal.com}")
    private String reportsDownloadHost;

    @Value("${app.reports.download.port}")
    private int reportsDownloadPort;

    private volatile BlobContainerClient containerClient;

    // UTC clock: timezone-agnostic so timestamps are consistent across regions/instances
    private final Clock clock = Clock.systemUTC();

    private BlobContainerClient getContainerClient() {
        if (containerClient == null) {
            synchronized (this) {
                if (containerClient == null) {
                    BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
                    if (connectionString != null && !connectionString.isEmpty()) {
                        builder.connectionString(connectionString);
                    } else if (storageEndpoint != null && !storageEndpoint.isEmpty()) {
                        builder.endpoint(storageEndpoint)
                               .credential(new DefaultAzureCredentialBuilder().build());
                    } else {
                        throw new IllegalStateException(
                                "Azure Blob Storage is not configured. Set AZURE_STORAGE_CONNECTION_STRING "
                                + "or AZURE_STORAGE_ENDPOINT.");
                    }
                    BlobServiceClient serviceClient = builder.buildClient();
                    BlobContainerClient client = serviceClient.getBlobContainerClient(reportsContainer);
                    if (!client.exists()) {
                        client.create();
                    }
                    containerClient = client;
                }
            }
        }
        return containerClient;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String blobName = reportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csv.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csv.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            byte[] data = csv.toString().getBytes(StandardCharsets.UTF_8);

            BlobClient blobClient = getContainerClient().getBlobClient(blobName);
            blobClient.upload(new ByteArrayInputStream(data), data.length, true);

            result.put("status", "generated");
            result.put("path", blobClient.getBlobUrl());
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
        return "http://" + reportsDownloadHost + ":" + reportsDownloadPort + "/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now(clock));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportsContainer + "/" + reportsPrefix);
        info.put("backupPath", reportsContainer + "/" + backupPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
