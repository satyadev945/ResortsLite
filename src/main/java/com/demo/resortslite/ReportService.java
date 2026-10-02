package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cz-java-0057 [Fixed]: Hardcoded absolute path /var/legacy/reports/ replaced with
    // environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap on EKS.
    @Value("${app.report.base-path:#{systemEnvironment['REPORT_BASE_PATH'] ?: '/var/reports'}}")
    private String reportBasePath; // cz-java-0057 fixed (was: private static final String REPORT_BASE_PATH = "/var/legacy/reports/")

    // cz-java-0057 [Fixed]: Windows-style hardcoded absolute path C:\ResortBackups\nightly\
    // replaced with environment variable BACKUP_PATH injected via Kubernetes ConfigMap on EKS.
    @Value("${app.backup.path:#{systemEnvironment['BACKUP_PATH'] ?: '/var/backups/resorts'}}")
    private String backupPath; // cz-java-0057 fixed (was: private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\")

    // cz-java-0061 [Fixed]: Hardcoded port 8080 replaced with environment-variable-driven
    // configuration via Kubernetes ConfigMap / EKS Pod spec. serverPort is now injected
    // through the SERVER_PORT environment variable (default: 8080), enabling flexible
    // port assignment per environment without code changes.
    @Value("${server.port:#{systemEnvironment['SERVER_PORT'] ?: '8080'}}")
    private int serverPort; // cz-java-0061 fixed (was: private static final int SERVER_PORT = 8080)

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + "/" + fileName; // cz-java-0057 fixed

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(reportBasePath); // cz-java-0057 fixed
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
            result.put("serverPort", serverPort); // cz-java-0061 fixed

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);  // cz-java-0057 fixed
        info.put("backupPath", backupPath);       // cz-java-0057 fixed
        info.put("serverPort", serverPort);      // cz-java-0061 fixed
        info.put("generatedAt", timestamp);
        return info;
    }
}
