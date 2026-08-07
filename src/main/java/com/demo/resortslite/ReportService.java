package com.demo.resortslite;

import com.azure.core.credential.AzureNamedKeyCredential;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
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

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    @Value("${app.report.container-name:reports}")
    private String reportContainerName;

    @Value("${app.report.backup-container-name:report-backups}")
    private String backupContainerName;

    @Value("${app.report.storage-connection-string:}")
    private String storageConnectionString;

    @Value("${app.report.storage-endpoint:}")
    private String storageEndpoint;

    @Value("${app.report.download-base-url}")
    private String reportDownloadBaseUrl;

    @Value("${app.report.server-port:8080}")
    private String configuredServerPort;

    @Value("${app.config.connection-string:}")
    private String appConfigConnectionString;

    @Value("${app.config.report-download-url-key:app.report.download-base-url}")
    private String reportDownloadUrlKey;

    @Value("${app.config.report-port-key:app.report.server-port}")
    private String reportPortKey;

    @Value("${app.report.schedule-queue:report-jobs}")
    private String reportScheduleQueue;

    @Value("${app.report.schedule-delay-minutes:5}")
    private long reportScheduleDelayMinutes;

    @Value("${AZURE_SERVICEBUS_CONNECTION_STRING:}")
    private String serviceBusConnectionString;

    private BlobContainerClient reportContainerClient;
    private BlobContainerClient backupContainerClient;
    private ConfigurationClient configurationClient;

    @PostConstruct
    public void initializeClients() {
        BlobServiceClient blobServiceClient = buildBlobServiceClient();
        if (blobServiceClient != null) {
            reportContainerClient = blobServiceClient.getBlobContainerClient(reportContainerName);
            if (!reportContainerClient.exists()) {
                reportContainerClient.create();
            }
            backupContainerClient = blobServiceClient.getBlobContainerClient(backupContainerName);
            if (!backupContainerClient.exists()) {
                backupContainerClient.create();
            }
        }

        if (appConfigConnectionString != null && !appConfigConnectionString.trim().isEmpty()) {
            configurationClient = new ConfigurationClientBuilder()
                    .connectionString(appConfigConnectionString)
                    .buildClient();
        }
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String blobUri = "azureblob://" + reportContainerName + "/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            uploadReport(fileName, reportContent);

            result.put("status", "generated");
            result.put("path", blobUri);
            result.put("serverPort", resolveConfiguredValue(reportPortKey, configuredServerPort));

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = resolveConfiguredValue(reportDownloadUrlKey, reportDownloadBaseUrl);
        return baseUrl + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", "azureblob://" + reportContainerName);
        info.put("backupPath", "azureblob://" + backupContainerName);
        info.put("serverPort", resolveConfiguredValue(reportPortKey, configuredServerPort));
        info.put("generatedAt", timestamp);
        return info;
    }

    public void scheduleReportGeneration(String reportName) {
        if (serviceBusConnectionString == null || serviceBusConnectionString.trim().isEmpty()) {
            return;
        }

        ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportScheduleQueue)
                .buildClient();

        try {
            ServiceBusMessage message = new ServiceBusMessage(reportName);
            message.setScheduledEnqueueTime(OffsetDateTime.now().plusMinutes(reportScheduleDelayMinutes));
            senderClient.scheduleMessage(message, message.getScheduledEnqueueTime());
        } finally {
            senderClient.close();
        }
    }

    private void uploadReport(String fileName, String reportContent) {
        if (reportContainerClient == null) {
            throw new IllegalStateException("Azure Blob Storage is not configured");
        }

        byte[] content = reportContent.getBytes(StandardCharsets.UTF_8);
        reportContainerClient.getBlobClient(fileName)
                .upload(new ByteArrayInputStream(content), content.length, true);
        backupContainerClient.getBlobClient(fileName)
                .upload(new ByteArrayInputStream(content), content.length, true);
    }

    private String resolveConfiguredValue(String key, String defaultValue) {
        if (configurationClient == null) {
            return defaultValue;
        }

        try {
            ConfigurationSetting setting = configurationClient.getConfigurationSetting(key, null);
            if (setting != null && setting.getValue() != null && !setting.getValue().trim().isEmpty()) {
                return setting.getValue();
            }
        } catch (Exception ignored) {
            return defaultValue;
        }
        return defaultValue;
    }

    private BlobServiceClient buildBlobServiceClient() {
        if (storageConnectionString != null && !storageConnectionString.trim().isEmpty()) {
            return new BlobServiceClientBuilder()
                    .connectionString(storageConnectionString)
                    .buildClient();
        }

        if (storageEndpoint != null && !storageEndpoint.trim().isEmpty()) {
            return new BlobServiceClientBuilder()
                    .endpoint(storageEndpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        }

        return null;
    }
}
