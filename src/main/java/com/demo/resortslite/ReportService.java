package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService generates monthly resort reports and stores them in Amazon S3.
 *
 * <p>All local file-system write operations (FileWriter, File.mkdirs) that previously
 * wrote to hard-coded absolute paths (/var/legacy/reports, C:\ResortBackups\nightly)
 * have been replaced with Amazon S3 object-storage operations using AWS SDK for Java v2.
 * This eliminates ephemeral local-storage dependencies (cr-java-0062) and ensures data
 * durability, availability, and scalability in containerised and serverless environments.</p>
 *
 * <p>The hard-coded server port (cr-java-0077) has been replaced with an environment-variable-
 * backed {@code @Value} injection sourced from the {@code SERVER_PORT} environment variable,
 * which can be set via AWS Systems Manager Parameter Store and injected at runtime by
 * ECS task definitions, EKS pod specs, or Elastic Beanstalk environment properties.</p>
 */
@Service
public class ReportService {

    /**
     * S3 bucket name injected from the {@code aws.s3.bucket-name} property,
     * which is backed by the {@code AWS_S3_BUCKET_NAME} environment variable.
     * Replaces the former hard-coded REPORT_BASE_PATH "/var/legacy/reports/" (cr-java-0062).
     */
    @Value("${aws.s3.bucket-name}")
    private String s3BucketName;

    /**
     * AWS region injected from the {@code aws.s3.region} property,
     * which is backed by the {@code AWS_REGION} environment variable.
     */
    @Value("${aws.s3.region}")
    private String s3Region;

    /**
     * S3 key prefix for monthly reports.
     * Replaces the former hard-coded REPORT_BASE_PATH "/var/legacy/reports/" (cr-java-0062).
     */
    @Value("${aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    /**
     * S3 key prefix for nightly backups.
     * Replaces the former hard-coded BACKUP_PATH "C:\\ResortBackups\\nightly\\" (cr-java-0062).
     */
    @Value("${aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    /**
     * Server port injected from the {@code server.port} property, which is backed by the
     * {@code SERVER_PORT} environment variable (cr-java-0077).
     *
     * <p>Replaces the former {@code private static final int SERVER_PORT = 8080} constant.
     * The value is externalised to AWS Systems Manager Parameter Store and injected at
     * runtime through environment variables in ECS task definitions, EKS pod specs, or
     * Elastic Beanstalk environment properties, enabling dynamic port assignment required
     * by container orchestration platforms and cloud service discovery mechanisms.</p>
     */
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Builds an {@link S3Client} using the default AWS credentials chain
     * (environment variables → instance profile → ECS task role → etc.).
     *
     * @return a configured {@link S3Client} instance
     */
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(s3Region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Generates a monthly resort report CSV and uploads it to Amazon S3.
     *
     * <p>Previously this method wrote the CSV to the local file system using
     * {@code FileWriter} targeting the hard-coded path {@code REPORT_BASE_PATH}
     * (cr-java-0062, original line 42). The local write has been replaced with
     * an in-memory CSV build followed by a direct {@code S3Client.putObject} call,
     * ensuring data is persisted durably in S3 regardless of container restarts
     * or horizontal scaling.</p>
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string  (e.g. "2024")
     * @return result map containing status, S3 object key, and S3 URI
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key — replaces the former local fullPath (cr-java-0062, original line 37)
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        // Build CSV content entirely in memory.
        // No local File / FileWriter / mkdirs needed (cr-java-0062, original line 42).
        StringBuilder csvContent = new StringBuilder();
        csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
        csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
        csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

        // Upload the in-memory CSV directly to Amazon S3.
        // Replaces: new File(REPORT_BASE_PATH).mkdirs() + new FileWriter(fullPath).write(...)
        // (cr-java-0062 — original lines 38-45)
        try (S3Client s3Client = buildS3Client()) {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromString(csvContent.toString()));

            String s3Uri = "s3://" + s3BucketName + "/" + s3Key;
            result.put("status", "generated");
            result.put("s3Key", s3Key);
            result.put("s3Uri", s3Uri);
            // cr-java-0077 fix: serverPort is now injected via @Value / environment variable
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the HTTPS report download URL for the given report name.
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    /**
     * Returns system information including S3 storage locations.
     *
     * <p>Hard-coded local paths replaced with S3 bucket/prefix references (cr-java-0062).
     * Hard-coded server port replaced with environment-variable-backed injection (cr-java-0077).</p>
     *
     * @return map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix: replaced java.util.Date / SimpleDateFormat with java.time API
        // standardised on UTC (ZoneOffset.UTC) for consistent timestamps across distributed
        // cloud environments and multiple regions/containers.
        String timestamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // S3 URIs replace the former hard-coded local paths (cr-java-0062)
        info.put("reportPath", "s3://" + s3BucketName + "/" + reportPrefix);   // was: REPORT_BASE_PATH
        info.put("backupPath", "s3://" + s3BucketName + "/" + backupPrefix);   // was: BACKUP_PATH
        // cr-java-0077 fix: serverPort is now injected via @Value / environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
