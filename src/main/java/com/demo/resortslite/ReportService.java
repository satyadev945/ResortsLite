package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
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

    // FIXED cz-java-0057: Replaced hardcoded absolute paths with environment-configurable paths
    // that can be mounted to EFS volumes in ECS Fargate tasks
    @Value("${app.report.path:/mnt/efs/reports}")
    private String reportBasePath;

    @Value("${app.backup.path:/mnt/efs/backups}")
    private String backupPath;

    // FIXED cz-java-0061: Replaced hardcoded port with environment variable injection
    // Port is now configurable via SERVER_PORT environment variable from AWS Secrets Manager
    // or ECS task definition, enabling flexible container deployment
    @Autowired
    private Environment environment;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + "/" + fileName;

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
            // FIXED cz-java-0061: Retrieve port from environment configuration
            String serverPort = environment.getProperty("server.port", "8080");
            result.put("serverPort", serverPort);

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
        // FIXED cz-java-0061: Use environment-configured port instead of hardcoded value
        String serverPort = environment.getProperty("server.port", "8080");
        return "http://reports.resorts-internal.com:" + serverPort + "/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        // FIXED cz-java-0061: Retrieve port from environment configuration
        String serverPort = environment.getProperty("server.port", "8080");
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
