package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private final AzureBlobReportStorage reportStorage;
    private final AzureCloudConfigService configService;
    private final AzureServiceBusReportScheduler reportScheduler;

    @Autowired
    public ReportService(AzureBlobReportStorage reportStorage,
                         AzureCloudConfigService configService,
                         AzureServiceBusReportScheduler reportScheduler) {
        this.reportStorage = reportStorage;
        this.configService = configService;
        this.reportScheduler = reportScheduler;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "reports/resort_report_" + month + "_" + year + ".csv";
        String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            String blobUrl = reportStorage.uploadReport(blobName, reportContent);
            result.put("status", "generated");
            result.put("path", blobUrl);
            result.put("blobName", blobName);
            result.put("serverPort", configService.getInt("server.port", "PORT", 8080));
            result.put("scheduleStatus", reportScheduler.scheduleReport(blobName, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)));
        } catch (RuntimeException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = configService.getString(
                "app.report.download-base-url",
                "APP_REPORT_DOWNLOAD_BASE_URL",
                "https://reports.resorts-internal.com/download/");
        return baseUrl.endsWith("/") ? baseUrl + reportName : baseUrl + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> info = new HashMap<>();
        info.put("reportStorage", "Azure Blob Storage container: " + reportStorage.getContainerName());
        info.put("backupStorage", "Azure Blob Storage");
        info.put("serverPort", configService.getInt("server.port", "PORT", 8080));
        info.put("generatedAt", timestamp);
        return info;
    }
}
