package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

// PostgreSQL: binary/LOB data uses BYTEA via getBytes()/setBytes(); no SQL Server IMAGE columns are referenced.
@Service
public class ReportService {

    private static final Clock CLOCK = Clock.systemUTC();

    // Reports and backups are stored in Amazon S3 rather than local file system paths.
    private final S3Client s3Client;
    private final String bucket;
    private final String reportPrefix;
    private final String backupPrefix;

    // Server port is externalized: sourced from the SERVER_PORT environment variable, which is
    // injected at runtime from AWS Systems Manager Parameter Store (ECS/EKS/Elastic Beanstalk).
    private final int serverPort;

    public ReportService(S3Client s3Client,
                         @Value("${app.reports.s3.bucket:resorts-reports}") String bucket,
                         @Value("${app.reports.s3.report-prefix:reports/}") String reportPrefix,
                         @Value("${app.reports.s3.backup-prefix:backups/nightly/}") String backupPrefix,
                         @Value("${server.port:8080}") int serverPort) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.reportPrefix = reportPrefix;
        this.backupPrefix = backupPrefix;
        this.serverPort = serverPort;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String content = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("text/csv")
                            .build(),
                    RequestBody.fromString(content, StandardCharsets.UTF_8));

            result.put("status", "generated");
            result.put("path", "s3://" + bucket + "/" + key);
            result.put("serverPort", serverPort);

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
        // Standardized on UTC via java.time Clock to avoid server-local timezone dependence.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(ZonedDateTime.now(CLOCK.withZone(ZoneOffset.UTC)));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", "s3://" + bucket + "/" + reportPrefix);
        info.put("backupPath", "s3://" + bucket + "/" + backupPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
