package com.demo.resortslite;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
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

    // Reports are stored in Azure Blob Storage instead of a local file system path.
    // Blob name prefix (virtual directory) for generated reports; configurable via environment.
    private final String reportBlobPrefix;

    // Blob name prefix (virtual directory) for nightly backups; configurable via environment.
    private final String backupBlobPrefix;

    // Azure Blob Storage settings supplied through environment variables / app configuration.
    private final String connectionString;
    private final String accountEndpoint;
    private final String containerName;

    // Server port is externalised (PORT / SERVER_PORT env var).
    private final int serverPort;

    // Base URL for report downloads (host/port/scheme externalised; set REPORT_DOWNLOAD_BASE_URL).
    private final String reportDownloadBaseUrl;

    private volatile BlobContainerClient containerClient;

    public ReportService(
            @Value("${app.reports.blob-prefix:reports/}") String reportBlobPrefix,
            @Value("${app.backups.blob-prefix:backups/nightly/}") String backupBlobPrefix,
            @Value("${azure.storage.connection-string:}") String connectionString,
            @Value("${azure.storage.account-endpoint:}") String accountEndpoint,
            @Value("${azure.storage.container-name:resort-reports}") String containerName,
            @Value("${server.port}") int serverPort,
            @Value("${app.reports.download-base-url:}") String reportDownloadBaseUrl) {
        this.reportBlobPrefix = reportBlobPrefix;
        this.backupBlobPrefix = backupBlobPrefix;
        this.connectionString = connectionString;
        this.accountEndpoint = accountEndpoint;
        this.containerName = containerName;
        this.serverPort = serverPort;
        this.reportDownloadBaseUrl = reportDownloadBaseUrl;
    }

    private BlobContainerClient getContainerClient() {
        if (containerClient == null) {
            synchronized (this) {
                if (containerClient == null) {
                    BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
                    if (connectionString != null && !connectionString.isEmpty()) {
                        builder.connectionString(connectionString);
                    } else if (accountEndpoint != null && !accountEndpoint.isEmpty()) {
                        // Managed identity / DefaultAzureCredential is used when no connection string is provided.
                        builder.endpoint(accountEndpoint)
                               .credential(new com.azure.identity.DefaultAzureCredentialBuilder().build());
                    } else {
                        throw new IllegalStateException(
                                "Azure Blob Storage is not configured. Set AZURE_STORAGE_CONNECTION_STRING "
                                + "or AZURE_STORAGE_ACCOUNT_ENDPOINT.");
                    }
                    BlobServiceClient serviceClient = builder.buildClient();
                    BlobContainerClient client = serviceClient.getBlobContainerClient(containerName);
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
        String blobName = reportBlobPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csv.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csv.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            // Report content is held as byte[] (PostgreSQL BYTEA via getBytes()/setBytes(), not IMAGE/Blob streams).
            byte[] data = csv.toString().getBytes(StandardCharsets.UTF_8);

            BlobClient blobClient = getContainerClient().getBlobClient(blobName);
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
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        String base = (reportDownloadBaseUrl != null && !reportDownloadBaseUrl.isEmpty())
                ? reportDownloadBaseUrl.replaceAll("/+$", "")
                : "http://localhost:" + serverPort;
        return base + "/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // Timezone-agnostic: always UTC (ISO-8601), independent of the server-local timezone.
        String timestamp = ZonedDateTime.now(Clock.systemUTC().withZone(ZoneOffset.UTC))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", containerName + "/" + reportBlobPrefix);
        info.put("backupPath", containerName + "/" + backupBlobPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
