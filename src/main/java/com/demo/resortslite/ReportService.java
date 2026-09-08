package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Replaced legacy java.util.Date / java.text.SimpleDateFormat with java.time.LocalDateTime.
// Issue: date-time — Legacy java.util.Date and SimpleDateFormat usage.
// Fix: Migrate to java.time API (LocalDateTime + DateTimeFormatter) for thread safety
// and correctness. SimpleDateFormat is not thread-safe; DateTimeFormatter is.
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // NOTE: Hardcoded absolute paths break containerisation. For production cloud deployments,
    // use volume mounts, cloud object storage (e.g., AWS S3), or environment variables.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";

    // NOTE: Windows-style absolute path will fail on Linux-based containers or cloud hosts.
    // Replace with a cross-platform path or externalise via environment variable.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";

    // NOTE: Fixed server port hardcoded in application logic. Container orchestration
    // (ECS/EKS) dynamically assigns ports. Externalise via server.port property or
    // environment variable for cloud-native deployments.
    private static final int SERVER_PORT = 8080;

    // Thread-safe DateTimeFormatter replaces non-thread-safe SimpleDateFormat.
    // Issue: date-time — Legacy java.util.Date and SimpleDateFormat usage.
    // Fix: Use java.time.format.DateTimeFormatter (immutable and thread-safe).
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly booking report CSV file.
     *
     * <p>Uses {@link LocalDateTime} and {@link DateTimeFormatter} instead of the legacy
     * {@code java.util.Date} / {@code SimpleDateFormat} APIs, which are not thread-safe.</p>
     *
     * @param month the month identifier (e.g., "2024-03")
     * @param year  the year identifier (e.g., "2024")
     * @return map containing generation status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH);
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
            result.put("serverPort", SERVER_PORT);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name.
     *
     * <p>NOTE: For production, use HTTPS endpoints and externalise the base URL to
     * an environment variable or configuration property.</p>
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns system information including report paths and current timestamp.
     *
     * <p>Updated from {@code new Date()} + {@code SimpleDateFormat} to
     * {@link LocalDateTime} + {@link DateTimeFormatter}.
     * Issue: date-time — Legacy java.util.Date and SimpleDateFormat usage.
     * Fix: java.time API is thread-safe and preferred in Java 17+.</p>
     *
     * @return map containing system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Updated from new Date() + SimpleDateFormat to LocalDateTime + DateTimeFormatter.
        // Issue: date-time — Legacy java.util.Date and SimpleDateFormat usage.
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
