package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated from java.util.Date / java.text.SimpleDateFormat to java.time API
// JAVA8_TO_25_DATE_TIME_CHANGES: Legacy date/time APIs replaced with thread-safe java.time API.
// LocalDateTime + DateTimeFormatter is immutable and thread-safe (unlike SimpleDateFormat).
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // NOTE: Hardcoded absolute paths should be externalised to environment variables
    // or replaced with cloud object storage (S3 / Azure Blob) for container deployments.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";

    // NOTE: Windows-style absolute path will fail on Linux-based containers.
    // Replace with environment variable or cloud storage reference.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";

    // NOTE: Fixed server port should be externalised to application.properties or
    // environment variable for dynamic port binding in container orchestration (ECS/EKS).
    private static final int SERVER_PORT = 8080;

    // DateTimeFormatter is thread-safe (unlike SimpleDateFormat) — Java 25 best practice
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly booking report CSV file.
     *
     * @param month the month identifier (e.g., "03")
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
     * NOTE: URL scheme should be HTTPS for cloud-native / AWS ALB deployments.
     *
     * @param reportName the name of the report file
     * @return download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns system information including report paths and current timestamp.
     * Updated from java.util.Date / SimpleDateFormat to java.time.LocalDateTime /
     * DateTimeFormatter for Java 25 compatibility (thread-safe, immutable).
     *
     * @return map containing system info entries
     */
    public Map<String, Object> getSystemInfo() {
        // Updated from new SimpleDateFormat("...").format(new Date()) to java.time API
        // JAVA8_TO_25_DATE_TIME_CHANGES: LocalDateTime + DateTimeFormatter replaces
        // java.util.Date + SimpleDateFormat (thread-safe, immutable, Java 25 standard)
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
