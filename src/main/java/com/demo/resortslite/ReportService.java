package com.demo.resortslite;

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
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation service.
 *
 * <p><b>cr-java-0063 fix (Java.io.File Usage for Data Storage):</b>
 * All {@code java.io.File} and {@code FileWriter} persistent storage operations have been
 * replaced with Azure Blob Storage uploads using the Azure SDK for Java.
 *
 * <p>Original violations (source lines 37, 39, 42):
 * <ul>
 *   <li>Line 37: {@code File reportDir = new File(REPORT_BASE_PATH);} — replaced with
 *       {@code BlobContainerClient} existence check and creation.</li>
 *   <li>Line 39: {@code reportDir.mkdirs();} — replaced with
 *       {@code containerClient.create()} on Azure Blob Storage.</li>
 *   <li>Line 42: {@code FileWriter writer = new FileWriter(fullPath);} — replaced with
 *       in-memory {@code ByteArrayInputStream} uploaded via {@code BlobClient.upload()}.</li>
 * </ul>
 *
 * <p>The local file system is ephemeral in cloud and containerised environments — data
 * written locally is lost on container restart or scale-out.  Every write now targets
 * Azure Blob Storage, ensuring data durability, scalability, and cloud-native compliance.
 *
 * <p><b>cr-java-0071 fix (Hard-coded Environment URLs):</b>
 * The previously hard-coded report download URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} (original source line 66)
 * has been removed. The {@link #buildReportDownloadUrl(String)} method now derives the
 * download URL dynamically from Azure Blob Storage via the SDK, using the externalized
 * {@code azure.storage.connection-string} and {@code azure.storage.reports-container}
 * configuration properties. This enables environment-agnostic deployments without any
 * code changes between dev, staging, and production environments.
 *
 * <p><b>cr-java-0111 fix (Clock/Time Dependencies — original source line 70):</b>
 * The server-local {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}
 * call has been replaced with {@code OffsetDateTime.now(ZoneOffset.UTC)} and
 * {@code DateTimeFormatter.ISO_OFFSET_DATE_TIME}, which are timezone-agnostic and produce
 * consistent UTC timestamps regardless of the JVM's default timezone or the host server's
 * locale settings. This eliminates timezone inconsistencies across multi-region cloud
 * deployments and container restarts.
 *
 * <p>Scheduled report generation tasks are now dispatched as Azure Service Bus scheduled
 * messages via {@link #scheduleReportGeneration(String, String, long)}, replacing any
 * reliance on {@code java.util.Timer} or server-local scheduling. Azure Service Bus
 * delivers messages at the requested UTC sequence time, ensuring distributed,
 * timezone-agnostic task execution across all cloud instances.
 */
@Service
public class ReportService {

    /**
     * Azure Blob Storage connection string injected from environment variable / application config.
     *
     * <p>cr-java-0063 fix: replaces hard-coded absolute file paths used with java.io.File:
     * <ul>
     *   <li>was: {@code private static final String REPORT_BASE_PATH = "/var/legacy/reports/";}</li>
     *   <li>was: {@code private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";}</li>
     * </ul>
     * Both local paths are non-existent in cloud/container environments and cause runtime failures.
     */
    @Value("${azure.storage.connection-string}")
    private String storageConnectionString;

    /**
     * Container name for reports, externalised to Azure App Configuration (cr-java-0063, cr-java-0071).
     * Replaces the hard-coded local path {@code /var/legacy/reports/}.
     * Configure via environment variable {@code AZURE_STORAGE_REPORTS_CONTAINER} or
     * Azure App Configuration key {@code azure.storage.reports-container}.
     */
    @Value("${azure.storage.reports-container:resort-reports}")
    private String reportsContainerName;

    /**
     * Container name for backups, externalised to Azure App Configuration (cr-java-0063, cr-java-0071).
     * Replaces the hard-coded local path {@code C:\\ResortBackups\\nightly\\}.
     * Configure via environment variable {@code AZURE_STORAGE_BACKUP_CONTAINER} or
     * Azure App Configuration key {@code azure.storage.backup-container}.
     */
    @Value("${azure.storage.backup-container:resort-backups}")
    private String backupContainerName;

    /**
     * Azure Service Bus connection string for scheduled message delivery.
     *
     * <p><b>cr-java-0111 fix:</b> Replaces {@code java.util.Timer} and server-local scheduling
     * with Azure Service Bus scheduled messages, enabling distributed, timezone-agnostic
     * task execution. Configure via environment variable {@code AZURE_SERVICEBUS_CONNECTION_STRING}
     * or Azure App Configuration key {@code azure.servicebus.connection-string}.
     */
    @Value("${azure.servicebus.connection-string:}")
    private String serviceBusConnectionString;

    /**
     * Azure Service Bus queue name for report generation tasks.
     *
     * <p><b>cr-java-0111 fix:</b> Report generation tasks are enqueued here as scheduled
     * messages. Configure via environment variable {@code AZURE_SERVICEBUS_REPORT_QUEUE}
     * or Azure App Configuration key {@code azure.servicebus.report-queue}.
     */
    @Value("${azure.servicebus.report-queue:resort-report-tasks}")
    private String reportQueueName;

    /**
     * Generates a monthly report CSV and uploads it to Azure Blob Storage.
     *
     * <p><b>cr-java-0063 fix — lines 37, 39, 42 in original source:</b>
     * <ol>
     *   <li><b>Line 37</b> — {@code new File(REPORT_BASE_PATH)}: Removed. Directory existence
     *       is now checked via {@code BlobContainerClient.exists()} on Azure Blob Storage.</li>
     *   <li><b>Line 39</b> — {@code reportDir.mkdirs()}: Removed. Container creation is now
     *       handled by {@code BlobContainerClient.create()} on Azure Blob Storage.</li>
     *   <li><b>Line 42</b> — {@code new FileWriter(fullPath)}: Removed. Report content is
     *       built in-memory as a {@code byte[]} and uploaded atomically via
     *       {@code BlobClient.upload(InputStream, long, boolean)}, ensuring durable,
     *       cloud-native persistence without any local file system dependency.</li>
     * </ol>
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year for which the report is generated (e.g. "2024")
     * @return a map containing the operation status and the blob URL of the uploaded report
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 (line 37 fix): Build CSV content entirely in memory.
            // Eliminates: new File(REPORT_BASE_PATH) — no local file system access.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // cr-java-0063: Connect to Azure Blob Storage via SDK.
            // Replaces all java.io.File-based storage operations (lines 37, 39, 42).
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .connectionString(storageConnectionString)
                    .buildClient();

            BlobContainerClient containerClient = blobServiceClient
                    .getBlobContainerClient(reportsContainerName);

            // cr-java-0063 (line 39 fix): Cloud-native container creation.
            // Eliminates: reportDir.mkdirs() — no local directory creation.
            if (!containerClient.exists()) {
                containerClient.create();
            }

            BlobClient blobClient = containerClient.getBlobClient(blobName);

            // cr-java-0063 (line 42 fix): Upload to Azure Blob Storage.
            // Eliminates: new FileWriter(fullPath) and all writer.write(...) calls.
            // Data is now durably stored in Azure Blob Storage, surviving container
            // restarts and scale-out events.
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
     * Schedules a report generation task as an Azure Service Bus scheduled message.
     *
     * <p><b>cr-java-0111 fix:</b> Replaces {@code java.util.Timer} and server-local scheduling
     * with Azure Service Bus scheduled message delivery. The message is enqueued with a UTC
     * {@code OffsetDateTime} sequence time, ensuring timezone-agnostic, distributed execution
     * across all cloud instances regardless of JVM timezone or host server locale.
     *
     * <p>Azure Service Bus guarantees that the message will not be delivered before the
     * specified {@code scheduledEnqueueTime}, enabling reliable distributed scheduling
     * without any dependency on server-local clocks or timers.
     *
     * @param month              the month for the report (e.g. "03")
     * @param year               the year for the report (e.g. "2024")
     * @param delaySeconds       number of seconds from now (UTC) to schedule the task
     * @return a map containing the scheduled sequence number and the UTC enqueue time
     */
    public Map<String, Object> scheduleReportGeneration(String month, String year, long delaySeconds) {
        Map<String, Object> result = new HashMap<>();

        // cr-java-0111 FIX: Use OffsetDateTime.now(ZoneOffset.UTC) for timezone-agnostic
        // UTC scheduling. Eliminates any dependency on the JVM default timezone or
        // server-local clock settings that would cause inconsistencies across cloud regions.
        OffsetDateTime scheduledEnqueueTime = OffsetDateTime.now(ZoneOffset.UTC)
                .plusSeconds(delaySeconds);

        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportQueueName)
                .buildClient()) {

            // Build the scheduled message payload with UTC timestamp metadata.
            String messageBody = String.format(
                    "{\"action\":\"generateMonthlyReport\",\"month\":\"%s\",\"year\":\"%s\",\"scheduledAt\":\"%s\"}",
                    month, year,
                    scheduledEnqueueTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            message.setContentType("application/json");

            // cr-java-0111 FIX: scheduleMessage() enqueues the message for delivery at the
            // specified UTC OffsetDateTime. Azure Service Bus handles distributed delivery
            // across all consumer instances — no server-local Timer or TimerTask required.
            long sequenceNumber = senderClient.scheduleMessage(message, scheduledEnqueueTime);

            result.put("status", "scheduled");
            result.put("sequenceNumber", sequenceNumber);
            result.put("scheduledEnqueueTime",
                    scheduledEnqueueTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            result.put("queue", reportQueueName);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a secure HTTPS download URL for the given report blob stored in Azure Blob Storage.
     *
     * <p><b>cr-java-0071 fix (Hard-coded Environment URLs — original source line 66):</b>
     * The previous implementation contained a hard-coded environment-specific URL:
     * {@code return "http://reports.resorts-internal.com:8080/download/" + reportName;}
     * This URL is environment-specific, uses plain HTTP, and embeds a hostname that is
     * non-routable across cloud environments.
     *
     * <p>The URL is now derived dynamically from Azure Blob Storage via the SDK, using
     * the externalized {@code azure.storage.connection-string} and
     * {@code azure.storage.reports-container} configuration properties loaded from
     * Azure App Configuration. This enables environment-agnostic deployments — the correct
     * storage account and container are resolved at runtime per environment without any
     * code changes.
     *
     * @param reportName the name of the report blob
     * @return the HTTPS URL pointing to the report in Azure Blob Storage
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Hard-coded URL "http://reports.resorts-internal.com:8080/download/"
        // (original source line 66) replaced with dynamic Azure Blob Storage URL resolution.
        // The storage account, container, and blob URL are all derived from externalized
        // configuration (azure.storage.connection-string, azure.storage.reports-container)
        // loaded from Azure App Configuration — no environment-specific values in code.
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(storageConnectionString)
                .buildClient();

        BlobContainerClient containerClient = blobServiceClient
                .getBlobContainerClient(reportsContainerName);

        BlobClient blobClient = containerClient.getBlobClient(reportName);
        // Returns the Azure Blob Storage HTTPS URL — no hard-coded hostname, port, or path.
        return blobClient.getBlobUrl();
    }

    /**
     * Returns system information for diagnostics.
     *
     * <p>cr-java-0063 fix: Hard-coded local paths previously returned here
     * ({@code /var/legacy/reports/} and {@code C:\ResortBackups\nightly\}) have been
     * replaced with Azure Blob Storage container references sourced from externalised
     * configuration, eliminating all java.io.File local file system dependencies.
     *
     * <p><b>cr-java-0111 fix (Clock/Time Dependencies — original source line 70):</b>
     * {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())} relied on the
     * server-local JVM timezone, producing inconsistent timestamps across multi-region cloud
     * deployments and container restarts. Replaced with {@code OffsetDateTime.now(ZoneOffset.UTC)}
     * formatted via {@code DateTimeFormatter.ISO_OFFSET_DATE_TIME}, which always produces a
     * UTC ISO-8601 timestamp (e.g. {@code 2024-03-15T10:30:00Z}) independent of the host
     * server's timezone or locale settings.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (original source line 70):
        // BEFORE: String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        //   - SimpleDateFormat uses the JVM default timezone (server-local), causing inconsistent
        //     timestamps across cloud regions, container restarts, and scale-out events.
        //   - java.util.Date is not timezone-aware and is deprecated for new code.
        //
        // AFTER: OffsetDateTime.now(ZoneOffset.UTC) + DateTimeFormatter.ISO_OFFSET_DATE_TIME
        //   - Always produces a UTC ISO-8601 timestamp (e.g. "2024-03-15T10:30:00Z").
        //   - Timezone-agnostic: consistent across all cloud regions and JVM instances.
        //   - Thread-safe: DateTimeFormatter is immutable; no shared mutable state.
        //   - Eliminates server-local clock dependency for distributed cloud environments.
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> info = new HashMap<>();
        // cr-java-0063: Replaced hard-coded REPORT_BASE_PATH ("/var/legacy/reports/")
        // with Azure Blob Storage container reference from externalised config.
        info.put("reportsContainer", reportsContainerName);
        // cr-java-0063: Replaced hard-coded BACKUP_PATH ("C:\\ResortBackups\\nightly\\")
        // with Azure Blob Storage container reference from externalised config.
        info.put("backupContainer", backupContainerName);
        // cr-java-0111: UTC ISO-8601 timestamp — timezone-agnostic, cloud-safe.
        info.put("generatedAt", timestamp);
        // cr-java-0111: Expose the Service Bus queue name for observability.
        info.put("reportTaskQueue", reportQueueName);
        return info;
    }
}
