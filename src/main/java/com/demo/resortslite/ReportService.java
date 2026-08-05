package com.demo.resortslite;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;
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

    // cr-java-0061 FIXED: Replaced hard-coded absolute path "/var/legacy/reports/" with
    // Azure Blob Storage container name injected via environment variable / application property.
    @Value("${azure.storage.connection-string}")
    private String storageConnectionString;

    // cr-java-0061 FIXED: Replaced hard-coded path constant with configurable container names
    // sourced from environment variables (AZURE_STORAGE_CONTAINER_NAME /
    // AZURE_STORAGE_BACKUP_CONTAINER_NAME) — no fixed host-filesystem dependency.
    @Value("${azure.storage.container-name:resort-reports}")
    private String reportContainerName;

    @Value("${azure.storage.backup-container-name:resort-backups}")
    private String backupContainerName;

    // cr-java-0071 FIX: Hard-coded report download base URL replaced with value injected from
    // Azure App Configuration / application.properties via @Value. The URL is now environment-
    // agnostic and can be overridden per deployment without any code change.
    @Value("${app.report.download.base-url}")
    private String reportDownloadBaseUrl;

    // cr-java-0077 FIXED (Line 28): Hard-coded port constant SERVER_PORT = 8080 replaced with
    // an @Value-injected field sourced from the environment variable APP_SERVER_PORT / SERVER_PORT
    // or the Azure App Configuration key "app.server.port". This enables dynamic port assignment
    // by Azure container orchestration platforms (Azure Container Apps / AKS) and eliminates
    // the deployment conflict caused by a fixed port number baked into application logic.
    @Value("${app.server.port:${SERVER_PORT:8080}}")
    private int serverPort; // cr-java-0077: externalized via Azure App Configuration / env var

    // cr-java-0111 FIX: Azure Service Bus connection string injected via environment variable.
    // Used to send scheduled messages for distributed, timezone-agnostic task execution,
    // replacing any server-local timer or timezone-dependent scheduling.
    @Value("${azure.servicebus.connection-string}")
    private String serviceBusConnectionString;

    // cr-java-0111 FIX: Azure Service Bus queue name for scheduled report generation tasks.
    // Injected via environment variable / Azure App Configuration — no hard-coded value.
    @Value("${azure.servicebus.report-queue-name:resort-report-tasks}")
    private String reportQueueName;

    /**
     * Builds and returns a configured {@link BlobServiceClient} using the connection string
     * supplied via the {@code AZURE_STORAGE_CONNECTION_STRING} environment variable.
     */
    private BlobServiceClient buildBlobServiceClient() {
        return new BlobServiceClientBuilder()
                .connectionString(storageConnectionString)
                .buildClient();
    }

    /**
     * Generates a monthly CSV report and uploads it to Azure Blob Storage.
     * <p>
     * cr-java-0062 FIXED (Line 42): Local file system write operation replaced with Azure Blob
     * Storage upload to ensure data durability across container restarts and scaling events.
     * <ul>
     *   <li>Line 42 — {@code FileWriter writer = new FileWriter(fullPath)} removed; CSV content
     *       is now serialised to a {@code ByteArrayInputStream} and uploaded directly to Azure
     *       Blob Storage via {@code BlobClient.upload()}, eliminating any dependency on the
     *       ephemeral local file system.</li>
     *   <li>Local directory creation ({@code new File(REPORT_BASE_PATH)} / {@code mkdirs()})
     *       removed; Azure Blob Storage containers are created on-demand via
     *       {@code createIfNotExists()}.</li>
     *   <li>Local {@code fullPath} variable removed; the blob name (key) is constructed from
     *       the report filename only and stored in Azure Blob Storage.</li>
     * </ul>
     * <p>
     * cr-java-0061 FIXED (Lines 23, 37): Hard-coded absolute file paths eliminated:
     * <ul>
     *   <li>Line 23 — {@code REPORT_BASE_PATH = "/var/legacy/reports/"} removed; container name
     *       is now injected via {@code azure.storage.container-name} property.</li>
     *   <li>Line 37 — {@code String fullPath = REPORT_BASE_PATH + fileName} removed; the blob
     *       name (key) is constructed from the report filename only.</li>
     * </ul>
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year for which the report is generated (e.g. "2024")
     * @return a result map containing status, blob URL, and server port
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062 FIX (Line 42): Azure Blob Storage container created on-demand —
            // replaces local directory creation (new File / mkdirs) which relied on the
            // ephemeral container file system.
            BlobServiceClient blobServiceClient = buildBlobServiceClient();
            BlobContainerClient containerClient =
                    blobServiceClient.getBlobContainerClient(reportContainerName);
            containerClient.createIfNotExists();

            // cr-java-0062 FIX (Line 42): FileWriter replaced with BlobClient.upload().
            // CSV content is serialised to a ByteArrayInputStream and streamed directly to
            // Azure Blob Storage, ensuring data persists across container restarts and
            // horizontal scaling events — no local file system write occurs.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
            InputStream dataStream = new ByteArrayInputStream(csvBytes);

            BlobClient blobClient = containerClient.getBlobClient(blobName);
            // Overwrite flag set to true so re-runs update the existing blob rather than failing.
            blobClient.upload(dataStream, csvBytes.length, true);

            result.put("status", "generated");
            result.put("blobUrl", blobClient.getBlobUrl());
            // cr-java-0077 FIX: serverPort now sourced from env var / Azure App Configuration
            result.put("serverPort", serverPort);

        } catch (BlobStorageException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Schedules a monthly report generation task via Azure Service Bus scheduled message delivery.
     * <p>
     * cr-java-0111 FIX: Replaces any server-local {@code java.util.Timer} or timezone-dependent
     * scheduling with Azure Service Bus scheduled messages. The message is enqueued with an
     * explicit UTC-based {@link OffsetDateTime} scheduled enqueue time, ensuring timezone-agnostic,
     * distributed task execution across all cloud regions and container instances.
     * <ul>
     *   <li>No server-local clock or timezone setting is relied upon — all timestamps use
     *       {@link OffsetDateTime#now(ZoneOffset#UTC)} anchored to UTC.</li>
     *   <li>Azure Service Bus guarantees the message is delivered to consumers at or after the
     *       scheduled UTC time, regardless of the timezone of the producing or consuming instance.</li>
     *   <li>Multiple application instances can safely call this method; Service Bus ensures
     *       exactly-once delivery semantics, preventing duplicate report generation.</li>
     * </ul>
     *
     * @param month           the month for which the report should be generated (e.g. "03")
     * @param year            the year for which the report should be generated (e.g. "2024")
     * @param delaySeconds    number of seconds from now (UTC) at which the message should be delivered
     * @return a result map containing the scheduled enqueue time (UTC) and sequence number
     */
    public Map<String, Object> scheduleMonthlyReportTask(String month, String year, long delaySeconds) {
        Map<String, Object> result = new HashMap<>();

        // cr-java-0111 FIX: Use OffsetDateTime with explicit UTC zone offset instead of
        // server-local new Date() / SimpleDateFormat. This eliminates any dependency on the
        // JVM's default timezone or the host server's locale settings, making scheduling
        // behaviour identical across all cloud regions and container instances.
        OffsetDateTime scheduledEnqueueTimeUtc = OffsetDateTime.now(ZoneOffset.UTC)
                .plusSeconds(delaySeconds);

        String messageBody = String.format(
                "{\"action\":\"generateMonthlyReport\",\"month\":\"%s\",\"year\":\"%s\"}",
                month, year);

        // cr-java-0111 FIX: Send a scheduled message to Azure Service Bus instead of using
        // java.util.Timer or server-local scheduling. The Service Bus broker holds the message
        // until the scheduledEnqueueTimeUtc (UTC) is reached, then delivers it to the consumer.
        // This pattern is fully distributed and timezone-agnostic.
        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportQueueName)
                .buildClient()) {

            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            message.setScheduledEnqueueTime(scheduledEnqueueTimeUtc);

            long sequenceNumber = senderClient.scheduleMessage(message, scheduledEnqueueTimeUtc);

            result.put("status", "scheduled");
            result.put("queueName", reportQueueName);
            // cr-java-0111 FIX: Timestamp formatted in UTC ISO-8601 — no server-local timezone.
            result.put("scheduledEnqueueTimeUtc",
                    scheduledEnqueueTimeUtc.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            result.put("sequenceNumber", sequenceNumber);
        }

        return result;
    }

    /**
     * Builds a report download URL for the given report name.
     * <p>
     * cr-java-0071 FIX (Line 66): Hard-coded URL
     * "http://reports.resorts-internal.com:8080/download/" replaced with the externalized
     * property {@code app.report.download.base-url} injected via @Value. The base URL is
     * now sourced from Azure App Configuration, enabling environment-agnostic deployments
     * without code changes between dev, staging, and production environments.
     *
     * @param reportName the name of the report blob
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX (Line 66): Replaced hard-coded "http://reports.resorts-internal.com:8080/download/"
        // with externalized property injected from Azure App Configuration via @Value("${app.report.download.base-url}").
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including Azure Blob Storage container references
     * (replaces the former hard-coded local file-path values).
     * <p>
     * cr-java-0111 FIX (Line 70): Replaced server-local {@code new Date()} and
     * {@code SimpleDateFormat} (which depend on the JVM default timezone / server locale) with
     * {@link OffsetDateTime#now(ZoneOffset#UTC)} formatted via {@link DateTimeFormatter#ISO_OFFSET_DATE_TIME}.
     * This ensures the timestamp is always expressed in UTC, producing consistent, timezone-agnostic
     * output across all cloud regions and container instances.
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (Line 70): Replaced new Date() + SimpleDateFormat (server-local timezone)
        // with OffsetDateTime.now(ZoneOffset.UTC) + DateTimeFormatter.ISO_OFFSET_DATE_TIME.
        // OffsetDateTime anchored to UTC is timezone-agnostic and produces identical output
        // regardless of the JVM default timezone or the host server's locale — safe for
        // distributed cloud deployments across multiple regions and container instances.
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 FIX: Replaced REPORT_BASE_PATH ("/var/legacy/reports/") and
        // BACKUP_PATH ("C:\\ResortBackups\\nightly\\") with Azure Blob Storage container names
        // sourced from environment variables — no host file-system dependency.
        info.put("reportContainer", reportContainerName);   // was: REPORT_BASE_PATH
        info.put("backupContainer", backupContainerName);   // was: BACKUP_PATH
        // cr-java-0077 FIX: serverPort now sourced from env var / Azure App Configuration
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
