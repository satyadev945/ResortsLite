package com.demo.resortslite;

import com.azure.core.credential.TokenCredential;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.ServiceBusMessage;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private final String storageConnectionString;
    private final String storageEndpoint;
    private final String reportsContainerName;
    private final String backupContainerName;
    private final String appConfigConnectionString;
    private final String appConfigEndpoint;
    private final String configuredServerPort;
    private final String configuredDownloadBaseUrl;
    private final String serviceBusConnectionString;
    private final String serviceBusQueueName;
    private final long scheduleDelaySeconds;

    public ReportService(
            @Value("${app.report.storage.connection-string:}") String storageConnectionString,
            @Value("${app.report.storage.endpoint:}") String storageEndpoint,
            @Value("${app.report.container-name:reports}") String reportsContainerName,
            @Value("${app.report.backup-container-name:report-backups}") String backupContainerName,
            @Value("${app.config.connection-string:}") String appConfigConnectionString,
            @Value("${app.config.endpoint:}") String appConfigEndpoint,
            @Value("${server.port:8080}") String configuredServerPort,
            @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}") String configuredDownloadBaseUrl,
            @Value("${app.servicebus.connection-string:}") String serviceBusConnectionString,
            @Value("${app.servicebus.queue-name:report-jobs}") String serviceBusQueueName,
            @Value("${app.servicebus.schedule-delay-seconds:300}") long scheduleDelaySeconds) {
        this.storageConnectionString = storageConnectionString;
        this.storageEndpoint = storageEndpoint;
        this.reportsContainerName = reportsContainerName;
        this.backupContainerName = backupContainerName;
        this.appConfigConnectionString = appConfigConnectionString;
        this.appConfigEndpoint = appConfigEndpoint;
        this.configuredServerPort = configuredServerPort;
        this.configuredDownloadBaseUrl = configuredDownloadBaseUrl;
        this.serviceBusConnectionString = serviceBusConnectionString;
        this.serviceBusQueueName = serviceBusQueueName;
        this.scheduleDelaySeconds = scheduleDelaySeconds;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            BlobContainerClient reportContainer = getContainerClient(reportsContainerName);
            reportContainer.getBlobClient(fileName).upload(
                    new ByteArrayInputStream(reportContent.getBytes(StandardCharsets.UTF_8)),
                    reportContent.getBytes(StandardCharsets.UTF_8).length,
                    true);

            BlobContainerClient backupContainer = getContainerClient(backupContainerName);
            backupContainer.getBlobClient(fileName).upload(
                    new ByteArrayInputStream(reportContent.getBytes(StandardCharsets.UTF_8)),
                    reportContent.getBytes(StandardCharsets.UTF_8).length,
                    true);

            result.put("status", "generated");
            result.put("path", reportContainer.getBlobClient(fileName).getBlobUrl());
            result.put("backupPath", backupContainer.getBlobClient(fileName).getBlobUrl());
            result.put("serverPort", resolveServerPort());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        return resolveDownloadBaseUrl() + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> info = new HashMap<>();
        info.put("reportContainer", reportsContainerName);
        info.put("backupContainer", backupContainerName);
        info.put("serverPort", resolveServerPort());
        info.put("generatedAt", timestamp);
        return info;
    }

    public void scheduleReportGeneration(String month, String year) {
        if (serviceBusConnectionString == null || serviceBusConnectionString.trim().isEmpty()) {
            return;
        }

        ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(serviceBusQueueName)
                .buildClient();

        try {
            Instant scheduledTime = Instant.now().plus(Duration.ofSeconds(scheduleDelaySeconds));
            ServiceBusMessage message = new ServiceBusMessage("generate-report:" + month + ":" + year)
                    .setScheduledEnqueueTime(scheduledTime);
            senderClient.scheduleMessage(message, scheduledTime);
        } finally {
            senderClient.close();
        }
    }

    private BlobContainerClient getContainerClient(String containerName) {
        BlobServiceClient serviceClient;
        if (storageConnectionString != null && !storageConnectionString.trim().isEmpty()) {
            serviceClient = new BlobServiceClientBuilder()
                    .connectionString(storageConnectionString)
                    .buildClient();
        } else {
            TokenCredential credential = new DefaultAzureCredentialBuilder().build();
            serviceClient = new BlobServiceClientBuilder()
                    .endpoint(storageEndpoint)
                    .credential(credential)
                    .buildClient();
        }

        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }
        return containerClient;
    }

    private String resolveServerPort() {
        return getAppConfigurationValue("server.port", configuredServerPort);
    }

    private String resolveDownloadBaseUrl() {
        return getAppConfigurationValue("app.report.download-base-url", configuredDownloadBaseUrl);
    }

    private String getAppConfigurationValue(String key, String fallback) {
        try {
            if (appConfigConnectionString != null && !appConfigConnectionString.trim().isEmpty()) {
                ConfigurationClient client = new ConfigurationClientBuilder()
                        .connectionString(appConfigConnectionString)
                        .buildClient();
                return client.getConfigurationSetting(key, null).getValue();
            }
            if (appConfigEndpoint != null && !appConfigEndpoint.trim().isEmpty()) {
                ConfigurationClient client = new ConfigurationClientBuilder()
                        .endpoint(appConfigEndpoint)
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .buildClient();
                return client.getConfigurationSetting(key, null).getValue();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
