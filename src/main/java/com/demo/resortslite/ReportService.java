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

/**
 * Service responsible for generating resort reports and persisting them to
 * Azure Blob Storage.
 *
 * <p>All local {@code java.io.File} / {@code java.io.FileWriter} operations
 * (cr-java-0063) have been replaced with Azure Blob Storage SDK calls so that
 * report data is durable, scalable, and cloud-native.</p>
 *
 * <p><strong>cr-java-0111 fix — Clock/Time Dependencies:</strong><br>
 * Server-local timezone usage ({@code new Date()} / {@code SimpleDateFormat}) and
 * {@code java.util.Timer}-based scheduling have been replaced with UTC-based
 * {@link OffsetDateTime} (timezone-agnostic) and Azure Service Bus scheduled message
 * delivery. This ensures consistent, distributed scheduling across multiple cloud
 * regions and container instances without relying on server-local clock settings.</p>
 */
@Service
public class ReportService {

    /**
     * Azure Blob Storage connection string injected from the
     * {@code AZURE_STORAGE_CONNECTION_STRING} environment variable via
     * {@code application.properties}.  Replaces the hard-coded local paths
     * {@code /var/legacy/reports/} and {@code C:\ResortBackups\nightly\}
     * (cr-java-0061 / cr-java-0062 / cr-java-0063).
     */
    @Value("${azure.storage.connection-string}")
    private String storageConnectionString;

    /** Azure Blob container used for monthly reports. */
    @Value("${azure.storage.reports.container-name:reports}")
    private String reportsContainerName;

    /** Azure Blob container used for nightly backups. */
    @Value("${azure.storage.backup.container-name:backups}")
    private String backupContainerName;

    /**
     * cr-java-0077 FIX: Hard-coded {@code SERVER_PORT = 8080} constant removed.
     * The server port is now externalised to Azure App Configuration and resolved
     * at runtime from the {@code SERVER_PORT} environment variable via the
     * {@code server.port} property in {@code application.properties}.
     * This enables dynamic port assignment required by Azure container
     * orchestration platforms (Azure Container Apps / AKS) and eliminates
     * deployment failures caused by static port binding.
     */
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * cr-java-0071 FIX: Hard-coded report download base URL replaced with a value injected
     * from Azure App Configuration via the 'app.reports.download-base-url' property key.
     * The property is externalised in application.properties and resolved at runtime from
     * the APP_REPORTS_DOWNLOAD_BASE_URL environment variable, enabling environment-agnostic
     * deployments without any code change between dev, staging, and production.
     */
    @Value("${app.reports.download-base-url}")
    private String reportsDownloadBaseUrl;

    /**
     * cr-java-0111 FIX: Azure Service Bus connection string injected from the
     * {@code AZURE_SERVICE_BUS_CONNECTION_STRING} environment variable via
     * {@code application.properties}. Used to send scheduled messages for
     * distributed, timezone-agnostic task execution instead of {@code java.util.Timer}.
     */
    @Value("${azure.servicebus.connection-string:}")
    private String serviceBusConnectionString;

    /**
     * cr-java-0111 FIX: Azure Service Bus queue name for scheduled report tasks,
     * injected from the {@code AZURE_SERVICE_BUS_REPORT_QUEUE} environment variable.
     */
    @Value("${azure.servicebus.report-queue-name:report-tasks}")
    private String reportQueueName;

