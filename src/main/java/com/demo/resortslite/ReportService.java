package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for generating and managing resort reports.
 * Cloud-compatible: externalised paths, HTTPS endpoints, no fixed ports.
 */
@Service
public class ReportService {

    // Fixed: externalised paths (czr-java-001) — no hardcoded absolute paths
    @Value("${app.report.base-path:/tmp/reports}")
    private String reportBasePath;

    @Value("${app.backup.base-path:/tmp/backups}")
    private String backupPath;

    // Fixed: removed fixed server port (czr-port-001) — let container orchestration assign dynamically

    /**
     * Generates a monthly report CSV file.
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return a map containing the report status and path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + "/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(reportBasePath);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            FileWriter writer = new FileWriter(fullPath);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.close();

            result.put("status", "generated");
            result.put("path", fullPath);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a secure HTTPS download URL for a report.
     *
     * @param reportName the name of the report file
     * @return the HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Fixed: HTTPS URL (cr-java-0088) — no plain HTTP
        return "https://reports.resorts-internal.com/download/" + reportName;
    }

    /**
     * Returns system information for diagnostics.
     *
     * @return a map containing system info
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("generatedAt", timestamp);
        return info;
    }
}
