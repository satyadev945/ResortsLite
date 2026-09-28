package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED cr-java-0063: Replaced hard-coded file paths with S3 configuration
    // Using environment variables and Spring properties for cloud-native configuration
    @Value("${aws.s3.bucket.name}")
    private String s3BucketName;

    @Value("${aws.s3.region}")
    private String awsRegion;

    @Value("${aws.s3.reports.prefix}")
    private String reportsPrefix;

    @Value("${aws.s3.backups.prefix}")
    private String backupsPrefix;

    // FIXED cr-java-0071: Externalized report download URL to AWS Systems Manager Parameter Store
    @Value("${app.reports.download.baseurl}")
    private String reportsDownloadBaseUrl;

    @Value("${aws.s3.endpoint:}")
    private String s3Endpoint;

    // FIXED cr-java-0077: Removed hard-coded port default, now uses environment variable
    // Port should be set via SERVER_PORT environment variable or AWS Parameter Store
    @Value("${server.port}")
    private int serverPort;

    private S3Client s3Client;

    /**
     * Initialize S3 client with AWS SDK v2
     * Uses DefaultCredentialsProvider which supports:
     * - Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
     * - System properties
     * - AWS credentials file
     * - IAM role for EC2/ECS/EKS (recommended for production)
     */
    @PostConstruct
    public void initS3Client() {
        try {
            software.amazon.awssdk.services.s3.S3ClientBuilder builder = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(DefaultCredentialsProvider.create());

            // Support custom S3 endpoint for LocalStack or S3-compatible services
            if (s3Endpoint != null && !s3Endpoint.isEmpty()) {
                builder.endpointOverride(URI.create(s3Endpoint));
            }

            s3Client = builder.build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize S3 client", e);
        }
    }

    /**
     * Clean up S3 client resources
     */
    @PreDestroy
    public void closeS3Client() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    /**
     * Generate monthly report and store in Amazon S3
     * FIXED cr-java-0063: Replaced file system operations with S3 PutObject
     * 
     * @param month Report month
     * @param year Report year
     * @return Map containing operation status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // FIXED cr-java-0063 Line 37: Replaced java.io.File(REPORT_BASE_PATH) with S3 key prefix
        String s3Key = reportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // FIXED cr-java-0063 Lines 37-42: Replaced File/FileWriter operations with S3 PutObject
            // Upload to S3 using AWS SDK v2
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            // FIXED cr-java-0063 Line 42: Return S3 location instead of file path
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("s3Uri", "s3://" + s3BucketName + "/" + s3Key);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", "S3 error: " + e.awsErrorDetails().errorMessage());
            result.put("errorCode", e.awsErrorDetails().errorCode());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Build report download URL
     * FIXED cr-java-0088: Changed to HTTPS for cloud security compliance
     * 
     * @param reportName Name of the report file
     * @return HTTPS URL for report download
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED cr-java-0071: Using externalized configuration from AWS Systems Manager Parameter Store
        return reportsDownloadBaseUrl + "/download/" + reportName;
    }

    /**
     * Get system information including S3 configuration
     * FIXED cr-java-0063: Return S3 configuration instead of file paths
     * 
     * @return Map containing S3 bucket and prefix information
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        
        // FIXED cr-java-0063: Return S3 configuration instead of hard-coded paths
        info.put("s3BucketName", s3BucketName);
        info.put("s3Region", awsRegion);
        info.put("reportsPrefix", reportsPrefix);
        info.put("backupsPrefix", backupsPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("storageType", "Amazon S3");
        
        return info;
    }
}
