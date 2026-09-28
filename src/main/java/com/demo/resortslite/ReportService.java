package com.demo.resortslite;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import com.demo.resortslite.service.AzureServiceBusSchedulerService;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED cr-java-0111: Inject Azure Service Bus scheduler for distributed task scheduling
    @Autowired(required = false)
    private AzureServiceBusSchedulerService schedulerService;

    // FIXED cr-java-0061: Replaced hard-coded file paths with Azure Blob Storage configuration
    // Using environment variables and Spring configuration for cloud-native storage
    @Value("${azure.storage.connection-string}")
    private String azureStorageConnectionString;

    @Value("${azure.storage.container.reports}")
    private String reportsContainerName;

    @Value("${azure.storage.container.backups}")
    private String backupsContainerName;

    // FIXED cr-java-0071: Externalized report download base URL to Azure App Configuration
    @Value("${app.reports.download.baseurl:https://reports.resorts-internal.com}")
    private String reportDownloadBaseUrl;

    @Value("${azure.storage.enabled:true}")
    private boolean azureStorageEnabled;

    @Value("${server.port}")
    private int serverPort;

    private BlobServiceClient blobServiceClient;
    private BlobContainerClient reportsContainerClient;
    private BlobContainerClient backupsContainerClient;

    /**
     * Initialize Azure Blob Storage clients after bean construction.
     * Creates containers if they don't exist for cloud-native file storage.
     */
    @PostConstruct
    public void initializeAzureStorage() {
        if (azureStorageEnabled) {
            try {
                // Initialize Azure Blob Storage client
                blobServiceClient = new BlobServiceClientBuilder()
                        .connectionString(azureStorageConnectionString)
                        .buildClient();

                // Get or create reports container
                reportsContainerClient = blobServiceClient.getBlobContainerClient(reportsContainerName);
                if (!reportsContainerClient.exists()) {
                    reportsContainerClient.create();
                }

                // Get or create backups container
                backupsContainerClient = blobServiceClient.getBlobContainerClient(backupsContainerName);
                if (!backupsContainerClient.exists()) {
                    backupsContainerClient.create();
                }
            } catch (Exception e) {
                // Log error but don't fail application startup
                System.err.println("Failed to initialize Azure Blob Storage: " + e.getMessage());
                azureStorageEnabled = false;
            }
        }
    }

    /**
     * Generates a monthly report and stores it in Azure Blob Storage.
     * FIXED cr-java-0061: Replaced file system operations with Azure Blob Storage.
     *
     * @param month The month for the report
     * @param year The year for the report
     * @return Map containing report generation status and blob URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            if (azureStorageEnabled && reportsContainerClient != null) {
                // FIXED cr-java-0061: Upload to Azure Blob Storage instead of local file system
                BlobClient blobClient = reportsContainerClient.getBlobClient(fileName);
                
                byte[] csvBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);
                ByteArrayInputStream inputStream = new ByteArrayInputStream(csvBytes);
                
                blobClient.upload(inputStream, csvBytes.length, true);

                result.put("status", "generated");
                result.put("storageType", "azure-blob");
                result.put("container", reportsContainerName);
                result.put("blobName", fileName);
                result.put("blobUrl", blobClient.getBlobUrl());
                result.put("serverPort", serverPort);
            } else {
                // Fallback for local development or when Azure Storage is disabled
                result.put("status", "generated-local");
                result.put("storageType", "in-memory");
                result.put("fileName", fileName);
                result.put("content", csvContent.toString());
                result.put("serverPort", serverPort);
            }

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using HTTPS for cloud security compliance.
     * FIXED cr-java-0088: Changed from HTTP to HTTPS for cloud security standards.
     *
     * @param reportName The name of the report to download
     * @return HTTPS URL for report download
     */
    public String buildReportDownloadUrl(String reportName) {
        if (azureStorageEnabled && reportsContainerClient != null) {
            // FIXED cr-java-0061: Return Azure Blob Storage URL instead of hard-coded URL
            BlobClient blobClient = reportsContainerClient.getBlobClient(reportName);
            return blobClient.getBlobUrl();
        } else {
            // Fallback URL with HTTPS (FIXED cr-java-0088)
            // FIXED cr-java-0071: Using externalized configuration from Azure App Configuration
            // FIXED cr-java-0077: Removed hard-coded port from URL construction
            // Port is now managed by Azure App Configuration and environment variables
            return reportDownloadBaseUrl + "/download/" + reportName;
        }
    }

    /**
     * Returns system information including Azure Blob Storage configuration.
     * FIXED cr-java-0061: Replaced hard-coded paths with Azure Blob Storage container names.
     *
     * @return Map containing system configuration information
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED cr-java-0111: Replaced server-local timezone-dependent Date/SimpleDateFormat
        // with UTC-based Instant and DateTimeFormatter for cloud-native, timezone-agnostic timestamps
        // This ensures consistent time handling across distributed Azure services and regions
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
        String timestamp = formatter.format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        
        // FIXED cr-java-0061: Return Azure Blob Storage configuration instead of hard-coded paths
        info.put("storageType", azureStorageEnabled ? "azure-blob" : "local");
        info.put("reportsContainer", reportsContainerName);
        info.put("backupsContainer", backupsContainerName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("azureStorageEnabled", azureStorageEnabled);
        
        return info;
    }

    /**
     * Schedule a monthly report generation task using Azure Service Bus.
     * FIXED cr-java-0111: Replaced java.util.Timer with Azure Service Bus scheduled messages
     * for distributed, timezone-agnostic task scheduling.
     *
     * @param month The month for the report
     * @param year The year for the report
     * @param delaySeconds Delay in seconds before report generation
     * @return Map containing scheduling status
     */
    public Map<String, Object> scheduleMonthlyReport(String month, String year, long delaySeconds) {
        if (schedulerService != null) {
            return schedulerService.scheduleReportGenerationWithDelay("monthly", month, year, delaySeconds);
        } else {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "unavailable");
            result.put("message", "Azure Service Bus scheduler is not configured");
            return result;
        }
    }
}
