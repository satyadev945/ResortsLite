package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cr-java-0061 FIX: Replaced hardcoded absolute file path "/var/legacy/reports/" with
    // Amazon S3 bucket configuration injected via environment variable / application property.
    @Value("${cloud.aws.s3.bucket-name:resorts-reports-bucket}")
    private String s3BucketName;

    // cr-java-0061 FIX: Replaced Windows-style hardcoded backup path "C:\\ResortBackups\\nightly\\"
    // with an S3 key prefix injected via environment variable / application property.
    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String s3BackupPrefix;

    // cr-java-0077 FIX: Replaced hard-coded port constant (8080) with a @Value-injected field.
    // The value is resolved from the SERVER_PORT environment variable at runtime, which is set
    // by ECS task definitions, EKS pod specs, or Elastic Beanstalk environment properties.
    // AWS Systems Manager Parameter Store can supply the value via the SSM agent or Spring Cloud AWS.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071 FIX: SsmParameterStoreConfig bean is injected to retrieve environment-specific
    // URLs from AWS Systems Manager Parameter Store instead of hardcoding them in source code.
    @Autowired
    private SsmParameterStoreConfig ssmParameterStoreConfig;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063 FIX (Line 37): Replaced java.io.File(REPORT_BASE_PATH) directory object
        // with an Amazon S3 object key. java.io.File assumes a local, persistent file system
        // which does not exist in ephemeral cloud/container environments.
        // Original: String fullPath = REPORT_BASE_PATH + fileName;
        //           File reportDir = new File(REPORT_BASE_PATH);   <-- LINE 37 violation
        String s3Key = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 FIX (Line 39): Removed reportDir.exists() / reportDir.mkdirs() calls.
            // S3 is a flat key-value store — no directory creation is needed. The concept of
            // "creating a directory" (mkdirs) is replaced by simply writing an object with a
            // key prefix, eliminating the java.io.File dependency entirely.
            // Original: if (!reportDir.exists()) { reportDir.mkdirs(); }  <-- LINE 39 violation

            // cr-java-0063 FIX (Line 42): Replaced FileWriter(fullPath) with Amazon S3 PutObject.
            // Original: FileWriter writer = new FileWriter(fullPath);      <-- LINE 42 violation
            //           writer.write(...); writer.close();
            // All local disk writes are now routed to S3 via the AWS SDK v2 S3Client.
            // S3Client uses the default AWS credential provider chain (IAM role / env vars),
            // ensuring zero hardcoded credentials and full cloud-native compatibility.
            S3Client s3Client = S3Client.builder()
                    .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                    .build();

            // cr-java-0063 FIX: Report content assembled in-memory (replaces FileWriter.write calls).
            String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // cr-java-0063 FIX: PutObjectRequest targets the S3 bucket/key instead of a local path.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            // cr-java-0063 FIX: s3Client.putObject replaces FileWriter.write + FileWriter.close.
            // Data is durably stored in S3 — survives container restarts and horizontal scaling.
            s3Client.putObject(putRequest, RequestBody.fromString(reportContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort); // cr-java-0077 FIX: uses injected env-var value

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
        // cr-java-0071 FIX (Line 66): Replaced hard-coded environment-specific URL
        // "http://reports.resorts-internal.com:8080/download/" with a value retrieved
        // from AWS Systems Manager Parameter Store via SsmParameterStoreConfig.
        // The SSM parameter key "/resortslite/reports/download-base-url" holds the correct
        // base URL for each deployment environment (dev, staging, production), enabling
        // environment-agnostic deployments without any code changes between environments.
        String reportDownloadBaseUrl = ssmParameterStoreConfig.getParameter("/resortslite/reports/download-base-url");
        return reportDownloadBaseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 FIX (Line 70): Replaced java.util.Date + SimpleDateFormat with java.time API.
        // Instant.now() always returns UTC time, eliminating server-local timezone dependency.
        // DateTimeFormatter.ISO_OFFSET_DATE_TIME with ZoneOffset.UTC produces a standardized
        // ISO-8601 timestamp (e.g. "2024-03-01T12:00:00Z") safe for distributed cloud environments.
        String timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: Replaced hardcoded REPORT_BASE_PATH and BACKUP_PATH references
        // (java.io.File-based local paths) with S3 bucket/prefix values sourced from
        // environment-injected properties — no local file system dependency remains.
        info.put("s3ReportBucket", s3BucketName);
        info.put("s3BackupPrefix", s3BackupPrefix);
        info.put("serverPort", serverPort);         // cr-java-0077 FIX: uses injected env-var value
        info.put("generatedAt", timestamp);
        return info;
    }
}
