package com.demo.resortslite;

import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.BlobClient;
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

    private final String blobEndpoint;
    private final String blobContainerName;
    private final String reportDownloadBaseUrl;
    private final String backupLocation;
    private final int serverPort;
    private final String serviceBusNamespace;
    private final String serviceBusQueueName;

    public ReportService(
            @Value("${app.storage.blob.endpoint}") String blobEndpoint,
            @Value("${app.storage.blob.container-name}") String blobContainerName,
            @Value("${app.report.download-base-url}") String reportDownloadBaseUrl,
            @Value("${app.report.backup-location}") String backupLocation,
            @Value("${server.port}") int serverPort,
            @Value("${app.servicebus.namespace}") String serviceBusNamespace,
            @Value("${app.servicebus.queue-name}") String serviceBusQueueName) {
        this.blobEndpoint = blobEndpoint;
        this.blobContainerName = blobContainerName;
        this.reportDownloadBaseUrl = reportDownloadBaseUrl;
        this.backupLocation = backupLocation;
        this.serverPort = serverPort;
        this.serviceBusNamespace = serviceBusNamespace;
        this.serviceBusQueueName = serviceBusQueueName;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            BlobClient blobClient = getContainerClient().getBlobClient(fileName);
            byte[] contentBytes = reportContent.getBytes(StandardCharsets.UTF_8);
            blobClient.upload(new ByteArrayInputStream(contentBytes), contentBytes.length, true);

            result.put("status", "generated");
            result.put("path", blobClient.getBlobUrl());
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + "/download/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", blobEndpoint + "/" + blobContainerName);
        info.put("backupPath", backupLocation);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    public void scheduleReportGeneration(String reportName, Duration delay) {
        ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .credential(serviceBusNamespace, new DefaultAzureCredentialBuilder().build())
                .sender()
                .queueName(serviceBusQueueName)
                .buildClient();
        try {
            Instant scheduledTime = Instant.now().plus(delay);
            senderClient.scheduleMessage(BinaryData.fromString(reportName), scheduledTime);
        } finally {
            senderClient.close();
        }
    }

    private BlobContainerClient getContainerClient() {
        BlobContainerClient containerClient = new BlobContainerClientBuilder()
                .endpoint(blobEndpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .containerName(blobContainerName)
                .buildClient();
        if (!containerClient.exists()) {
            containerClient.create();
        }
        return containerClient;
    }
}
