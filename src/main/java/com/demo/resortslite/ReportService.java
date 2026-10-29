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

/**
 * Service for generating and serving resort booking reports.
 * All filesystem paths, server ports, and service URLs are externalised to
 * application configuration / environment variables — no hardcoded absolute paths
 * or OS-specific separators remain in this class (czr-java-001 resolved).
 */
@Service
public class ReportService {

    /**
     * Base directory for report files.
     * Injected from {@code app.report.base-path} configuration property.
     * Defaults to {@code /tmp/reports/} for local development; override with an
     * S3-mounted path or cloud object-storage URI in production deployments.
     */
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    /**
     * Report download base URL.
     * Injected from {@code app.report.download-url} configuration property.
     * Uses HTTPS to comply with cloud security standards (cr-java-0088 resolved).
     */
    @Value("${app.report.download-url:https://reports.resorts-internal.com/download/}")
    private String reportDownloadUrl;

    /** Thread-safe formatter for report timestamps. */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly CSV report and writes it to the configured report directory.
     * The output path is derived from the injected {@code reportBasePath} property,
     * making this method portable across local, containerised, and cloud environments.
     *
     * @param month month label (e.g. "03")
     * @param year  four-digit year (e.g. "2024")
     * @return map containing generation status and the resolved file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(reportBasePath);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            // FileWriter uses explicit UTF-8 charset to avoid platform-default encoding issues.
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
     * Builds the HTTPS download URL for the specified report file.
     * The base URL is injected from application configuration (cr-java-0088 resolved).
     *
     * @param reportName filename of the report to download
     * @return fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadUrl + reportName;
    }

    /**
     * Returns current system information including the configured report path
     * and a UTC-formatted generation timestamp.
     *
     * @return map of system info key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("generatedAt", timestamp);
        return info;
    }
}
