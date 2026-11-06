package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // NOTE: Hardcoded absolute paths break containerisation.
    // These should be externalised to environment variables or use cloud object storage
    // (S3 / Azure Blob). Kept here for backward compatibility; override via env vars.
    private static final String REPORT_BASE_PATH = System.getenv().getOrDefault(
            "REPORT_BASE_PATH", "/var/legacy/reports/");

    // NOTE: Windows-style backup path replaced with a cross-platform default.
    // Override via environment variable for OS-specific deployments.
    private static final String BACKUP_PATH = System.getenv().getOrDefault(
            "BACKUP_PATH", "/var/legacy/backups/nightly/");

    // NOTE: Server port should be managed by the container orchestrator (ECS/EKS).
    // Kept as a reference value; actual binding is controlled by server.port property.
    private static final int SERVER_PORT = Integer.parseInt(
            System.getenv().getOrDefault("SERVER_PORT", "8080"));

    /**
     * Generates a monthly booking report as a CSV file.
     *
     * <p>Uses try-with-resources to ensure the {@link FileWriter} is always closed,
     * and specifies {@link StandardCharsets#UTF_8} explicitly to avoid platform-default
     * charset issues introduced in Java 18+ (JEP 400).</p>
     *
     * @param month the month label (e.g., "March")
     * @param year  the four-digit year (e.g., "2024")
     * @return a result map containing status and file path, or an error message
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        Path reportDir = Paths.get(REPORT_BASE_PATH);
        Path fullPath = reportDir.resolve(fileName);

        Map<String, Object> result = new HashMap<>();

        try {
            // Create report directory if it does not exist
            Files.createDirectories(reportDir);

            // Fixed: FileWriter now uses explicit UTF-8 charset (Java 18+ default-charset change)
            // Fixed: try-with-resources ensures FileWriter is always closed (resource leak fix)
            try (FileWriter writer = new FileWriter(fullPath.toFile(), StandardCharsets.UTF_8)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath.toString());
            result.put("serverPort", SERVER_PORT);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report.
     *
     * <p>NOTE: The URL scheme should be HTTPS in production. The base URL should be
     * externalised to an environment variable for cloud deployments.</p>
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // NOTE: Use HTTPS and externalise the base URL via environment variable
        // for cloud-native deployments (cloud security standards enforce HTTPS).
        String baseUrl = System.getenv().getOrDefault(
                "REPORT_DOWNLOAD_BASE_URL", "https://reports.resorts-internal.com:8443/download/");
        return baseUrl + reportName;
    }

    /**
     * Returns system information including report paths and current timestamp.
     *
     * <p>Replaced legacy {@link java.util.Date} and {@link java.text.SimpleDateFormat}
     * with the modern {@link java.time.LocalDateTime} and {@link java.time.format.DateTimeFormatter}
     * API introduced in Java 8 and recommended for Java 25+.</p>
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Replaced java.util.Date + SimpleDateFormat with java.time.LocalDateTime + DateTimeFormatter
        // (modern date-time API, thread-safe, no mutable state)
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String timestamp = LocalDateTime.now().format(formatter);

        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
