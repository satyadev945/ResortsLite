package com.demo.resortslite;

import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private final AzureConfigurationService configurationService;
    private final BlobContainerClient reportContainerClient;
    private final ServiceBusSenderClient scheduledReportSender;
    private final String reportContainerName;

    public ReportService(AzureConfigurationService configurationService,
                         @Value("${azure.storage.account-endpoint:}") String storageEndpoint,
                         @Value("${azure.storage.reports-container:reports}") String reportContainerName,
                         @Value("${azure.servicebus.fully-qualified-namespace:}") String serviceBusNamespace,
                         @Value("${azure.servicebus.report-schedule-queue:report-schedule}") String reportScheduleQueue) {
        this.configurationService = configurationService;
        this.reportContainerName = configurationService.getSetting(
                "reports:container-name", "AZURE_STORAGE_REPORTS_CONTAINER", reportContainerName);
        this.reportContainerClient = createBlobContainerClient(storageEndpoint, this.reportContainerName);
        this.scheduledReportSender = createServiceBusSender(serviceBusNamespace, reportScheduleQueue);
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            BlobClient blobClient = reportContainerClient.getBlobClient(fileName);
            blobClient.upload(BinaryData.fromBytes(csvContent.getBytes(StandardCharsets.UTF_8)), true);

            result.put("status", "generated");
            result.put("blobContainer", reportContainerName);
            result.put("blobName", fileName);
            result.put("uri", blobClient.getBlobUrl());
            result.put("serverPort", configurationService.getIntSetting("server:port", "PORT", 8080));

            scheduleReportCompletionMessage(fileName);
        } catch (RuntimeException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a cloud-configured HTTPS report download URL from Azure App Configuration.
     */
    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = configurationService.getSetting(
                "reports:download-base-url",
                "REPORT_DOWNLOAD_BASE_URL",
                "https://reports.resorts.example.com/download");
        return trimTrailingSlash(baseUrl) + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> info = new HashMap<>();
        info.put("reportStorage", "Azure Blob Storage");
        info.put("reportContainer", reportContainerName);
        info.put("backupStorage", configurationService.getSetting(
                "reports:backup-container", "AZURE_STORAGE_BACKUP_CONTAINER", "report-backups"));
        info.put("serverPort", configurationService.getIntSetting("server:port", "PORT", 8080));
        info.put("generatedAt", timestamp);
        return info;
    }

    private BlobContainerClient createBlobContainerClient(String storageEndpoint, String containerName) {
        String endpoint = configurationService.getSetting(
                "azure:storage:account-endpoint", "AZURE_STORAGE_ACCOUNT_ENDPOINT", storageEndpoint);
        if (endpoint == null || endpoint.trim().isEmpty()) {
            endpoint = "https://devstoreaccount1.blob.core.windows.net";
        }
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        try {
            if (!containerClient.exists()) {
                containerClient.create();
            }
        } catch (RuntimeException ignored) {
            // Creation can be managed by Azure provisioning; upload will surface real storage failures.
        }
        return containerClient;
    }

    private ServiceBusSenderClient createServiceBusSender(String serviceBusNamespace, String queueName) {
        String namespace = configurationService.getSetting(
                "azure:servicebus:fully-qualified-namespace",
                "AZURE_SERVICEBUS_FULLY_QUALIFIED_NAMESPACE",
                serviceBusNamespace);
        if (namespace == null || namespace.trim().isEmpty()) {
            return null;
        }
        return new ServiceBusClientBuilder()
                .fullyQualifiedNamespace(namespace)
                .credential(new DefaultAzureCredentialBuilder().build())
                .sender()
                .queueName(queueName)
                .buildClient();
    }

    private void scheduleReportCompletionMessage(String blobName) {
        if (scheduledReportSender == null) {
            return;
        }
        ServiceBusMessage message = new ServiceBusMessage("Report generated: " + blobName);
        scheduledReportSender.scheduleMessage(message, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
