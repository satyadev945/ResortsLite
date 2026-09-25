package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated: Replaced legacy java.util.Date + SimpleDateFormat with java.time API (JAVA8_TO_21_DATE_TIME_CHANGES)
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Fix czr-java-001 [Software Portability]: Hardcoded absolute path /var/legacy/reports
    // replaced with environment-variable-backed property. Set APP_REPORT_BASE_PATH to an
    // S3 path, Azure Blob path, or mounted volume path in container/cloud environments.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // Fix czr-java-001: Windows-style hardcoded backup path C:\\ResortBackups\\nightly\\
    // replaced with environment-variable-backed property. Set APP_BACKUP_PATH in the
    // container task definition or Kubernetes Secret/ConfigMap.
    @Value("${app.backup.path:/tmp/backups/}")
    private String backupPath;

    // Fix czr-port-001 [Software Portability]: Hardcoded SERVER_PORT constant removed.
    // Port is now read from the environment via ${PORT:8080} in application.properties
    // and injected here for any logic that needs to reference it.
    @Value("${server.port:8080}")
    private int serverPort;

    // Fix cr-java-0088: Report download base URL externalised to environment variable.
    // Set APP_REPORT_DOWNLOAD_URL in AWS Parameter Store / ECS task definition.
    @Value("${app.report.download-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    /**
     * Generates a monthly booking report as a CSV file.
     *
     * @param month the month for which the report is generated (e.g., "03")
     * @param year  the year for which the report is generated (e.g., "2024")
     * @return a map containing the report generation status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Fix czr-java-001: reportBasePath is now injected from env var — no hardcoded path.
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(reportBasePath);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            // try-with-resources for proper resource management (Java 7+ best practice)
            try (FileWriter writer = new FileWriter(fullPath)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath);
            // Fix czr-port-001: serverPort now injected from env var, not a hardcoded constant.
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a given report name.
     *
     * @param reportName the name of the report file
     * @return the full download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // Fix cr-java-0088 [Cloud Compatibility]: Plain HTTP URL replaced with HTTPS endpoint
        // injected from environment variable (app.report.download-url). Cloud security
        // standards (AWS WAF, ALB) enforce HTTPS — plain HTTP calls are blocked or flagged.
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including report paths and current timestamp.
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: Replaced legacy java.util.Date + SimpleDateFormat with java.time API
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // Fix czr-java-001: paths now come from injected env-var-backed properties.
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        // Fix czr-port-001: port now injected from env var.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
