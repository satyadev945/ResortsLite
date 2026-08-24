package com.demo.resortslite;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private final String reportContainerName;
    private final String backupContainerName;
    private final String storageConnectionString;
    private final String reportDownloadBaseUrl;
    private final String configuredServerPort;
    private final String serviceBusQueue;
    private final int scheduleDelayMinutes;
    private final ServiceBusSenderClient serviceBusSenderClient;

    public ReportService(
            @Value("${app.report.container-name}") String reportContainerName,
            @Value("${app.report.backup-container-name}") String backupContainerName,
            @Value("${app.report.storage-connection-string:}") String storageConnectionString,
            @Value("${app.config.report-download-url}") String reportDownloadBaseUrl,
            @Value("${app.config.server-port}") String configuredServerPort,
            @Value("${app.report.service-bus-queue}") String serviceBusQueue,
            @Value("${app.report.schedule-delay-minutes}") int scheduleDelayMinutes,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ServiceBusSenderClient serviceBusSenderClient) {
        this.reportContainerName = reportContainerName;
        this.backupContainerName = backupContainerName;
        this.storageConnectionString = storageConnectionString;
        this.reportDownloadBaseUrl = reportDownloadBaseUrl;
        this.configuredServerPort = configuredServerPort;
        this.serviceBusQueue = serviceBusQueue;
        this.scheduleDelayMinutes = scheduleDelayMinutes;
        this.serviceBusSenderClient = serviceBusSenderClient;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            BlobContainerClient reportContainer = getContainerClient(reportContainerName);
            BlobClient reportBlob = reportContainer.getBlobClient(fileName);
            reportBlob.upload(BinaryData.fromString(reportContent), true);

            BlobContainerClient backupContainer = getContainerClient(backupContainerName);
            backupContainer.getBlobClient(fileName).upload(BinaryData.fromString(reportContent), true);

            result.put("status", "generated");
            result.put("path", reportBlob.getBlobUrl());
            result.put("serverPort", configuredServerPort);

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
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportContainer", reportContainerName);
        info.put("backupContainer", backupContainerName);
        info.put("serverPort", configuredServerPort);
        info.put("generatedAt", timestamp);
        info.put("serviceBusQueue", serviceBusQueue);
        return info;
    }

    public void scheduleReportGeneration(String month) {
        if (serviceBusSenderClient == null) {
            return;
        }

        String payload = String.format("{\"month\":\"%s\"}", month);
        ServiceBusMessage message = new ServiceBusMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setScheduledEnqueueTime(OffsetDateTime.now().plusMinutes(scheduleDelayMinutes));
        serviceBusSenderClient.scheduleMessage(message, message.getScheduledEnqueueTime());
    }

    private BlobContainerClient getContainerClient(String containerName) {
        if (storageConnectionString == null || storageConnectionString.trim().isEmpty()) {
            throw new IllegalStateException("Azure Blob Storage connection string is not configured");
        }

        BlobContainerClient containerClient = new BlobContainerClientBuilder()
                .connectionString(storageConnectionString)
                .containerName(containerName)
                .buildClient();
        if (!containerClient.exists()) {
            containerClient.create();
        }
        return containerClient;
    }
}
