package com.demo.resortslite;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private final AzureConfigurationService configurationService;
    private final BlobContainerClient reportsContainerClient;
    private final BlobContainerClient backupsContainerClient;
    private final ServiceBusSenderClient reportScheduleSender;

    public ReportService(AzureConfigurationService configurationService,
                         @Value("${azure.storage.connection-string:}") String storageConnectionString,
                         @Value("${azure.storage.account-endpoint:}") String storageAccountEndpoint,
                         @Value("${azure.storage.reports-container:reports}") String reportsContainer,
                         @Value("${azure.storage.backups-container:backups}") String backupsContainer,
                         @Value("${azure.servicebus.connection-string:}") String serviceBusConnectionString,
                         @Value("${azure.servicebus.report-schedule-queue:report-schedule}") String reportScheduleQueue) {
        this.configurationService = configurationService;
        this.reportsContainerClient = createBlobContainerClient(storageConnectionString, storageAccountEndpoint, reportsContainer);
        this.backupsContainerClient = createBlobContainerClient(storageConnectionString, storageAccountEndpoint, backupsContainer);
        this.reportScheduleSender = createServiceBusSender(serviceBusConnectionString, reportScheduleQueue);
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            reportsContainerClient.createIfNotExists();
            BlobClient blobClient = reportsContainerClient.getBlobClient(fileName);
            byte[] payload = reportContent.getBytes(StandardCharsets.UTF_8);
            blobClient.upload(new ByteArrayInputStream(payload), payload.length, true);

            result.put("status", "generated");
            result.put("path", blobClient.getBlobUrl());
            result.put("storage", "Azure Blob Storage");
            result.put("serverPort", getConfiguredServerPort());

        } catch (RuntimeException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = configurationService.getString(
                "app:report:download-base-url",
                "app.report.download-base-url",
                "https://reports.example.invalid/download/");
        if (!baseUrl.endsWith("/")) {
            baseUrl = baseUrl + "/";
        }
        return baseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> info = new HashMap<>();
        info.put("reportStorage", reportsContainerClient.getBlobContainerUrl());
        info.put("backupStorage", backupsContainerClient.getBlobContainerUrl());
        info.put("serverPort", getConfiguredServerPort());
        info.put("generatedAt", timestamp);
        info.put("timeZone", "UTC");
        return info;
    }

    public Map<String, Object> scheduleMonthlyReport(String month, String year, OffsetDateTime scheduledTimeUtc) {
        Map<String, Object> result = new HashMap<>();
        try {
            ServiceBusMessage message = new ServiceBusMessage(month + "," + year)
                    .setSubject("monthly-report")
                    .setContentType("text/csv");
            OffsetDateTime utcSchedule = scheduledTimeUtc.withOffsetSameInstant(ZoneOffset.UTC);
            reportScheduleSender.scheduleMessage(message, utcSchedule);
            result.put("status", "scheduled");
            result.put("scheduledAt", utcSchedule.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            result.put("scheduler", "Azure Service Bus Scheduled Messages");
        } catch (RuntimeException ex) {
            result.put("status", "error");
            result.put("message", ex.getMessage());
        }
        return result;
    }

    private int getConfiguredServerPort() {
        return configurationService.getInt("server:port", "server.port", 8080);
    }

    private BlobContainerClient createBlobContainerClient(String connectionString, String accountEndpoint, String containerName) {
        BlobContainerClientBuilder builder = new BlobContainerClientBuilder().containerName(containerName);
        if (StringUtils.hasText(connectionString)) {
            builder.connectionString(connectionString);
        } else {
            TokenCredential credential = new DefaultAzureCredentialBuilder().build();
            builder.endpoint(accountEndpoint).credential(credential);
        }
        return builder.buildClient();
    }

    private ServiceBusSenderClient createServiceBusSender(String connectionString, String queueName) {
        if (StringUtils.hasText(connectionString)) {
            return new ServiceBusClientBuilder()
                    .connectionString(connectionString)
                    .sender()
                    .queueName(queueName)
                    .buildClient();
        }
        return new ServiceBusClientBuilder()
                .credential(new DefaultAzureCredentialBuilder().build())
                .fullyQualifiedNamespace(configurationService.getString(
                        "azure:servicebus:namespace",
                        "azure.servicebus.namespace",
                        "localhost.servicebus.windows.net"))
                .sender()
                .queueName(queueName)
                .buildClient();
    }
}
