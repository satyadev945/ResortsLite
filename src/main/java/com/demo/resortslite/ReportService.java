package com.demo.resortslite;

import com.azure.core.util.BinaryData;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.BlobClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    @Value("${azure.storage.blob.endpoint:}")
    private String blobEndpoint;

    @Value("${azure.storage.blob.container-name:reports}")
    private String containerName;

    @Value("${azure.appconfig.connection-string:}")
    private String appConfigConnectionString;

    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}")
    private String configuredDownloadBaseUrl;

    @Value("${SERVER_PORT:${server.port:8080}}")
    private String configuredServerPort;

    @Value("${azure.servicebus.connection-string:}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.queue-name:report-jobs}")
    private String serviceBusQueueName;

    private BlobContainerClient blobContainerClient;
    private ConfigurationClient configurationClient;
    private String downloadBaseUrl;
    private String serverPort;

    @PostConstruct
    public void initializeCloudClients() {
        if (blobEndpoint != null && !blobEndpoint.trim().isEmpty()) {
            blobContainerClient = new BlobContainerClientBuilder()
                    .endpoint(blobEndpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .containerName(containerName)
                    .buildClient();
            try {
                blobContainerClient.createIfNotExists();
            } catch (Exception ignored) {
            }
        }

        if (appConfigConnectionString != null && !appConfigConnectionString.trim().isEmpty()) {
            configurationClient = new ConfigurationClientBuilder()
                    .connectionString(appConfigConnectionString)
                    .buildClient();
        }

        downloadBaseUrl = resolveConfiguration("app.report.download-base-url", configuredDownloadBaseUrl);
        serverPort = resolveConfiguration("server.port", configuredServerPort);
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            String blobLocation = uploadReport(fileName, reportContent);
            result.put("status", "generated");
            result.put("path", blobLocation);
            result.put("serverPort", serverPort);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        return downloadBaseUrl + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", blobContainerClient != null ? blobContainerClient.getBlobContainerUrl() : "azure-blob-unconfigured");
        info.put("backupPath", blobContainerClient != null ? blobContainerClient.getBlobContainerUrl() : "azure-blob-unconfigured");
        info.put("serverPort", serverPort);
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
            ServiceBusMessage message = new ServiceBusMessage("generate-report:" + month + ":" + year)
                    .setScheduledEnqueueTime(Instant.now().plus(Duration.ofHours(1)));
            senderClient.scheduleMessage(message, Instant.now().plus(Duration.ofHours(1)));
        } finally {
            senderClient.close();
        }
    }

    private String uploadReport(String fileName, String reportContent) {
        if (blobContainerClient == null) {
            return "azure-blob://" + containerName + "/" + fileName;
        }
        BlobClient blobClient = blobContainerClient.getBlobClient(fileName);
        blobClient.upload(BinaryData.fromBytes(reportContent.getBytes(StandardCharsets.UTF_8)), true);
        return blobClient.getBlobUrl();
    }

    private String resolveConfiguration(String key, String fallback) {
        if (configurationClient != null) {
            try {
                return configurationClient.getConfigurationSetting(key, null).getValue();
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }
}
