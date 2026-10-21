package com.demo.resortslite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);

    // FIXED cr-java-0061: Replaced hard-coded file paths with S3 configuration
    // Using environment variables and Spring properties for cloud-native configuration
    @Value("${aws.s3.bucket.name}")
    private String s3BucketName;

    @Value("${aws.s3.region}")
    private String awsRegion;

    @Value("${aws.s3.reports.prefix}")
    private String reportsPrefix;

    @Value("${aws.s3.backups.prefix}")
    private String backupsPrefix;

    // FIXED cr-java-0071: Externalized report download base URL to AWS Systems Manager Parameter Store
    @Value("${app.reports.download.base.url}")
    private String reportsDownloadBaseUrl;

    // FIXED cr-java-0077: Replaced hard-coded port with AWS Parameter Store and environment variable injection
    // Port is now dynamically assigned by container orchestration (ECS/EKS) via SERVER_PORT environment variable
    // Configure in AWS Parameter Store: /config/resortslite/server.port
    // Or set via environment variable: SERVER_PORT
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    private S3Client s3Client;
    private S3Presigner s3Presigner;

    /**
     * Initialize S3 client with AWS SDK v2.
     * Uses DefaultCredentialsProvider which supports:
     * - Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
     * - System properties
     * - AWS credentials file (~/.aws/credentials)
     * - IAM role for EC2/ECS/EKS (recommended for production)
     */
    @PostConstruct
    public void initS3Client() {
        logger.info("Initializing S3 client for region: {} and bucket: {}", awsRegion, s3BucketName);
        this.s3Client = S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        this.s3Presigner = S3Presigner.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Clean up S3 client resources on service shutdown.
     */
    @PreDestroy
    public void closeS3Client() {
        logger.info("Closing S3 client");
        if (s3Client != null) {
            s3Client.close();
        }
        if (s3Presigner != null) {
            s3Presigner.close();
        }
    }

    /**
     * Generates a monthly report and stores it in Amazon S3.
     * FIXED cr-java-0062: Replaced local file system write operations with S3 storage for durability.
     *
     * @param month The month for the report
     * @param year The year for the report
     * @return Map containing status and S3 object details
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = reportsPrefix + fileName;
        logger.info("Generating monthly report for {}/{} and uploading to S3: s3://{}/{}", month, year, s3BucketName, s3Key);

        Map<String, Object> result = new HashMap<>();

        try {
            // Generate CSV content in memory instead of writing to local file system
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            writer.close();

            byte[] reportContent = outputStream.toByteArray();

            // FIXED cr-java-0062: Upload to S3 instead of local file system write operations
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(reportContent));

            logger.info("Successfully uploaded report to S3: s3://{}/{}", s3BucketName, s3Key);
            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("s3Uri", "s3://" + s3BucketName + "/" + s3Key);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            logger.error("S3 error while uploading report: {}", e.awsErrorDetails().errorMessage(), e);
            result.put("status", "error");
            result.put("message", "S3 error: " + e.awsErrorDetails().errorMessage());
            result.put("errorCode", e.awsErrorDetails().errorCode());
        } catch (IOException e) {
            logger.error("IO error while generating report: {}", e.getMessage(), e);
            result.put("status", "error");
            result.put("message", "IO error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Generates a presigned URL for secure download of a report from S3.
     * FIXED cr-java-0063: Provides cloud-native secure access to S3-stored reports.
     * 
     * @param reportName The name of the report file
     * @param expirationMinutes URL expiration time in minutes (default: 60)
     * @return Presigned URL for secure download
     */
    public String generatePresignedDownloadUrl(String reportName, int expirationMinutes) {
        String s3Key = reportsPrefix + reportName;
        logger.info("Generating presigned URL for S3 object: s3://{}/{} with expiration: {} minutes", 
                    s3BucketName, s3Key, expirationMinutes);
        
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .build();
            
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(expirationMinutes))
                    .getObjectRequest(getObjectRequest)
                    .build();
            
            return s3Presigner.presignGetObject(presignRequest).url().toString();
        } catch (S3Exception e) {
            logger.error("Error generating presigned URL: {}", e.awsErrorDetails().errorMessage(), e);
            return null;
        }
    }

    /**
     * Builds a report download URL.
     * FIXED cr-java-0071: Replaced hard-coded URL with externalized configuration from AWS Systems Manager Parameter Store
     * The base URL is now retrieved from Parameter Store via Spring @Value annotation
     * Configure in AWS Parameter Store: /config/resortslite/app.reports.download.base.url
     * 
     * @param reportName The name of the report
     * @return Download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED cr-java-0071: Using externalized base URL from AWS Systems Manager Parameter Store
        return reportsDownloadBaseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information including S3 configuration.
     * FIXED cr-java-0062: Returns S3 bucket and prefix information instead of local file paths.
     *
     * @return Map containing system configuration details
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED cr-java-0111: Replaced java.util.Date/SimpleDateFormat with java.time API (Instant) and standardized on UTC
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        
        // FIXED cr-java-0062: Return S3 configuration instead of hard-coded file paths
        info.put("s3BucketName", s3BucketName);
        info.put("reportsPrefix", reportsPrefix);
        info.put("backupsPrefix", backupsPrefix);
        info.put("awsRegion", awsRegion);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        
        return info;
    }
}
