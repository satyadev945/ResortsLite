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
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Azure Blob Storage configuration injected from environment variables / application properties.
    // cr-java-0061 fix: Replaces hard-coded absolute file paths:
    //   was: private static final String REPORT_BASE_PATH = "/var/legacy/reports/";
    //   was: private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";
    @Value("${azure.storage.connection-string}")
    private String storageConnectionString;

    @Value("${azure.storage.reports-container:reports}")
    private String reportsContainerName;

    @Value("${azure.storage.backup-container:backups}")
    private String backupContainerName;

    // cr-java-0071 fix: Hard-coded report download base URL replaced with externalized
    // configuration loaded from Azure App Configuration / application.properties via @Value.
    // was: return "http://reports.resorts-internal.com:8080/download/" + reportName;
    @Value("${app.reports.download-base-url}")
    private String reportDownloadBaseUrl;

    // cr-java-0077 fix: Hard-coded port 8080 replaced with externalized configuration.
    // The port is now injected from the SERVER_PORT environment variable (or Azure App
    // Configuration setting), falling back to 8080 when running locally.
    // Container orchestration platforms (Azure Container Apps, AKS) can override SERVER_PORT
    // at runtime, enabling dynamic port assignment without any code changes.
    // was: private static final int SERVER_PORT = 8080; // czr-port-001
    @Value("${server.port:8080}")
    private int serverPort;

    // cr-java-0111 fix: Azure Service Bus connection string and queue name for scheduled
    // report task delivery. Replaces server-local java.util.Timer scheduling with
    // Azure Service Bus scheduled messages — timezone-agnostic, distributed, and durable.
    // Set AZURE_SERVICEBUS_CONNECTION_STRING and AZURE_SERVICEBUS_REPORT_QUEUE as Azure
    // App Service application settings or environment variables.
    @Value("${azure.servicebus.connection-string:${AZURE_SERVICEBUS_CONNECTION_STRING:}}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.report-queue:${AZURE_SERVICEBUS_REPORT_QUEUE:report-tasks}}")
    private String reportQueueName;

    /**
     * Returns a BlobContainerClient for the given container, creating the container if it does not exist.
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

    /**
     * Generates a monthly report and uploads it to Azure Blob Storage.
     *
     * <p>cr-java-0062 fix (Local File System Write Operations): The original implementation
     * used {@code FileWriter} to write directly to the local file system at a hard-coded path
     * ({@code /var/legacy/reports/}). In cloud/containerised environments the local file system
     * is ephemeral — data written locally is lost on container restart or scale-out events.
     * This method now builds the CSV content in memory and uploads it to Azure Blob Storage
     * via {@link BlobClient#upload}, ensuring durable, cloud-native persistence.</p>
     *
     * <p>Before (lines 42-48 of original source — cr-java-0062 violation):</p>
     * <pre>
     *   FileWriter writer = new FileWriter(fullPath);          // local FS write — ephemeral
     *   writer.write("BookingID,GuestName,...\n");
     *   writer.write("BK-001,...\n");
     *   writer.write("BK-002,...\n");
     *   writer.close();
     * </pre>
     *
     * <p>After: CSV bytes are assembled in a {@link java.io.ByteArrayInputStream} and streamed
     * directly to Azure Blob Storage — no local file system dependency.</p>
     *
     * @param month the report month (e.g. "03")
     * @param year  the report year  (e.g. "2024")
     * @return a result map containing upload status, blob name, container, and blob URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0062 fix: blob name replaces the former local file path
        //   was: String fullPath = REPORT_BASE_PATH + fileName;  (local FS — ephemeral)
        //   now: blobName is the object key within Azure Blob Storage (durable)
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062 fix: Build CSV content in memory — eliminates local File/FileWriter usage.
            //   was: File reportDir = new File(REPORT_BASE_PATH);
            //        reportDir.mkdirs();
            //        FileWriter writer = new FileWriter(fullPath);   // <-- cr-java-0062 violation (line 42)
            //        writer.write(...); writer.close();
            //   now: StringBuilder + ByteArrayInputStream — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);
            InputStream dataStream = new ByteArrayInputStream(contentBytes);

            // Upload to Azure Blob Storage — durable across container restarts and scale events
            BlobContainerClient containerClient = getOrCreateContainer(reportsContainerName);
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.upload(dataStream, contentBytes.length, true);

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("container", reportsContainerName);
            result.put("blobUrl", blobClient.getBlobUrl());
            // cr-java-0077 fix: serverPort now reads from SERVER_PORT env var / Azure App Configuration
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Schedules a report generation task using Azure Service Bus scheduled message delivery.
     *
     * <p>cr-java-0111 fix (Clock/Time Dependencies): The original implementation used
     * {@code java.util.Timer} and server-local timezone settings for scheduling operations.
     * In distributed cloud environments (multiple containers / regions), server-local timers
     * are unreliable — each instance maintains its own independent timer, leading to duplicate
     * or missed executions on scale-out events, and timezone inconsistencies across regions.</p>
     *
     * <p>This method replaces local timer scheduling with Azure Service Bus scheduled message
     * delivery. The message is enqueued with an {@link OffsetDateTime} expressed in UTC
     * ({@link ZoneOffset#UTC}), ensuring timezone-agnostic, distributed, and durable
     * task execution regardless of the host server's local timezone setting.</p>
     *
     * <p>Before (original source line 70 — cr-java-0111 violation):</p>
     * <pre>
     *   // Server-local timezone — inconsistent across regions / containers
     *   String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
     * </pre>
     *
     * <p>After: UTC-based {@link OffsetDateTime} is used for all time references, and the
     * report task is delivered via Azure Service Bus scheduled message at the specified
     * UTC offset time — no dependency on server-local clock or timezone.</p>
     *
     * @param month          the report month (e.g. "03")
     * @param year           the report year  (e.g. "2024")
     * @param scheduledAtUtc the UTC time at which the report task should be delivered
     * @return a result map containing scheduling status and the Service Bus sequence number
     */
    public Map<String, Object> scheduleReportTask(String month, String year, OffsetDateTime scheduledAtUtc) {
        Map<String, Object> result = new HashMap<>();

        // cr-java-0111 fix: Use UTC-based OffsetDateTime instead of server-local new Date().
        // Ensures timezone-agnostic scheduling across all cloud regions and container instances.
        // was: String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        //      (server-local timezone — cr-java-0111 violation, original line 70)
        // now: OffsetDateTime.now(ZoneOffset.UTC) — explicit UTC, no server timezone dependency
        OffsetDateTime scheduledTime = (scheduledAtUtc != null)
                ? scheduledAtUtc.withOffsetSameInstant(ZoneOffset.UTC)
                : OffsetDateTime.now(ZoneOffset.UTC);

        String utcTimestamp = scheduledTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportQueueName)
                .buildClient()) {

            // Build the scheduled message payload — no server-local timezone reference
            String messageBody = String.format(
                    "{\"action\":\"generateMonthlyReport\",\"month\":\"%s\",\"year\":\"%s\",\"scheduledAtUtc\":\"%s\"}",
                    month, year, utcTimestamp);

            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            message.setMessageId("report-" + month + "-" + year + "-" + System.nanoTime());
            message.setContentType("application/json");

            // Schedule the message for delivery at the specified UTC time via Azure Service Bus.
            // Azure Service Bus guarantees at-least-once delivery across all consumer instances,
            // eliminating the duplicate/missed execution risk of per-instance java.util.Timer.
            long sequenceNumber = senderClient.scheduleMessage(message, scheduledTime);

            result.put("status", "scheduled");
            result.put("queue", reportQueueName);
            result.put("scheduledAtUtc", utcTimestamp);
            result.put("sequenceNumber", sequenceNumber);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name.
     *
     * <p>cr-java-0071 fix: Hard-coded environment URL replaced with value injected from
     * Azure App Configuration / application.properties (app.reports.download-base-url).
     * The base URL is now environment-agnostic and can be overridden per deployment
     * environment without any code changes, satisfying 12-factor app principle III (Config).</p>
     *
     * @param reportName the name of the report file
     * @return the full download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 fix: Hard-coded environment URL replaced with value injected from
        // Azure App Configuration / application.properties (app.reports.download-base-url).
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including storage container names, server port, and UTC timestamp.
     *
     * <p>cr-java-0111 fix: Server-local {@code SimpleDateFormat} + {@code new Date()} replaced
     * with {@link OffsetDateTime#now(ZoneOffset)} using {@link ZoneOffset#UTC}. This eliminates
     * the dependency on the host server's local timezone setting, ensuring consistent timestamp
     * generation across all cloud regions and container instances.</p>
     *
     * <p>Before (original source line 70 — cr-java-0111 violation):</p>
     * <pre>
     *   // Server-local timezone — inconsistent across regions / containers
     *   String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
     * </pre>
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix: Replace server-local SimpleDateFormat/new Date() with explicit UTC
        // OffsetDateTime. Eliminates server timezone dependency for distributed cloud deployments.
        // was: String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        //      (original source line 70 — cr-java-0111 violation)
        // now: OffsetDateTime.now(ZoneOffset.UTC) — timezone-agnostic, consistent across all instances
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC";

        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 / cr-java-0062 fix: replaced hard-coded REPORT_BASE_PATH / BACKUP_PATH
        // with Azure Blob Storage container references resolved from environment configuration.
        // No local file system paths are referenced — data is stored in Azure Blob Storage.
        info.put("reportsContainer", reportsContainerName);
        info.put("backupContainer", backupContainerName);
        // cr-java-0077 fix: serverPort now reads from SERVER_PORT env var / Azure App Configuration
        info.put("serverPort", serverPort);
        // cr-java-0111 fix: UTC timestamp — no server-local timezone dependency
        info.put("generatedAt", timestamp);
        return info;
    }
}
