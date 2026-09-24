package com.demo.resortslite;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
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

    // cr-java-0063 fix (lines 37, 39, 42 of original source):
    // Replaced all java.io.File-based persistent storage operations with Azure Blob Storage.
    // - Original line 37: new File(REPORT_BASE_PATH)  → Azure BlobContainerClient
    // - Original line 39: reportDir.mkdirs()           → containerClient.create() (if not exists)
    // - Original line 42: new FileWriter(fullPath)     → BlobClient.upload(InputStream, ...)
    // Azure Blob Storage connection string injected from environment variable / application config.
    // Replaces hard-coded absolute file paths (/var/legacy/reports/ and C:\ResortBackups\nightly\)
    // that were incompatible with cloud / container environments (cr-java-0061, cr-java-0063).
    @Value("${azure.storage.connection-string}")
    private String storageConnectionString;

    // Container name for monthly reports – externalised so it can differ per environment.
    @Value("${azure.storage.reports-container:resort-reports}")
    private String reportsContainerName;

    // Container name for nightly backups – replaces the Windows-style BACKUP_PATH constant.
    @Value("${azure.storage.backup-container:resort-backups}")
    private String backupContainerName;

    // cr-java-0111 REMEDIATED: Azure Service Bus connection string for scheduled message delivery.
    // Replaces server-local java.util.Timer / SimpleDateFormat(timezone) scheduling with
    // Azure Service Bus scheduled messages, enabling distributed, timezone-agnostic task execution
    // across multiple cloud regions and container instances.
    // Set AZURE_SERVICEBUS_CONNECTION_STRING in Azure App Service / Container Apps application settings.
    @Value("${azure.servicebus.connection-string:${AZURE_SERVICEBUS_CONNECTION_STRING:}}")
    private String serviceBusConnectionString;

    // cr-java-0111 REMEDIATED: Azure Service Bus queue name for scheduled report tasks.
    // Externalised so it can differ per environment (dev / staging / prod).
    @Value("${azure.servicebus.report-queue:resort-report-tasks}")
    private String reportQueueName;

    // cr-java-0077 REMEDIATED: Hard-coded port SERVER_PORT = 8080 removed.
    // Port is now fully externalized via the SERVER_PORT environment variable and
    // resolved by Spring Boot through server.port=${SERVER_PORT:8080} in application.properties.
    // Azure App Service / Container Apps can override SERVER_PORT at runtime, enabling
    // dynamic port assignment required by Azure container orchestration platforms.

    /**
     * Generates a monthly report CSV and uploads it to Azure Blob Storage.
     *
     * <p>cr-java-0063 remediation — Migrate java.io.File operations to Azure Blob Storage:</p>
     * <ul>
     *   <li>Original line 37: {@code File reportDir = new File(REPORT_BASE_PATH)} —
     *       replaced with {@link BlobContainerClient} obtained from {@link BlobServiceClient}.</li>
     *   <li>Original line 39: {@code reportDir.mkdirs()} —
     *       replaced with {@code containerClient.create()} guarded by an existence check.</li>
     *   <li>Original line 42: {@code FileWriter writer = new FileWriter(fullPath)} —
     *       replaced with {@code BlobClient.upload(InputStream, long, boolean)} so report data
     *       is written directly to Azure Blob Storage, ensuring durability and scalability
     *       across container restarts and scaling events in Azure cloud environments.</li>
     * </ul>
     *
     * <p>Replaces the previous implementation that wrote to the local file system path
     * /var/legacy/reports/ using FileWriter (cr-java-0061, cr-java-0062, cr-java-0063).</p>
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year for which the report is generated (e.g. "2024")
     * @return a map containing the operation status and the blob URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build the CSV content in memory – no local file system dependency.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // cr-java-0063 fix (original line 37): BlobServiceClient replaces new File(REPORT_BASE_PATH).
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .connectionString(storageConnectionString)
                    .buildClient();

            // cr-java-0063 fix (original line 37): BlobContainerClient replaces java.io.File directory.
            BlobContainerClient containerClient = blobServiceClient
                    .getBlobContainerClient(reportsContainerName);

            // cr-java-0063 fix (original line 39): containerClient.create() replaces reportDir.mkdirs().
            // Create the container if it does not already exist.
            if (!containerClient.exists()) {
                containerClient.create();
            }

            BlobClient blobClient = containerClient.getBlobClient(blobName);

            // cr-java-0063 fix (original line 42): BlobClient.upload() replaces new FileWriter(fullPath).
            // Data is streamed directly to Azure Blob Storage – no local file system write.
            try (InputStream inputStream = new ByteArrayInputStream(contentBytes)) {
                blobClient.upload(inputStream, contentBytes.length, true);
            }

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("blobUrl", blobClient.getBlobUrl());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Schedules a monthly report generation task via Azure Service Bus scheduled message delivery.
     *
     * <p>cr-java-0111 remediation — Replace local timers with Azure Service Bus Scheduled Messages:</p>
     * <p>Instead of relying on {@code java.util.Timer}, {@code java.util.Date}, or
     * {@code SimpleDateFormat} with server-local timezone settings, this method enqueues a
     * scheduled message on Azure Service Bus. The message is delivered at the specified UTC
     * {@link OffsetDateTime}, ensuring timezone-agnostic, distributed task execution across
     * multiple cloud regions and container instances without server affinity.</p>
     *
     * <p>The scheduled message payload carries the report parameters (month, year) so that
     * any consumer instance can process the task independently, enabling horizontal scaling
     * and fault-tolerant execution in Azure cloud environments.</p>
     *
     * @param month           the month for which the report should be generated (e.g. "03")
     * @param year            the year for which the report should be generated (e.g. "2024")
     * @param scheduledEnqueueTimeUtc the UTC time at which the message should be delivered
     * @return a map containing the scheduling status and the Azure Service Bus sequence number
     */
    public Map<String, Object> scheduleMonthlyReportTask(String month, String year,
                                                          OffsetDateTime scheduledEnqueueTimeUtc) {
        Map<String, Object> result = new HashMap<>();

        // cr-java-0111 fix (original line 70):
        // Replaced server-local SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
        // with Azure Service Bus scheduled message delivery.
        // OffsetDateTime.now(ZoneOffset.UTC) provides a timezone-agnostic UTC timestamp,
        // eliminating server-local timezone dependency that caused scheduling failures
        // in distributed cloud deployments across multiple regions and containers.
        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportQueueName)
                .buildClient()) {

            // Build the scheduled message payload with report parameters.
            String messageBody = String.format(
                    "{\"action\":\"generateMonthlyReport\",\"month\":\"%s\",\"year\":\"%s\"}",
                    month, year);

            ServiceBusMessage scheduledMessage = new ServiceBusMessage(messageBody);
            scheduledMessage.setContentType("application/json");
            scheduledMessage.setMessageId("report-" + month + "-" + year);
            scheduledMessage.setSubject("MonthlyReportGeneration");

            // Determine the enqueue time: use the provided UTC time, or default to now (UTC).
            // Using ZoneOffset.UTC ensures timezone-agnostic scheduling regardless of the
            // server's local timezone configuration — resolving cr-java-0111.
            OffsetDateTime enqueueTime = (scheduledEnqueueTimeUtc != null)
                    ? scheduledEnqueueTimeUtc.withOffsetSameInstant(ZoneOffset.UTC)
                    : OffsetDateTime.now(ZoneOffset.UTC);

            // Schedule the message for future delivery via Azure Service Bus.
            // Returns the sequence number that can be used to cancel the scheduled message.
            long sequenceNumber = senderClient.scheduleMessage(scheduledMessage, enqueueTime);

            result.put("status", "scheduled");
            result.put("sequenceNumber", sequenceNumber);
            result.put("scheduledEnqueueTimeUtc", enqueueTime.toString());
            result.put("queueName", reportQueueName);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using Azure Blob Storage.
     *
     * @param reportName the name of the report blob
     * @return the HTTPS URL for the report blob in Azure Blob Storage
     */
    public String buildReportDownloadUrl(String reportName) {
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(storageConnectionString)
                .buildClient();

        BlobContainerClient containerClient = blobServiceClient
                .getBlobContainerClient(reportsContainerName);

        BlobClient blobClient = containerClient.getBlobClient(reportName);
        return blobClient.getBlobUrl();
    }

    /**
     * Returns system information including Azure Blob Storage container references
     * and the current UTC timestamp via timezone-agnostic {@link OffsetDateTime}.
     *
     * <p>cr-java-0111 remediation — Replaced server-local
     * {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())} (original line 70)
     * with {@code OffsetDateTime.now(ZoneOffset.UTC).toString()} to eliminate server-local
     * timezone dependency. Using UTC ensures consistent timestamp generation across all
     * cloud regions and container instances in distributed Azure deployments.</p>
     *
     * <p>Replaces the previous implementation that exposed local file system paths
     * (REPORT_BASE_PATH and BACKUP_PATH) which were cloud-incompatible
     * (cr-java-0061, cr-java-0063).</p>
     *
     * @return a map of system information entries
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix (original line 70):
        // Replaced: String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        // With: OffsetDateTime.now(ZoneOffset.UTC) — timezone-agnostic UTC timestamp.
        // This eliminates server-local timezone dependency that caused time-related logic errors
        // in distributed cloud deployments across multiple regions and container instances.
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString();

        Map<String, Object> info = new HashMap<>();
        info.put("reportsContainer", reportsContainerName);
        info.put("backupContainer", backupContainerName);
        info.put("reportQueueName", reportQueueName);
        info.put("generatedAt", timestamp);
        return info;
    }
}
