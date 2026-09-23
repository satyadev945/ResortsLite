package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private final Path reportBasePath;
    private final Path backupPath;
    private final String reportBaseUrl;

    public ReportService(
            @Value("${app.report.base-path:./reports}") String reportBasePath,
            @Value("${app.report.backup-path:./backups/nightly}") String backupPath,
            @Value("${app.report.base-url:https://reports.resorts-internal.com/download}") String reportBaseUrl) {
        this.reportBasePath = Paths.get(reportBasePath);
        this.backupPath = Paths.get(backupPath);
        this.reportBaseUrl = reportBaseUrl;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        Path fullPath = reportBasePath.resolve(fileName);
        Map<String, Object> result = new HashMap<>();

        try {
            Files.createDirectories(reportBasePath);
            Files.writeString(
                    fullPath,
                    "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                            + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                            + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n",
                    StandardCharsets.UTF_8);

            result.put("status", "generated");
            result.put("path", fullPath.toString());
        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        return reportBaseUrl.endsWith("/") ? reportBaseUrl + reportName : reportBaseUrl + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath.toString());
        info.put("backupPath", backupPath.toString());
        info.put("generatedAt", timestamp);
        return info;
    }
}
