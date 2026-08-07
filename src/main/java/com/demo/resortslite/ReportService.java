package com.demo.resortslite;

import com.azure.core.util.BinaryData;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private final String blobEndpoint;
    private final String reportsContainerName;
    private final String backupContainerName;
    private final String appConfigConnectionString;
    private final String reportDownloadBaseUrl;
    private final int configuredServerPort;
    private final String serviceBusConnectionString;
    private final String reportScheduleQueue;
    private final long reportScheduleDelayMinutes;

    public ReportService(
            @Value("${azure.storage.blob.endpoint:}") String blobEndpoint,
            @Value("${app.report.container-name:reports}") String reportsContainerName,
            @Value("${app.report.backup-container-name:report-backups}") String backupContainerName,
            @Value("${azure.app.configuration.connection-string:}") String appConfigConnectionString,
            @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}") String reportDownloadBaseUrl,
            @Value("${app.report.server-port:${SERVER_PORT:8080}}") int configuredServerPort,
            @Value("${azure.servicebus.connection-string:}") String serviceBusConnectionString,
            @Value("${app.report.schedule-queue:report-jobs}") String reportScheduleQueue,
            @Value("${app.report.schedule-delay-minutes:5}") long reportScheduleDelayMinutes) {
        this.blobEndpoint = blobEndpoint;
        this.reportsContainerName = reportsContainerName;
        this.backupContainerName = backupContainerName;
        this.appConfigConnectionString = appConfigConnectionString;
        this.reportDownloadBaseUrl = reportDownloadBaseUrl;
        this.configuredServerPort = configuredServerPort;
        this.serviceBusConnectionString = serviceBusConnectionString;
        this.reportScheduleQueue = reportScheduleQueue;
        this.reportScheduleDelayMinutes = reportScheduleDelayMinutes;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            BlobContainerClient reportContainer = getBlobContainerClient(reportsContainerName);
            reportContainer.createIfNotExists();
            reportContainer.getBlobClient(fileName)
                    .upload(BinaryData.fromString(reportContent), true);

            BlobContainerClient backupContainer = getBlobContainerClient(backupContainerName);
            backupContainer.createIfNotExists();
            backupContainer.getBlobClient(fileName)
                    .upload(BinaryData.fromBytes(reportContent.getBytes(StandardCharsets.UTF_8)), true);

            result.put("status", "generated");
            result.put("path", reportContainer.getBlobClient(fileName).getBlobUrl());
            result.put("serverPort", resolveConfiguredPort());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = getAppConfigurationValue("app.report.download-base-url", reportDownloadBaseUrl);
        return baseUrl + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportsContainerName);
        info.put("backupPath", backupContainerName);
        info.put("serverPort", resolveConfiguredPort());
        info.put("generatedAt", timestamp);
        return info;
    }

    public Map<String, Object> scheduleReportGeneration(String month) {
        Map<String, Object> response = new HashMap<>();
        try {
            ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                    .connectionString(serviceBusConnectionString)
                    .sender()
                    .queueName(reportScheduleQueue)
                    .buildClient();
            OffsetDateTime scheduledTime = OffsetDateTime.now(ZoneOffset.UTC)
                    .plus(Duration.ofMinutes(reportScheduleDelayMinutes));
            ServiceBusMessage message = new ServiceBusMessage("generate-report:" + month)
                    .setScheduledEnqueueTime(scheduledTime);
            senderClient.scheduleMessage(message, scheduledTime);
            senderClient.close();
            response.put("status", "scheduled");
            response.put("scheduledFor", scheduledTime.toString());
            response.put("queue", reportScheduleQueue);
        } catch (Exception ex) {
            response.put("status", "error");
            response.put("message", ex.getMessage());
        }
        return response;
    }

    private BlobContainerClient getBlobContainerClient(String containerName) {
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(blobEndpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        return blobServiceClient.getBlobContainerClient(containerName);
    }

    private String getAppConfigurationValue(String key, String fallback) {
        if (appConfigConnectionString == null || appConfigConnectionString.trim().isEmpty()) {
            return fallback;
        }
        try {
            ConfigurationClient client = new ConfigurationClientBuilder()
                    .connectionString(appConfigConnectionString)
                    .buildClient();
            String value = client.getConfigurationSetting(key, null).getValue();
            return value == null || value.trim().isEmpty() ? fallback : value;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private int resolveConfiguredPort() {
        String configuredPort = getAppConfigurationValue("app.report.server-port", String.valueOf(configuredServerPort));
        return Integer.parseInt(configuredPort);
    }
}
