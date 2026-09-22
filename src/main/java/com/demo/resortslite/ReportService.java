package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated: Replaced legacy java.util.Date / SimpleDateFormat with java.time API
// (java.util.Date and SimpleDateFormat are deprecated for new code in Java 8+)
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // NOTE: Hardcoded absolute path — does not exist in a Docker container image.
    // Consider using volume mounts, cloud object storage (S3/Azure Blob),
    // or an environment variable for the report base path.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";

    // NOTE: Windows-style absolute path will fail on Linux-based containers or cloud hosts.
    // Replace with a cross-platform configurable path.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";

    // NOTE: Fixed server port hardcoded in application logic.
    // Container orchestration (ECS/EKS) dynamically assigns ports.
    // Use server.port property or dynamic port binding instead.
    private static final int SERVER_PORT = 8080;

    /**
     * Generates a monthly booking report as a CSV file.
     *
     * @param month the month (e.g., "03")
     * @param year  the year (e.g., "2024")
     * @return a map containing the generation status and file path
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
     * NOTE: Plain HTTP URL — cloud security standards enforce HTTPS.
     * Update to HTTPS endpoint for production deployments.
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns system information including report paths, backup path,
     * server port, and current timestamp.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: Using java.time.LocalDateTime instead of deprecated java.util.Date
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