    /**
     * cr-java-0111 FIX: UTC-based timestamp formatter replacing server-local
     * {@code SimpleDateFormat}. Uses ISO-8601 with explicit UTC offset to ensure
     * consistent timestamps across all cloud regions and container instances.
     */
    private static final DateTimeFormatter UTC_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * Builds and returns a {@link BlobContainerClient} for the given container name,
     * creating the container if it does not already exist.
     *
     * @param containerName the name of the Azure Blob Storage container
     * @return a ready-to-use {@link BlobContainerClient}
     */
    private BlobContainerClient getOrCreateContainer(String containerName) {
        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .connectionString(storageConnectionString)
                .buildClient();
        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }
        return containerClient;
    }

    /**
     * Generates a monthly resort report and uploads it to Azure Blob Storage.
     *
     * <p><strong>cr-java-0063 fix — Java.io.File Usage for Data Storage:</strong><br>
     * The following local file-system operations from the original source have been
     * replaced with Azure Blob Storage SDK calls:</p>
     * <ul>
     *   <li>Line 37 — {@code new File(REPORT_BASE_PATH)} (directory existence check on
     *       local disk) → container existence check via
     *       {@link BlobContainerClient#exists()}</li>
     *   <li>Line 39 — {@code reportDir.mkdirs()} (local directory creation) →
     *       {@link BlobContainerClient#create()} when the container does not exist</li>
     *   <li>Line 42 — {@code new FileWriter(fullPath)} + {@code writer.write(...)}
     *       (local file write to {@code /var/legacy/reports/}) → in-memory
     *       {@link ByteArrayInputStream} uploaded via
     *       {@link BlobClient#upload(InputStream, long, boolean)}</li>
     * </ul>
     *
     * <p>All CSV content is built in-memory using a {@link StringBuilder} and then
     * streamed to Azure Blob Storage, ensuring durability across container restarts
     * and horizontal scaling events without any local file-system dependency.</p>
     *
     * @param month the month for which the report is generated (e.g. {@code "03"})
     * @param year  the year for which the report is generated (e.g. {@code "2024"})
     * @return a result map containing {@code status}, {@code blobName},
     *         {@code container}, and {@code blobUrl}
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // ----------------------------------------------------------------
            // cr-java-0063 FIX (lines 37-42 of original source):
            //
            // BEFORE (local file-system — cloud-incompatible):
            //   File reportDir = new File(REPORT_BASE_PATH);   // line 37
            //   if (!reportDir.exists()) {                      // line 38
            //       reportDir.mkdirs();                         // line 39
            //   }
            //   FileWriter writer = new FileWriter(fullPath);   // line 42
            //   writer.write("BookingID,...\n");
            //   writer.close();
            //
            // AFTER (Azure Blob Storage — cloud-native):
            //   Build content in-memory, upload to Azure Blob Storage.
            // ----------------------------------------------------------------

            // Build CSV content in memory — no local file-system dependency.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // Replaces File.exists() + mkdirs() (lines 37-39): ensure container exists.
            BlobContainerClient containerClient = getOrCreateContainer(reportsContainerName);

            // Replaces new FileWriter(fullPath) + writer.write() (lines 42+):
            // upload content as a blob to Azure Blob Storage.
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            try (InputStream inputStream = new ByteArrayInputStream(contentBytes)) {
                blobClient.upload(inputStream, contentBytes.length, true);
            }

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("container", reportsContainerName);
            result.put("blobUrl", blobClient.getBlobUrl());
            // cr-java-0077 FIX: serverPort now resolved from environment variable
            // SERVER_PORT via application.properties instead of hard-coded 8080.
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds and returns the download URL for a given report name.
     *
     * <p><strong>cr-java-0071 FIX (line 66 of original source):</strong><br>
     * The hard-coded URL {@code "http://reports.resorts-internal.com:8080/download/"}
     * has been removed. The base URL is now resolved at runtime from the
     * {@code app.reports.download-base-url} property, which is backed by the
     * {@code APP_REPORTS_DOWNLOAD_BASE_URL} environment variable and can be overridden
     * per environment via Azure App Configuration without any code change.</p>
     *
     * @param reportName the name of the report file to download
     * @return the fully-qualified download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX (line 66): Hard-coded URL
        //   "http://reports.resorts-internal.com:8080/download/" + reportName
        // replaced with the externalized 'app.reports.download-base-url' property
        // injected from Azure App Configuration / environment variable.
        return reportsDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including Azure Blob Storage container references
     * instead of local file-system paths (cr-java-0061 / cr-java-0062 / cr-java-0063).
     *
     * <p><strong>cr-java-0111 FIX (line 70 of original source):</strong><br>
     * Server-local timezone usage via {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}
     * has been replaced with UTC-based {@link OffsetDateTime#now(ZoneOffset)} using an explicit
     * {@link ZoneOffset#UTC} offset. This eliminates timezone inconsistencies across cloud
     * regions and container instances where the JVM timezone may differ.</p>
     *
     * <p>Additionally, any {@code java.util.Timer}-based scheduling is replaced by sending
     * a scheduled message to Azure Service Bus, which provides distributed, timezone-agnostic
     * task execution across all cloud regions and container replicas.</p>
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (line 70):
        // BEFORE (server-local timezone — cloud-incompatible):
        //   String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        //
        // AFTER (UTC-based, timezone-agnostic — cloud-native):
        //   Use OffsetDateTime.now(ZoneOffset.UTC) with an explicit UTC formatter so that
        //   timestamps are consistent across all cloud regions and container instances
        //   regardless of the JVM's default timezone setting.
        String timestamp = UTC_FORMATTER.format(OffsetDateTime.now(ZoneOffset.UTC));

        Map<String, Object> info = new HashMap<>();
        info.put("reportsContainer", reportsContainerName);   // replaces REPORT_BASE_PATH (cr-java-0063)
        info.put("backupContainer", backupContainerName);     // replaces BACKUP_PATH (cr-java-0063)
        // cr-java-0077 FIX: serverPort now resolved from environment variable
        // SERVER_PORT via application.properties instead of hard-coded 8080.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Schedules a report generation task using Azure Service Bus scheduled message delivery.
     *
     * <p><strong>cr-java-0111 FIX — Replace local timers with Azure Service Bus Scheduled Messages:</strong><br>
     * Instead of using {@code java.util.Timer} or {@code java.util.TimerTask} (which rely on
     * server-local scheduling and are not distributed), this method sends a scheduled message
     * to an Azure Service Bus queue. Azure Service Bus delivers the message at the specified
     * UTC time, enabling distributed, timezone-agnostic task execution across multiple cloud
     * regions and container instances without any dependency on server-local clock settings.</p>
     *
     * @param taskPayload   the JSON payload describing the report task to execute
     * @param scheduledTime the UTC time at which Azure Service Bus should deliver the message
     * @return the sequence number of the scheduled Service Bus message
     */
    public long scheduleReportTask(String taskPayload, OffsetDateTime scheduledTime) {
        // cr-java-0111 FIX: Replace java.util.Timer with Azure Service Bus scheduled messages.
        //
        // BEFORE (server-local timer — cloud-incompatible):
        //   Timer timer = new Timer();
        //   timer.schedule(new TimerTask() {
        //       public void run() { generateMonthlyReport(...); }
        //   }, delay);
        //
        // AFTER (Azure Service Bus scheduled message — cloud-native):
        //   Send a scheduled message to Azure Service Bus queue. The message is delivered
        //   at the specified UTC time by the Azure Service Bus broker, ensuring consistent
        //   scheduling across all cloud regions and container replicas.
        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportQueueName)
                .buildClient()) {

            ServiceBusMessage message = new ServiceBusMessage(taskPayload);
            // Schedule the message for delivery at the specified UTC time.
            // Azure Service Bus handles timezone-agnostic delivery across all regions.
            long sequenceNumber = senderClient.scheduleMessage(message, scheduledTime);
            return sequenceNumber;
        }
    }
}
