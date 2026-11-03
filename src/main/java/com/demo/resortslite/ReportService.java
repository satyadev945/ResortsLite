package com.demo.resortslite;

import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private static final String DEFAULT_BLOB_ENDPOINT_SUFFIX = "core.windows.net";
    private static final String DEFAULT_REPORT_CONTAINER = "reports";
    private static final String DEFAULT_BACKUP_CONTAINER = "report-backups";
    private static final String DEFAULT_REPORT_DOWNLOAD_BASE_URL = "https://reports.resorts-internal.com/download";

    // VIOLATION [Software Portability / High]: Fixed server port hardcoded in application logic.
    // Container orchestration (ECS / EKS) dynamically assigns ports. Hardcoded ports prevent
    // dynamic port binding required for modern container deployment and service discovery.
    private static final String SERVER_PORT = resolveServerPort(); // czr-port-001

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            BlobContainerClient reportContainerClient = getBlobContainerClient(resolveReportContainerName());
            BlobClient reportBlobClient = reportContainerClient.getBlobClient(fileName);
            byte[] reportBytes = reportContent.getBytes(StandardCharsets.UTF_8);
            reportBlobClient.upload(new ByteArrayInputStream(reportBytes), reportBytes.length, true);

            BlobContainerClient backupContainerClient = getBlobContainerClient(resolveBackupContainerName());
            BlobClient backupBlobClient = backupContainerClient.getBlobClient(fileName);
            backupBlobClient.upload(new ByteArrayInputStream(reportBytes), reportBytes.length, true);

            result.put("status", "generated");
            result.put("path", reportBlobClient.getBlobUrl());
            result.put("backupPath", backupBlobClient.getBlobUrl());
            result.put("serverPort", SERVER_PORT); // czr-port-001

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
        String baseUrl = getConfiguredValue("reports.download.base-url", DEFAULT_REPORT_DOWNLOAD_BASE_URL);
        return baseUrl.endsWith("/") ? baseUrl + reportName : baseUrl + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", buildContainerUrl(resolveReportContainerName()));
        info.put("backupPath", buildContainerUrl(resolveBackupContainerName()));
        info.put("serverPort", SERVER_PORT);        // czr-port-001
        info.put("generatedAt", timestamp);
        return info;
    }

    public Map<String, Object> scheduleMonthlyReport(String month, String year, OffsetDateTime scheduledTimeUtc) {
        OffsetDateTime normalizedScheduleTime = normalizeScheduledTime(scheduledTimeUtc);
        String payload = String.format("{\"month\":\"%s\",\"year\":\"%s\"}", month, year);

        Map<String, Object> result = new HashMap<>();

        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(getRequiredEnv("AZURE_SERVICE_BUS_CONNECTION_STRING"))
                .sender()
                .queueName(getEnvOrDefault("AZURE_SERVICE_BUS_REPORT_QUEUE", "monthly-report-jobs"))
                .buildClient()) {

            ServiceBusMessage scheduledMessage = new ServiceBusMessage(payload)
                    .setContentType("application/json")
                    .setSubject("monthly-report")
                    .setScheduledEnqueueTime(normalizedScheduleTime);

            long sequenceNumber = senderClient.scheduleMessage(scheduledMessage, normalizedScheduleTime);
            result.put("status", "scheduled");
            result.put("sequenceNumber", sequenceNumber);
            result.put("scheduledAtUtc", normalizedScheduleTime.toString());
        } catch (RuntimeException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    private static String resolveServerPort() {
        String configuredPort = System.getenv("SERVER_PORT");
        if (configuredPort == null || configuredPort.trim().isEmpty()) {
            configuredPort = System.getenv("AZURE_APP_CONFIGURATION_SERVER_PORT");
        }
        return (configuredPort == null || configuredPort.trim().isEmpty()) ? "8080" : configuredPort;
    }

    private OffsetDateTime normalizeScheduledTime(OffsetDateTime scheduledTimeUtc) {
        OffsetDateTime requestedTime = scheduledTimeUtc == null ? OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5) : scheduledTimeUtc;
        return requestedTime.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private BlobContainerClient getBlobContainerClient(String containerName) {
        String connectionString = getRequiredEnv("AZURE_STORAGE_CONNECTION_STRING");
        BlobContainerClient containerClient = new BlobContainerClientBuilder()
                .connectionString(connectionString)
                .containerName(containerName)
                .buildClient();

        if (!containerClient.exists()) {
            containerClient.create();
        }

        return containerClient;
    }

    private String resolveReportContainerName() {
        return getEnvOrDefault("AZURE_REPORTS_CONTAINER", DEFAULT_REPORT_CONTAINER);
    }

    private String resolveBackupContainerName() {
        return getEnvOrDefault("AZURE_REPORT_BACKUP_CONTAINER", DEFAULT_BACKUP_CONTAINER);
    }

    private String buildContainerUrl(String containerName) {
        String accountName = getEnvOrDefault("AZURE_STORAGE_ACCOUNT_NAME", "local-account");
        String endpointSuffix = getEnvOrDefault("AZURE_STORAGE_ENDPOINT_SUFFIX", DEFAULT_BLOB_ENDPOINT_SUFFIX);
        return "https://" + accountName + ".blob." + endpointSuffix + "/" + containerName;
    }

    private String getRequiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }

    private String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.trim().isEmpty()) ? defaultValue : value;
    }

    private String getConfiguredValue(String key, String defaultValue) {
        String connectionString = System.getenv("AZURE_APP_CONFIGURATION_CONNECTION_STRING");
        if (connectionString == null || connectionString.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            ConfigurationClient configurationClient = new ConfigurationClientBuilder()
                    .connectionString(connectionString)
                    .buildClient();
            String configuredValue = configurationClient.getConfigurationSetting(key, null).getValue();
            return (configuredValue == null || configuredValue.trim().isEmpty()) ? defaultValue : configuredValue;
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }
}
