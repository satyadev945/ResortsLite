package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // All timestamps are standardised on UTC.
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'");

    // Report and backup locations are now S3 key prefixes (bucket/prefix) configured through
    // environment variables instead of hard-coded local file system paths.
    private final String reportBucket;
    private final String reportPrefix;
    private final String backupPrefix;

    // S3 client is created lazily using the default AWS credential/region provider chain
    // (IAM role, environment variables, etc.).
    private volatile S3Client s3Client;

    // Server port is externalised: resolved from the SERVER_PORT environment variable
    // (injected from AWS Systems Manager Parameter Store by ECS/EKS/Elastic Beanstalk) via server.port.
    private final int serverPort;

    public ReportService(
            @Value("${app.report.s3.bucket:resorts-reports}") String reportBucket,
            @Value("${app.report.s3.prefix:reports/}") String reportPrefix,
            @Value("${app.report.s3.backup-prefix:backups/nightly/}") String backupPrefix,
            @Value("${server.port}") int serverPort) {
        this.serverPort = serverPort;
        this.reportBucket = reportBucket;
        this.reportPrefix = reportPrefix;
        this.backupPrefix = backupPrefix;
    }

    private S3Client s3() {
        S3Client client = s3Client;
        if (client == null) {
            synchronized (this) {
                client = s3Client;
                if (client == null) {
                    client = S3Client.create();
                    s3Client = client;
                }
            }
        }
        return client;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String objectKey = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csv.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csv.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();
            s3().putObject(request, RequestBody.fromString(csv.toString()));

            result.put("status", "generated");
            result.put("path", "s3://" + reportBucket + "/" + objectKey);
            result.put("serverPort", serverPort);

        } catch (RuntimeException e) {
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
        String timestamp = ZonedDateTime.now(Clock.systemUTC()).withZoneSameInstant(ZoneOffset.UTC).format(TIMESTAMP_FORMAT);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", "s3://" + reportBucket + "/" + reportPrefix);
        info.put("backupPath", "s3://" + reportBucket + "/" + backupPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
