package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Service responsible for generating and managing resort reports.
 *
 * <p>Uses {@link java.time.LocalDateTime} and {@link java.time.format.DateTimeFormatter}
 * (java.time API) instead of the legacy {@code java.util.Date} / {@code SimpleDateFormat}
 * which are thread-unsafe and deprecated for new code.</p>
 *
 * <p><strong>Note:</strong> Report paths are currently hardcoded constants.
 * For container / cloud deployments these should be externalised to environment
 * variables or replaced with cloud object-storage (S3 / Azure Blob) calls.</p>
 */
@Service
public class ReportService {

    // NOTE: Hardcoded paths — externalise via @Value / environment variable for containers.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";
    private static final String BACKUP_PATH = "/opt/resort-backups/nightly/"; // was Windows path; normalised to POSIX
    private static final int SERVER_PORT = 8080; // NOTE: externalise via server.port property

    /**
     * Generates a monthly CSV report and writes it to {@code REPORT_BASE_PATH}.
     *
     * @param month the month identifier (e.g. "03")
     * @param year  the four-digit year (e.g. "2024")
     * @return a result map containing status, path, and server port
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
     * <p><strong>Note:</strong> URL scheme should be HTTPS in production / cloud environments.</p>
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // NOTE: Use HTTPS and externalise the base URL for cloud-native deployments.
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns system information including report paths and current timestamp.
     *
     * <p>Timestamp is generated using {@link LocalDateTime} (java.time API) —
     * thread-safe replacement for the legacy {@code SimpleDateFormat}.</p>
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // java.time API — thread-safe, replaces legacy java.util.Date / SimpleDateFormat
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
