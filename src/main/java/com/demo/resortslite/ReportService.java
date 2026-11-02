package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private final Path reportBasePath;
    private final Path backupPath;
    private final String reportDownloadBaseUrl;

    public ReportService(
            @Value("${app.report.base-path:${java.io.tmpdir}/resort-reports}") String reportBasePath,
            @Value("${app.backup.path:${java.io.tmpdir}/resort-backups}") String backupPath,
            @Value("${app.report.download-base-url:https://reports.example.internal/download}") String reportDownloadBaseUrl) {
        this.reportBasePath = Paths.get(reportBasePath);
        this.backupPath = Paths.get(backupPath);
        this.reportDownloadBaseUrl = reportDownloadBaseUrl;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        Path fullPath = reportBasePath.resolve(fileName);

        Map<String, Object> result = new HashMap<>();

        try {
            Files.createDirectories(reportBasePath);
            Files.writeString(fullPath,
                    "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                            + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                            + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            result.put("status", "generated");
            result.put("path", fullPath.toString());

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath.toString());
        info.put("backupPath", backupPath.toString());
        info.put("generatedAt", timestamp);
        return info;
    }
}
