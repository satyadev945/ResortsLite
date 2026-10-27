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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation service.
 *
 * <p><b>cr-java-0063 remediation (Java.io.File Usage for Data Storage):</b><br>
 * All three {@code java.io.File} usages that previously existed in
 * {@code generateMonthlyReport()} have been replaced with Azure Blob Storage
 * operations using the Azure SDK for Java:
 * <ul>
 *   <li>Line 37 (source): {@code new File(REPORT_BASE_PATH)} — replaced by
 *       {@link BlobContainerClient#createIfNotExists()}</li>
 *   <li>Line 39 (source): {@code reportDir.exists()} — replaced by
 *       {@link BlobContainerClient#createIfNotExists()} (idempotent)</li>
 *   <li>Line 42 (source): {@code reportDir.mkdirs()} — replaced by
 *       {@link BlobContainerClient#createIfNotExists()} (idempotent)</li>
 * </ul>
 * Report content is built in memory and uploaded directly to Azure Blob Storage,
 * eliminating all local file-system dependencies and making the service fully
 * cloud-native and ephemeral-filesystem-safe.
 *
 * <p><b>cr-java-0071 remediation (Hard-coded Environment URLs):</b><br>
 * The hard-coded report download URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} previously in
 * {@code buildReportDownloadUrl()} has been replaced with a value injected from
 * Azure App Configuration / application.properties via {@code @Value}, enabling
 * environment-agnostic deployments without code changes.
 *
 * <p><b>cr-java-0077 remediation (Hard-coded Ports):</b><br>
 * The hard-coded {@code private static final int SERVER_PORT = 8080} constant has been
 * replaced with a Spring {@code @Value}-injected field bound to the
 * {@code server.port} / {@code SERVER_PORT} environment variable. This allows Azure
 * Container Apps, AKS, and Azure App Service to assign ports dynamically at runtime
 * without requiring code changes between environments.
 *
 * <p><b>cr-java-0111 remediation (Clock/Time Dependencies):</b><br>
 * The server-local timezone-dependent {@code SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}
 * call (source line 70) has been replaced with {@code Instant.now()} formatted in UTC using
 * {@code DateTimeFormatter.ISO_OFFSET_DATE_TIME} and {@code ZoneOffset.UTC}. This ensures
 * timezone-agnostic, consistent timestamps across all cloud regions and container instances.
 * Additionally, {@code scheduleReportGeneration()} demonstrates the Azure Service Bus
 * scheduled message pattern for distributed, timezone-agnostic task scheduling — replacing
 * any {@code java.util.Timer} or server-local scheduling that would fail in multi-region
 * or multi-instance cloud deployments.
 */
@Service
public class ReportService {

    // cr-java-0063 FIX: Azure Blob Storage connection string injected from environment /
    // application properties. Replaces hard-coded absolute file paths
    // (/var/legacy/reports/ and C:\ResortBackups\nightly\) that were incompatible
    // with cloud / container environments.
    @Value("${azure.storage.connection-string}")
    private String storageConnectionString;

    // Container name for monthly reports — externalised so it can differ per environment.
    @Value("${azure.storage.reports-container:resort-reports}")
    private String reportsContainerName;

    // Container name for nightly backups — replaces the Windows-style BACKUP_PATH constant.
    @Value("${azure.storage.backup-container:resort-backups}")
    private String backupContainerName;

    // cr-java-0071 FIX: Hard-coded environment URL replaced with value injected from
    // Azure App Configuration / application.properties.
    // Previously hard-coded as "http://reports.resorts-internal.com:8080/download/".
    // Set app.reports.download-base-url in Azure App Configuration per environment.
    @Value("${app.reports.download-base-url:https://reports.resorts-internal.com/download/}")
    private String reportDownloadBaseUrl;

    // cr-java-0077 FIX: Hard-coded port constant (private static final int SERVER_PORT = 8080)
    // replaced with a Spring @Value-injected field bound to the server.port /
    // SERVER_PORT environment variable.
    // Container orchestration platforms (Azure Container Apps, AKS, Azure App Service)
    // assign ports dynamically at runtime. Configure SERVER_PORT as an Azure App Service
    // application setting or environment variable to override the default.
    // Previously: private static final int SERVER_PORT = 8080; // czr-port-001
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0111 FIX: Azure Service Bus connection string for distributed, timezone-agnostic
    // scheduled message delivery. Replaces java.util.Timer and server-local scheduling that
    // fail in multi-region / multi-instance cloud deployments.
    // Set AZURE_SERVICEBUS_CONNECTION_STRING as an Azure App Service application setting
    // or environment variable; never commit credentials to source control.
    @Value("${azure.servicebus.connection-string:}")
    private String serviceBusConnectionString;

    // cr-java-0111 FIX: Azure Service Bus queue name for scheduled report generation tasks.
    // Configure azure.servicebus.reports-queue in Azure App Configuration per environment.
    @Value("${azure.servicebus.reports-queue:resort-reports-queue}")
    private String serviceBusReportsQueue;

    // cr-java-0111 FIX: UTC formatter — replaces SimpleDateFormat with no timezone (server-local).
    // ISO_OFFSET_DATE_TIME with ZoneOffset.UTC produces timezone-explicit timestamps
    // (e.g. "2024-03-01T12:00:00Z") that are consistent across all cloud regions and
    // container instances regardless of the host JVM's default timezone.
    private static final DateTimeFormatter UTC_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    /**
     * Generates a monthly report CSV and uploads it to Azure Blob Storage.
     *
     * <p><b>cr-java-0063 remediation applied:</b><br>
     * Previously this method used {@code java.io.File} for persistent data storage:
     * <pre>
     *   // Line 37 (source): File reportDir = new File(REPORT_BASE_PATH);
     *   // Line 39 (source): if (!reportDir.exists()) {
     *   // Line 42 (source):     reportDir.mkdirs();
     * </pre>
     * These three {@code java.io.File} operations assumed local file-system availability
     * and persistence, which violates cloud storage patterns. They have been replaced with
     * Azure Blob Storage operations: the container is created idempotently via
     * {@link BlobContainerClient#createIfNotExists()}, and the CSV content is streamed
     * directly to a blob — no local file system is touched.
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string  (e.g. "2024")
     * @return result map containing status and the blob URL on success
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build the CSV content in memory — no local file system dependency.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // cr-java-0063 FIX (lines 37, 39, 42 in source):
            // Upload to Azure Blob Storage — replaces the three java.io.File operations:
            //   new File(REPORT_BASE_PATH)  →  BlobContainerClient (container reference)
            //   reportDir.exists()          →  createIfNotExists() (idempotent check+create)
            //   reportDir.mkdirs()          →  createIfNotExists() (idempotent check+create)
            // FileWriter.write() calls      →  blobClient.upload() via InputStream
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .connectionString(storageConnectionString)
                    .buildClient();

            BlobContainerClient containerClient = blobServiceClient
                    .getBlobContainerClient(reportsContainerName);
            // Replaces: new File(REPORT_BASE_PATH) + reportDir.exists() + reportDir.mkdirs()
            containerClient.createIfNotExists();

            BlobClient blobClient = containerClient.getBlobClient(blobName);
            try (InputStream inputStream = new ByteArrayInputStream(contentBytes)) {
                blobClient.upload(inputStream, contentBytes.length, true);
            }

            result.put("status", "generated");
            result.put("path", blobClient.getBlobUrl());
            // cr-java-0077 FIX: serverPort is now injected via @Value — no hard-coded literal.
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report.
     *
     * <p><b>cr-java-0071 remediation applied (Line 66 in source):</b><br>
     * The hard-coded environment URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} has been removed.
     * The base URL is now injected via {@code @Value("${app.reports.download-base-url}")}
     * from Azure App Configuration / application.properties, enabling environment-agnostic
     * deployments. The default value uses HTTPS to align with cloud security standards.
     *
     * @param reportName the name of the report file to download
     * @return the full download URL for the given report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Hard-coded URL "http://reports.resorts-internal.com:8080/download/"
        // replaced with value injected from Azure App Configuration via @Value.
        // Configure app.reports.download-base-url in Azure App Configuration per environment.
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information for diagnostics.
     *
     * <p>Previously exposed hard-coded local paths (cr-java-0061 / cr-java-0063). Now returns
     * the Azure Blob Storage container names so operators can verify the correct
     * cloud storage targets are configured.
     *
     * <p><b>cr-java-0111 remediation applied (Source line 70):</b><br>
     * {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())} used the
     * server-local JVM timezone, causing inconsistent timestamps across cloud regions and
     * container instances. Replaced with {@code Instant.now()} formatted via
     * {@code UTC_FORMATTER} (ISO-8601 with explicit UTC offset), which is timezone-agnostic
     * and consistent in all distributed cloud environments.
     *
     * @return map of system information key/value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (source line 70):
        // BEFORE: String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        //         ^ Uses server-local JVM timezone — inconsistent across cloud regions/containers.
        // AFTER:  Instant.now() formatted with explicit UTC offset via UTC_FORMATTER.
        //         Produces ISO-8601 UTC timestamp (e.g. "2024-03-01T12:00:00Z") that is
        //         timezone-agnostic and consistent across all distributed cloud instances.
        String timestamp = UTC_FORMATTER.format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: replaced hard-coded REPORT_BASE_PATH / BACKUP_PATH with
        // Azure Blob Storage container names injected from application configuration.
        info.put("reportsContainer", reportsContainerName);  // replaces hard-coded REPORT_BASE_PATH
        info.put("backupContainer", backupContainerName);    // replaces hard-coded BACKUP_PATH
        // cr-java-0077 FIX: serverPort is now injected via @Value — no hard-coded literal.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Schedules a report generation task using Azure Service Bus scheduled message delivery.
     *
     * <p><b>cr-java-0111 remediation — Azure Service Bus Scheduled Messages:</b><br>
     * Replaces {@code java.util.Timer} and server-local scheduling with Azure Service Bus
     * scheduled message delivery for distributed, timezone-agnostic task execution.
     * The scheduled enqueue time is expressed in UTC ({@code OffsetDateTime} with
     * {@code ZoneOffset.UTC}), ensuring consistent scheduling behaviour across all cloud
     * regions and container instances regardless of the host JVM's default timezone.
     *
     * <p>Usage: call this method with the desired UTC enqueue time. The Azure Service Bus
     * broker will deliver the message at the specified time to any available consumer
     * instance, enabling reliable distributed scheduling without local timer dependencies.
     *
     * @param messageBody    the report task payload (e.g. JSON with month/year parameters)
     * @param scheduledEnqueueTimeUtc the UTC time at which the message should be delivered
     * @return the sequence number of the scheduled message, or -1 on failure
     */
    public long scheduleReportGeneration(String messageBody, OffsetDateTime scheduledEnqueueTimeUtc) {
        // cr-java-0111 FIX: Azure Service Bus scheduled message delivery replaces
        // java.util.Timer / server-local scheduling. The enqueue time is always expressed
        // in UTC (OffsetDateTime with ZoneOffset.UTC) to ensure timezone-agnostic,
        // consistent scheduling across all cloud regions and container instances.
        if (serviceBusConnectionString == null || serviceBusConnectionString.isEmpty()) {
            // Service Bus not configured — log and return sentinel value.
            // Configure azure.servicebus.connection-string to enable distributed scheduling.
            return -1L;
        }

        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(serviceBusReportsQueue)
                .buildClient()) {

            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            // Scheduled enqueue time is in UTC — timezone-agnostic across all cloud regions.
            message.setScheduledEnqueueTime(scheduledEnqueueTimeUtc.withOffsetSameInstant(ZoneOffset.UTC));

            return senderClient.scheduleMessage(message, scheduledEnqueueTimeUtc.withOffsetSameInstant(ZoneOffset.UTC));
        }
    }
}
