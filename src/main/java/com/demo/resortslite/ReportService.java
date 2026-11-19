package com.demo.resortslite;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute path.
    // /var/legacy/reports does not exist in a Docker container image. Breaks containerisation.
    // Must use volume mounts, cloud object storage (S3 / Azure Blob), or environment variable.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/"; // czr-java-001

    // Reports are stored in Amazon S3 (bucket configurable via REPORT_S3_BUCKET env var).
    private static final String REPORT_BUCKET =
            System.getenv().getOrDefault("REPORT_S3_BUCKET", "resortslite-reports");
    private static final String REPORT_KEY_PREFIX =
            System.getenv().getOrDefault("REPORT_S3_PREFIX", "reports/");

    // S3 client uses the default AWS region/credentials provider chains (IAM role, env vars).
    // Created lazily so application startup does not fail when AWS config is absent.
    private volatile S3Client s3Client;

    private S3Client s3() {
        if (s3Client == null) {
            synchronized (this) {
                if (s3Client == null) {
                    s3Client = S3Client.create();
                }
            }
        }
        return s3Client;
    }

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Windows-style absolute path
    // will fail on any Linux-based container or cloud host. Hard dependency on OS path structure.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\"; // czr-java-001

    // VIOLATION [Software Portability / High]: Fixed server port hardcoded in application logic.
    // Container orchestration (ECS / EKS) dynamically assigns ports. Hardcoded ports prevent
    // dynamic port binding required for modern container deployment and service discovery.
    private static final int SERVER_PORT = 8080; // czr-port-001

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String objectKey = REPORT_KEY_PREFIX + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csv.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csv.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Persist report to Amazon S3 instead of the local file system
            s3().putObject(
                    PutObjectRequest.builder()
                            .bucket(REPORT_BUCKET)
                            .key(objectKey)
                            .contentType("text/csv")
                            .build(),
                    RequestBody.fromString(csv.toString(), StandardCharsets.UTF_8));

            result.put("status", "generated");
            result.put("path", "s3://" + REPORT_BUCKET + "/" + objectKey);
            result.put("serverPort", SERVER_PORT); // czr-port-001

        } catch (SdkException e) {
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
        info.put("reportPath", REPORT_BASE_PATH);  // czr-java-001
        info.put("backupPath", BACKUP_PATH);        // czr-java-001
        info.put("serverPort", SERVER_PORT);        // czr-port-001
        info.put("generatedAt", timestamp);
        return info;
    }
}
