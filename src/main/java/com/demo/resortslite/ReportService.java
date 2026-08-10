package com.demo.resortslite;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
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

    private final AzureCloudConfigService cloudConfigService;
    private final BlobContainerClient blobContainerClient;
    private final ServiceBusSenderClient reportSchedulerClient;

    public ReportService(AzureCloudConfigService cloudConfigService,
                         @Value("${azure.storage.blob.connection-string:}") String blobConnectionString,
                         @Value("${azure.storage.blob.endpoint:}") String blobEndpoint,
                         @Value("${azure.storage.blob.container:reports}") String blobContainer,
                         @Value("${azure.servicebus.connection-string:}") String serviceBusConnectionString,
                         @Value("${azure.servicebus.fully-qualified-namespace:}") String serviceBusNamespace,
                         @Value("${azure.servicebus.report-queue:}") String reportQueue) {
        this.cloudConfigService = cloudConfigService;
        this.blobContainerClient = buildBlobContainerClient(blobConnectionString, blobEndpoint, blobContainer);
        this.reportSchedulerClient = buildServiceBusSender(serviceBusConnectionString, serviceBusNamespace, reportQueue);
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";
        byte[] reportBytes = buildReportContent().getBytes(StandardCharsets.UTF_8);

        Map<String, Object> result = new HashMap<>();
        try {
            if (!blobContainerClient.exists()) {
                blobContainerClient.create();
            }
            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
            blobClient.upload(new ByteArrayInputStream(reportBytes), reportBytes.length, true);

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("blobUrl", blobClient.getBlobUrl());
            result.put("serverPort", cloudConfigService.getIntConfiguration("server.port", "server.port", 0));
        } catch (RuntimeException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = cloudConfigService.getConfiguration(
                "app.reports.download-base-url",
                "app.reports.download-base-url",
                "");
        if (StringUtils.hasText(baseUrl)) {
            return trimTrailingSlash(baseUrl) + "/" + reportName;
        }
        return blobContainerClient.getBlobClient(reportName).getBlobUrl();
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> info = new HashMap<>();
        info.put("reportStorage", "Azure Blob Storage");
        info.put("reportContainer", blobContainerClient.getBlobContainerName());
        info.put("reportContainerUrl", blobContainerClient.getBlobContainerUrl());
        info.put("serverPort", cloudConfigService.getIntConfiguration("server.port", "server.port", 0));
        info.put("generatedAt", timestamp);
        scheduleSystemInfoAudit(timestamp);
        return info;
    }

    private BlobContainerClient buildBlobContainerClient(String connectionString, String endpoint, String container) {
        BlobContainerClientBuilder builder = new BlobContainerClientBuilder().containerName(container);
        if (StringUtils.hasText(connectionString)) {
            builder.connectionString(connectionString);
        } else if (StringUtils.hasText(endpoint)) {
            builder.endpoint(endpoint).credential(new DefaultAzureCredentialBuilder().build());
        } else {
            throw new IllegalStateException("Azure Blob Storage endpoint or connection string must be configured");
        }
        return builder.buildClient();
    }

    private ServiceBusSenderClient buildServiceBusSender(String connectionString, String fullyQualifiedNamespace, String queueName) {
        if (!StringUtils.hasText(queueName)) {
            return null;
        }
        if (StringUtils.hasText(connectionString)) {
            return new ServiceBusClientBuilder()
                    .connectionString(connectionString)
                    .sender()
                    .queueName(queueName)
                    .buildClient();
        }
        if (StringUtils.hasText(fullyQualifiedNamespace)) {
            TokenCredential credential = new DefaultAzureCredentialBuilder().build();
            return new ServiceBusClientBuilder()
                    .credential(fullyQualifiedNamespace, credential)
                    .sender()
                    .queueName(queueName)
                    .buildClient();
        }
        return null;
    }

    private void scheduleSystemInfoAudit(String timestamp) {
        if (reportSchedulerClient != null) {
            ServiceBusMessage message = new ServiceBusMessage("system-info-generated:" + timestamp);
            reportSchedulerClient.scheduleMessage(message, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        }
    }

    private String buildReportContent() {
        return "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";
    }

    private String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
