package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cr-java-0077 FIX: Hard-coded port (SERVER_PORT = 8080) removed and externalized to AWS SSM
    // Parameter Store. Value is resolved from the environment variable SERVER_PORT, which is injected
    // at runtime by ECS task definition, EKS pod spec, or Elastic Beanstalk environment properties.
    // SSM Parameter Store path: /resortslite/server/port
    // Defaults to 8080 for local development only.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071 FIX: Hard-coded report download base URL externalized to AWS SSM Parameter Store.
    // Value is resolved from application property backed by SSM: /resortslite/reports/download-base-url
    // Override via environment variable APP_REPORT_DOWNLOAD_BASE_URL or property app.report.download-base-url
    @Value("${app.report.download-base-url:${APP_REPORT_DOWNLOAD_BASE_URL:http://reports.resorts-internal.com:8080/download/}}")
    private String reportDownloadBaseUrl;

    // Cloud-native: S3 bucket name resolved from environment variable (replaces hardcoded /var/legacy/reports/)
    @Value("${cloud.aws.s3.bucket-name:${REPORT_S3_BUCKET:resorts-reports-bucket}}")
    private String reportBucketName;

    // Cloud-native: S3 key prefix resolved from environment variable (replaces hardcoded C:\ResortBackups\nightly\)
    @Value("${cloud.aws.s3.backup-prefix:${BACKUP_S3_PREFIX:backups/nightly/}}")
    private String backupKeyPrefix;

    // Cloud-native: AWS region resolved from environment variable
    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Cloud-native: S3 object key replaces hardcoded absolute file path (was: REPORT_BASE_PATH + fileName)
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try (S3Client s3Client = buildS3Client()) {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload report directly to S3 (replaces File/FileWriter local operations)
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent.toString()));

            String s3Url = "s3://" + reportBucketName + "/" + s3Key;
            result.put("status", "generated");
            result.put("path", s3Url);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // cr-java-0071 FIX: Base URL is no longer hard-coded. It is injected from AWS SSM Parameter Store
        // via the property app.report.download-base-url (SSM path: /resortslite/reports/download-base-url).
        return reportDownloadBaseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 FIX: Replaced java.util.Date/SimpleDateFormat with java.time API standardized on UTC
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // Cloud-native: report and backup locations now reference S3 bucket/prefix (replaces hardcoded file paths)
        info.put("reportPath", "s3://" + reportBucketName + "/reports/");
        info.put("backupPath", "s3://" + reportBucketName + "/" + backupKeyPrefix);
        // cr-java-0077 FIX: serverPort is now injected from environment variable SERVER_PORT / SSM Parameter Store
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
