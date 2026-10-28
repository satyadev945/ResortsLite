package com.demo.resortslite;

// cr-java-0063 FIX: Replaced java.io.File / FileWriter imports with Azure Blob Storage SDK imports.
// All persistent data storage operations now use Azure Blob Storage instead of the local file system.
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cr-java-0063 FIX: Azure Blob Storage configuration injected from environment variables /
    // application properties — replaces hardcoded local file system paths (REPORT_BASE_PATH,
    // BACKUP_PATH) that used java.io.File for persistent data storage.
    @Value("${azure.storage.connection-string}")
    private String storageConnectionString;

    @Value("${azure.storage.reports-container:reports}")
    private String reportsContainerName;

    @Value("${azure.storage.backup-container:backups}")
    private String backupContainerName;

    // cr-java-0071 FIX: Hardcoded report download URL externalized to Azure App Configuration.
    // Value is injected from application.properties / environment variable
    // AZURE_REPORTS_BASE_URL, enabling environment-agnostic deployments.
    @Value("${app.reports.base.url}")
    private String reportsBaseUrl;

    // cr-java-0077 FIX: Hardcoded SERVER_PORT constant replaced with @Value-injected field
    // backed by the SERVER_PORT environment variable (default: 8080).
    // Azure App Configuration / environment variables supply the actual port at runtime,
    // enabling dynamic port assignment required by Azure Container Apps and AKS.
    @Value("${server.port:8080}")
    private int serverPort;

    // cr-java-0111 FIX: Azure Service Bus connection string and queue name injected from
    // environment variables / application properties — enables distributed, timezone-agnostic
    // scheduled task delivery via Azure Service Bus Scheduled Messages.
    @Value("${azure.servicebus.connection-string:}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.report-queue:report-tasks}")
    private String reportQueueName;

    /**
     * cr-java-0063 FIX: Returns a BlobContainerClient for the given container, creating the
     * container if it does not exist. Replaces the original java.io.File.mkdirs() pattern
     * (original line 39: reportDir.mkdirs()) with Azure Blob container creation.
     */
    private BlobContainerClient getOrCreateContainer(String containerName) {
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(storageConnectionString)
                .buildClient();
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }
        return containerClient;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 FIX (original line 37): Replaced "new File(REPORT_BASE_PATH)" with
            // Azure BlobContainerClient — no local file system directory creation needed.
            // cr-java-0063 FIX (original line 39): Replaced "reportDir.exists() / reportDir.mkdirs()"
            // with BlobContainerClient.exists() / create() on Azure Blob Storage.
            // cr-java-0063 FIX (original line 42): Replaced "new FileWriter(fullPath)" with
            // BlobClient.upload(InputStream, ...) — data written directly to Azure Blob Storage.

            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // Upload to Azure Blob Storage instead of writing to local file system
            BlobContainerClient containerClient = getOrCreateContainer(reportsContainerName);
            BlobClient blobClient = containerClient.getBlobClient(fileName);

            try (InputStream inputStream = new ByteArrayInputStream(contentBytes)) {
                blobClient.upload(inputStream, contentBytes.length, true);
            }

            result.put("status", "generated");
            result.put("blobUrl", blobClient.getBlobUrl());
            result.put("containerName", reportsContainerName);
            result.put("blobName", fileName);
            result.put("serverPort", serverPort); // cr-java-0077 FIX: uses injected value

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * cr-java-0111 FIX: Schedules a report generation task via Azure Service Bus Scheduled
     * Messages instead of relying on java.util.Timer or server-local scheduling.
     * The message is enqueued with a UTC-based scheduled enqueue time, ensuring
     * timezone-agnostic, distributed task delivery across all cloud instances.
     *
     * @param month       target report month (e.g. "03")
     * @param year        target report year  (e.g. "2024")
     * @param delaySeconds number of seconds from now (UTC) to schedule the task
     * @return map containing scheduling confirmation details
     */
    public Map<String, Object> scheduleReportGeneration(String month, String year, long delaySeconds) {
        Map<String, Object> result = new HashMap<>();
        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportQueueName)
                .buildClient()) {

            // cr-java-0111 FIX: Use OffsetDateTime.now(ZoneOffset.UTC) for timezone-agnostic
            // scheduling — eliminates server-local timezone dependency (java.util.Date / SimpleDateFormat).
            OffsetDateTime scheduledEnqueueTime = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(delaySeconds);

            String messageBody = String.format("{\"action\":\"generateMonthlyReport\",\"month\":\"%s\",\"year\":\"%s\"}", month, year);
            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            message.setScheduledEnqueueTime(scheduledEnqueueTime);

            long sequenceNumber = senderClient.scheduleMessage(message, scheduledEnqueueTime);

            result.put("status", "scheduled");
            result.put("queueName", reportQueueName);
            result.put("sequenceNumber", sequenceNumber);
            result.put("scheduledEnqueueTimeUtc", scheduledEnqueueTime.toString());
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
        // cr-java-0071 FIX: Hardcoded URL replaced with value injected from Azure App Configuration
        // via the app.reports.base.url property (backed by AZURE_REPORTS_BASE_URL env var).
        return reportsBaseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 FIX: Replaced server-local timezone-dependent
        // "new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())" with
        // OffsetDateTime.now(ZoneOffset.UTC).toString() — produces an ISO-8601 UTC timestamp
        // that is consistent across all cloud instances regardless of server timezone,
        // eliminating timezone inconsistencies in distributed / multi-region deployments.
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString();
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: Replaced hardcoded local paths (REPORT_BASE_PATH, BACKUP_PATH)
        // with Azure Blob container name references — no java.io.File dependency.
        info.put("reportsContainer", reportsContainerName);
        info.put("backupContainer", backupContainerName);
        info.put("serverPort", serverPort);                   // cr-java-0077 FIX: uses injected value
        info.put("generatedAt", timestamp);
        info.put("reportQueueName", reportQueueName);         // cr-java-0111 FIX: Service Bus queue reference
        return info;
    }
}
