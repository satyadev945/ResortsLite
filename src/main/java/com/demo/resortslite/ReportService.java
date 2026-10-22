package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Report base path externalised to environment variable (czr-java-001).
    // Defaults to the JVM temporary directory so the application works inside a
    // container without requiring a pre-existing filesystem layout.
    // For production, set APP_REPORT_BASE_PATH to an S3-mounted path or a
    // persistent volume mount injected by ECS/EKS.
    @Value("${app.report.base-path:${java.io.tmpdir}/reports}")
    private String reportBasePath;

    // Removed hardcoded Windows backup path (czr-java-001).
    // Backup destination is now controlled via environment variable APP_BACKUP_PATH,
    // defaulting to a platform-neutral temp subdirectory.
    @Value("${app.backup.path:${java.io.tmpdir}/backups}")
    private String backupPath;

    // Removed hardcoded SERVER_PORT constant (czr-port-001).
    // The actual port is managed by Spring Boot via the SERVER_PORT environment
    // variable and is not a concern of the service layer.

    // DateTimeFormatter is immutable and thread-safe (unlike SimpleDateFormat)
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Report download base URL externalised to environment variable (cr-java-0088).
    // HTTPS enforced; value injected at runtime via AWS Parameter Store / ECS task definition.
    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    /**
     * Generates a monthly booking report CSV file and writes it to the configured
     * report base path.
     *
     * @param month numeric month (e.g. "03")
     * @param year  four-digit year (e.g. "2024")
     * @return result map containing status and the generated file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + File.separator + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(reportBasePath);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            // FileWriter uses explicit UTF-8 charset for deterministic behaviour
            // across all Java versions (behaviour changed in Java 18 via JEP 400).
            try (FileWriter writer = new FileWriter(fullPath, StandardCharsets.UTF_8)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the HTTPS download URL for a named report file.
     * The base URL is injected via the {@code app.report.download-base-url}
     * property, defaulting to an HTTPS endpoint (cr-java-0088).
     *
     * @param reportName filename of the report to download
     * @return fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns current system configuration information for diagnostics.
     *
     * @return map containing report path, backup path, and generation timestamp
     */
    public Map<String, Object> getSystemInfo() {
        // java.time.LocalDateTime + DateTimeFormatter: immutable, thread-safe,
        // and the recommended replacement for legacy java.util.Date + SimpleDateFormat.
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("generatedAt", timestamp);
        return info;
    }
}
