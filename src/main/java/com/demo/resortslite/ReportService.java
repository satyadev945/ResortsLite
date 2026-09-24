package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation using Amazon S3.
 *
 * <p>cr-java-0063 remediation: All java.io.File-based persistent storage operations
 * (new File, mkdirs, FileWriter) have been replaced with Amazon S3 client calls
 * using AWS SDK for Java v2.  No host-level file system dependency remains.
 *
 * <p>cr-java-0061 remediation: Hard-coded absolute paths (/var/legacy/reports/,
 * C:\\ResortBackups\\nightly\\) replaced with environment-variable-backed S3
 * bucket name and prefix properties.
 *
 * <p>cr-java-0071 remediation: Hard-coded environment URL
 * "http://reports.resorts-internal.com:8080/download/" replaced with a value
 * injected from AWS Systems Manager Parameter Store via AwsSsmParameterStoreConfig,
 * enabling environment-agnostic deployments without code changes per environment.
 *
 * <p>cr-java-0077 remediation: Hard-coded port constant SERVER_PORT = 8080 replaced
 * with a value injected from the environment variable SERVER_PORT, which is backed by
 * AWS Systems Manager Parameter Store (/resortslite/server/port).  Container
 * orchestration platforms (ECS, EKS, Elastic Beanstalk) inject the port at runtime,
 * enabling dynamic port assignment and service-discovery compatibility.
 *
 * <p>cr-java-0111 remediation: java.util.Date and SimpleDateFormat (server-local timezone)
 * replaced with java.time.Instant standardized on UTC via ZoneOffset.UTC and
 * DateTimeFormatter.  Eliminates timezone inconsistencies across distributed cloud
 * containers and regions.
 */
@Service
public class ReportService {

    // cr-java-0061 / cr-java-0063: Replaced hard-coded absolute path
    // "/var/legacy/reports/" with an environment-variable-backed S3 bucket name.
    @Value("${aws.s3.bucket-name:resorts-reports-bucket}")
    private String s3BucketName;

    // cr-java-0061 / cr-java-0063: Replaced hard-coded Windows backup path
    // "C:\\ResortBackups\\nightly\\" with an environment-variable-backed S3 prefix.
    @Value("${aws.s3.backup-prefix:backups/nightly/}")
    private String s3BackupPrefix;

    // cr-java-0077: Replaced hard-coded port constant (SERVER_PORT = 8080) with a
    // Spring @Value field backed by the SERVER_PORT environment variable.
    // In AWS deployments the value is sourced from SSM Parameter Store
    // (/resortslite/server/port) and injected by ECS/EKS/Elastic Beanstalk at
    // container startup, enabling dynamic port assignment and service discovery.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    private final S3Client s3Client;

    // cr-java-0071: Replaced hard-coded URL "http://reports.resorts-internal.com:8080/download/"
    // with a value injected from AWS Systems Manager Parameter Store via AwsSsmParameterStoreConfig.
    // The actual URL is resolved at startup from SSM parameter /resortslite/reports/base-url,
    // enabling environment-agnostic deployments without code changes per environment.
    @Autowired
    @Qualifier("reportsBaseUrl")
    private String reportsBaseUrl;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0063: Replaced REPORT_BASE_PATH + fileName local file write with
        // an S3 object key — no java.io.File or FileWriter dependency.
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 (Line 37 original): Removed — new File(REPORT_BASE_PATH)
            // cr-java-0063 (Line 39 original): Removed — reportDir.mkdirs()
            // cr-java-0063 (Line 42 original): Removed — new FileWriter(fullPath)
            //
            // Replacement: Build CSV content in memory and upload directly to Amazon S3
            // using AWS SDK v2 — no local file system dependency.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort); // cr-java-0077: injected from env/SSM

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
        // cr-java-0071: Hard-coded URL "http://reports.resorts-internal.com:8080/download/"
        // replaced with reportsBaseUrl injected from AWS SSM Parameter Store.
        // SSM parameter: /resortslite/reports/base-url (configured in AwsSsmParameterStoreConfig).
        // This enables the same binary to be deployed across dev/staging/production environments
        // by updating the SSM parameter value — no code change or redeployment required.
        return reportsBaseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111: Replaced java.util.Date + SimpleDateFormat (server-local timezone)
        // with java.time.Instant standardized on UTC.  In distributed cloud deployments
        // (ECS, EKS, Lambda) each container may run in a different timezone; using
        // Instant.now() with ZoneOffset.UTC guarantees consistent, timezone-agnostic
        // timestamps across all regions and replicas.
        String timestamp = Instant.now()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 / cr-java-0063: Replaced hard-coded REPORT_BASE_PATH and
        // BACKUP_PATH with S3 bucket/prefix values sourced from environment variables.
        info.put("s3BucketName", s3BucketName);
        info.put("s3BackupPrefix", s3BackupPrefix);
        info.put("serverPort", serverPort);         // cr-java-0077: injected from env/SSM
        info.put("generatedAt", timestamp);
        return info;
    }
}
