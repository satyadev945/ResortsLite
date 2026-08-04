package com.demo.resortslite;

import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private final BlobContainerClient reportContainerClient;
    private final BlobContainerClient backupContainerClient;
    private final String reportDownloadBaseUrl;
    private final int serverPort;
    private final String reportScheduleQueue;
    private final int reportScheduleDelaySeconds;
    private final String serviceBusConnectionString;

    public ReportService(
            @Value("${app.azure.storage.connection-string}") String storageConnectionString,
            @Value("${app.report.container-name}") String reportContainerName,
            @Value("${app.report.backup-container-name}") String backupContainerName,
            @Value("${app.report.download-base-url}") String reportDownloadBaseUrl,
            @Value("${server.port:8080}") int serverPort,
            @Value("${app.report.schedule-queue}") String reportScheduleQueue,
            @Value("${app.report.schedule-delay-seconds:300}") int reportScheduleDelaySeconds,
            @Value("${app.azure.servicebus.connection-string}") String serviceBusConnectionString) {
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(storageConnectionString)
                .buildClient();
        this.reportContainerClient = blobServiceClient.getBlobContainerClient(reportContainerName);
        this.backupContainerClient = blobServiceClient.getBlobContainerClient(backupContainerName);
        if (!this.reportContainerClient.exists()) {
            this.reportContainerClient.create();
        }
        if (!this.backupContainerClient.exists()) {
            this.backupContainerClient.create();
        }
        this.reportDownloadBaseUrl = reportDownloadBaseUrl;
        this.serverPort = serverPort;
        this.reportScheduleQueue = reportScheduleQueue;
        this.reportScheduleDelaySeconds = reportScheduleDelaySeconds;
        this.serviceBusConnectionString = serviceBusConnectionString;
        new DefaultAzureCredentialBuilder().build();
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            reportContainerClient.getBlobClient(fileName)
                    .upload(BinaryData.fromString(reportContent), true);
            backupContainerClient.getBlobClient(fileName)
                    .upload(BinaryData.fromString(reportContent), true);

            result.put("status", "generated");
            result.put("path", reportContainerClient.getBlobClient(fileName).getBlobUrl());
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportContainerClient.getBlobContainerUrl());
        info.put("backupPath", backupContainerClient.getBlobContainerUrl());
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    public void scheduleReportGeneration(String month) {
        Instant scheduledTime = Instant.now().plus(Duration.ofSeconds(reportScheduleDelaySeconds));
        String payload = "{\"month\":\"" + month + "\",\"scheduledAt\":\"" + scheduledTime + "\"}";
        ServiceBusMessage message = new ServiceBusMessage(payload.getBytes(StandardCharsets.UTF_8))
                .setScheduledEnqueueTime(scheduledTime);
        new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportScheduleQueue)
                .buildClient()
                .sendMessage(message);
    }
}
