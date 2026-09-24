package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated from java.util.Date / java.text.SimpleDateFormat to java.time API
// (JAVA8_TO_21_DATE_TIME_CHANGES: legacy date/time → java.time)
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // czr-java-001 fix: Hardcoded absolute paths replaced with configurable property.
    // Value is injected from application.properties (app.report.base-path) which itself
    // reads from the REPORT_BASE_PATH environment variable, defaulting to /tmp/reports/.
    // This allows container/cloud deployments to mount the correct volume path at runtime.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // cr-java-0088 fix: Report download base URL externalised to application properties.
    // Supports HTTPS enforcement in cloud environments without code changes.
    @Value("${app.report.download-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    // czr-port-001 fix: Removed hardcoded SERVER_PORT constant.
    // Port is managed by Spring Boot via server.port property (${PORT:8080}).

    /**
     * Generates a monthly booking report as a CSV file.
     * Uses java.time API (LocalDateTime) instead of legacy java.util.Date.
     * (JAVA8_TO_21_DATE_TIME_CHANGES)
     * czr-java-001 fix: Report path resolved from injected property, not hardcoded constant.
     *
     * @param month the month for the report (e.g. "03")
     * @param year  the year for the report (e.g. "2024")
     * @return a map containing the report status and file path
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
     * Builds a download URL for the given report name.
     * cr-java-0088 fix: URL base is injected from application properties, supporting
     * HTTPS enforcement in cloud environments without code changes.
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0088 fix: URL constructed from injected property (defaults to HTTPS).
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including report paths and current timestamp.
     * Uses java.time.LocalDateTime (thread-safe) instead of legacy SimpleDateFormat.
     * (JAVA8_TO_21_DATE_TIME_CHANGES: SimpleDateFormat.format(new Date()) → DateTimeFormatter)
     * czr-java-001 fix: Paths resolved from injected properties, not hardcoded constants.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated from java.util.Date / SimpleDateFormat to java.time.LocalDateTime
        // (JAVA8_TO_21_DATE_TIME_CHANGES: legacy date/time API → java.time API)
        // DateTimeFormatter is thread-safe; SimpleDateFormat was NOT thread-safe.
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("generatedAt", timestamp);
        return info;
    }
}
