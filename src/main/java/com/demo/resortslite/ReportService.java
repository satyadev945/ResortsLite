package com.demo.resortslite;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    // Binary report content is handled as byte[] (getBytes()/setBytes()), which maps to
    // PostgreSQL BYTEA; no SQL Server IMAGE / LOB streaming API is used.

    // Reports are stored in Azure Blob Storage instead of a local file system path.
    // Blob prefix (virtual directory) for generated reports.
    @Value("${azure.storage.report-prefix:reports/}")
    private String reportPrefix;

    // Blob prefix (virtual directory) for nightly backups.
    @Value("${azure.storage.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    // Azure Blob Storage container name, externalised via environment variable.
    @Value("${azure.storage.container-name:resort-reports}")
    private String containerName;

    // Azure Blob Storage connection string, externalised (env AZURE_STORAGE_CONNECTION_STRING / Key Vault).
    @Value("${azure.storage.connection-string:}")
    private String connectionString;

    // Azure Blob Storage account endpoint (used with managed identity when no connection string is set).
    @Value("${azure.storage.endpoint:}")
    private String endpoint;

    // Server port is externalised (env SERVER_PORT / Azure App Configuration key server.port)
    // so Azure platforms can assign it dynamically.
    @Value("${server.port:8080}")
    private int serverPort;

    private volatile BlobContainerClient containerClient;

    private BlobContainerClient getContainerClient() {
        if (containerClient == null) {
            synchronized (this) {
                if (containerClient == null) {
                    BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
                    if (connectionString != null && !connectionString.isEmpty()) {
                        builder.connectionString(connectionString);
                    } else {
                        builder.endpoint(endpoint)
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

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String blobName = reportPrefix + fileName;

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
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // Timezone-agnostic: always UTC, independent of server-local timezone (multi-region safe).
        String timestamp = ZonedDateTime.now(Clock.systemUTC().withZone(ZoneOffset.UTC))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", containerName + "/" + reportPrefix);  // Azure Blob location
        info.put("backupPath", containerName + "/" + backupPrefix);  // Azure Blob location
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
