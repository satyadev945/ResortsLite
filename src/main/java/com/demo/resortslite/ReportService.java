package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Fixed czr-java-001: replaced hardcoded absolute path /var/legacy/reports/ with
    // an externalised value injected from application.properties / environment variable.
    // Set REPORT_BASE_PATH at runtime to a mounted volume path or cloud storage mount.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // Fixed czr-java-001: removed hardcoded Windows-style backup path C:\ResortBackups\nightly\.
    // Backup destination is now externalised so the same artifact runs on Linux containers.
    @Value("${app.backup.path:/tmp/backups/}")
    private String backupPath;

    // Fixed czr-port-001: removed hardcoded SERVER_PORT constant.
    // The server port is managed by Spring Boot via server.port / SERVER_PORT env var.
    // Application logic must never hard-depend on a specific port number.

    // Fixed cr-java-0088: replaced hardcoded plain-HTTP report download URL with an
    // externalised HTTPS base URL injected from application configuration.
    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(reportBasePath);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            try (FileWriter writer = new FileWriter(fullPath)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath);
            // Removed: result.put("serverPort", SERVER_PORT) — port must not be exposed in responses

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the HTTPS download URL for a given report name.
     *
     * <p>Fixed cr-java-0088: the URL scheme is now HTTPS and the base URL is
     * externalised via {@code app.report.download-base-url} configuration property,
     * replacing the previous hardcoded plain-HTTP URL.</p>
     *
     * @param reportName the name of the report file
     * @return the full HTTPS download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including report paths, backup path, and the current
     * timestamp formatted using java.time (Java 8+ / Java 21 compatible).
     *
     * <p>Fixed czr-java-001 and czr-port-001: all path and port values are now
     * sourced from injected configuration rather than hardcoded constants.</p>
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // LocalDateTime and DateTimeFormatter are thread-safe (unlike legacy SimpleDateFormat)
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("generatedAt", timestamp);
        return info;
    }
}
