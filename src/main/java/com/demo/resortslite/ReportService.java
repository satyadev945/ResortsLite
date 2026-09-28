package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);

    // IMPROVED: Externalized paths to configuration for container compatibility
    // Default uses /tmp for container-friendly path
    @Value("${app.report.path:/tmp/reports/}")
    private String reportBasePath;
    
    @Value("${app.backup.path:/tmp/backups/}")
    private String backupPath;
    
    // IMPROVED: Externalized server port to configuration
    // Allows dynamic port assignment in container environments
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Generates a monthly report for the specified month and year.
     * @param month the month for the report
     * @param year the year for the report
     * @return a map containing the report generation status and details
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // IMPROVED: Input validation
        if (month == null || month.trim().isEmpty() || year == null || year.trim().isEmpty()) {
            logger.error("Invalid month or year provided: month={}, year={}", month, year);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Month and year cannot be null or empty");
            return error;
        }

        logger.info("Generating monthly report for: {}/{}", month, year);

        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(reportBasePath);
            if (!reportDir.exists()) {
                boolean created = reportDir.mkdirs();
                if (!created) {
                    logger.error("Failed to create report directory: {}", reportBasePath);
                    result.put("status", "error");
                    result.put("message", "Failed to create report directory");
                    return result;
                }
                logger.info("Created report directory: {}", reportBasePath);
            }

            try (FileWriter writer = new FileWriter(fullPath)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", serverPort);
            logger.info("Report generated successfully: {}", fullPath);

        } catch (IOException e) {
            logger.error("Failed to generate report for {}/{}", month, year, e);
            result.put("status", "error");
            result.put("message", "IO error: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error generating report for {}/{}", month, year, e);
            result.put("status", "error");
            result.put("message", "Unexpected error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the specified report.
     * @param reportName the name of the report
     * @return the download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // IMPROVED: Input validation
        if (reportName == null || reportName.trim().isEmpty()) {
            logger.error("Invalid report name provided: {}", reportName);
            return "";
        }

        // IMPROVED: Using HTTPS for cloud compatibility
        // In production, this should be externalized to configuration
        // Example: @Value("${app.report.download.baseurl}")
        String url = "https://reports.resorts-internal.com:" + serverPort + "/download/" + reportName;
        logger.debug("Generated download URL: {}", url);
        return url;
    }

    /**
     * Retrieves system information including paths and timestamps.
     * @return a map containing system information
     */
    public Map<String, Object> getSystemInfo() {
        logger.debug("Retrieving system information");
        
        // FIXED: Using java.time API instead of legacy Date/SimpleDateFormat
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        
        logger.debug("System info retrieved: {}", info);
        return info;
    }
}
