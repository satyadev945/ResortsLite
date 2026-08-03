package com.demo.resortslite;

import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.specialized.BlockBlobClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private final BlobContainerClient reportContainerClient;
    private final BlobContainerClient backupContainerClient;
    private final String reportBasePath;
    private final String backupBasePath;
    private final int serverPort;
    private final String reportDownloadBaseUrl;
    private final String serviceBusConnectionString;
    private final String reportScheduleQueue;
    private final long reportScheduleDelaySeconds;

    public ReportService(
            @Value("${spring.cloud.azure.storage.blob.endpoint:}") String blobEndpoint,
            @Value("${app.report.container-name:reports}") String reportContainerName,
            @Value("${app.report.backup-container-name:report-backups}") String backupContainerName,
            @Value("${app.report.base-path:reports}") String reportBasePath,
            @Value("${app.report.backup-path:backups}") String backupBasePath,
            @Value("${app.report.service-port:${SERVER_PORT:8080}}") int serverPort,
            @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}") String reportDownloadBaseUrl,
            @Value("${spring.cloud.azure.servicebus.connection-string:}") String serviceBusConnectionString,
            @Value("${app.report.schedule-queue:report-schedule-queue}") String reportScheduleQueue,
            @Value("${app.report.schedule-delay-seconds:300}") long reportScheduleDelaySeconds) {
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(blobEndpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        this.reportContainerClient = blobServiceClient.getBlobContainerClient(reportContainerName);
        this.backupContainerClient = blobServiceClient.getBlobContainerClient(backupContainerName);
        this.reportBasePath = sanitizePath(reportBasePath);
        this.backupBasePath = sanitizePath(backupBasePath);
        this.serverPort = serverPort;
        this.reportDownloadBaseUrl = trimTrailingSlash(reportDownloadBaseUrl);
        this.serviceBusConnectionString = serviceBusConnectionString;
        this.reportScheduleQueue = reportScheduleQueue;
        this.reportScheduleDelaySeconds = reportScheduleDelaySeconds;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String blobName = buildBlobName(reportBasePath, fileName);
        String backupBlobName = buildBlobName(backupBasePath, fileName);

        Map<String, Object> result = new HashMap<>();

        try {
            ensureContainerExists(reportContainerClient);
            ensureContainerExists(backupContainerClient);

            String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            uploadContent(reportContainerClient, blobName, reportContent);
            uploadContent(backupContainerClient, backupBlobName, reportContent);
            scheduleReportNotification(blobName, month, year);

            result.put("status", "generated");
            result.put("path", blobName);
            result.put("backupPath", backupBlobName);
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
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupBasePath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    private void ensureContainerExists(BlobContainerClient containerClient) {
        if (!containerClient.exists()) {
            containerClient.create();
        }
    }

    private void uploadContent(BlobContainerClient containerClient, String blobName, String content) {
        BlockBlobClient blobClient = containerClient.getBlobClient(blobName).getBlockBlobClient();
        blobClient.upload(BinaryData.fromString(content), true);
    }

    private void scheduleReportNotification(String blobName, String month, String year) {
        if (serviceBusConnectionString == null || serviceBusConnectionString.trim().isEmpty()) {
            return;
        }

        ServiceBusMessage message = new ServiceBusMessage(
                BinaryData.fromString("{\"report\":\"" + blobName + "\",\"month\":\"" + month + "\",\"year\":\"" + year + "\"}")
                        .toBytes())
                .setContentType("application/json")
                .setScheduledEnqueueTime(Instant.now().plus(Duration.ofSeconds(reportScheduleDelaySeconds)));

        try (com.azure.messaging.servicebus.ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportScheduleQueue)
                .buildClient()) {
            senderClient.sendMessage(message);
        }
    }

    private String buildBlobName(String basePath, String fileName) {
        return basePath.isEmpty() ? fileName : basePath + "/" + fileName;
    }

    private String sanitizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
