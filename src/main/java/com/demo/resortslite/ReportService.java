package com.demo.resortslite;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cz-java-0057 fixed: Absolute paths replaced with environment variables injected
    // via Kubernetes ConfigMap for container-portable filesystem access on EKS.
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String REPORT_BASE_PATH; // cz-java-0057 fixed — was "/var/legacy/reports/"

    // cz-java-0057 fixed: Windows-style absolute path replaced with environment variable
    // injected via Kubernetes ConfigMap to support Linux-based container deployments.
    @Value("${BACKUP_PATH:/var/backups/nightly}")
    private String BACKUP_PATH; // cz-java-0057 fixed — was "C:\\ResortBackups\\nightly\\"

    // cz-java-0061 fixed: Hardcoded port replaced with environment variable injected via
    // Kubernetes ConfigMap, enabling flexible port assignment per environment in EKS/ECS.
    @Value("${SERVER_PORT:8080}")
    private int SERVER_PORT; // cz-java-0061 fixed — was: private static final int SERVER_PORT = 8080

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH);
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
            result.put("serverPort", SERVER_PORT);

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
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
