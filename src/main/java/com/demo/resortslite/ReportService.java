package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import com.demo.resortslite.config.AwsParameterStoreConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED cr-java-0061: Replaced hard-coded file paths with S3 bucket configuration
    // S3 bucket name is externalized to application.properties and can be overridden via environment variables
    @Value("${aws.s3.bucket.name}")
    private String s3BucketName;

    @Value("${aws.s3.region}")
    private String awsRegion;

    @Autowired
    private AwsParameterStoreConfig parameterStoreConfig;

    // FIXED cr-java-0077: Replaced hard-coded port with environment variable injection
    // Port is now externalized to AWS Parameter Store and injected via environment variable
    // This enables dynamic port assignment by ECS, EKS, or Elastic Beanstalk
    // The port value can be overridden via SERVER_PORT environment variable or AWS Parameter Store
    @Value("${server.port:8080}")
    private int serverPort;

    private S3Client s3Client;

    /**
     * Initialize S3 client with default credentials provider.
     * In AWS cloud environment, credentials are automatically retrieved from IAM role.
     */
    private S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
        }
        return s3Client;
    }

    /**
     * Generate monthly report and upload to S3 bucket.
     * FIXED cr-java-0062: Replaced local file system write operations with S3 upload.
     * All report data is generated in-memory and uploaded directly to S3 for durable storage.
     * 
     * @param month Report month
     * @param year Report year
     * @return Map containing operation status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // FIXED cr-java-0062: Generate S3 key instead of local file path
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = "reports/" + year + "/" + month + "/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // FIXED cr-java-0062: Generate CSV content in-memory instead of writing to local file system
            // Using ByteArrayOutputStream ensures no local file system writes occur
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            
            // Generate CSV header and sample data
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            writer.close();

            // FIXED cr-java-0062: Upload directly to S3 instead of writing to local file system
            // This ensures data durability and availability in cloud/containerized environments
            byte[] reportContent = outputStream.toByteArray();
            
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .contentLength((long) reportContent.length)
                    .build();

            getS3Client().putObject(putObjectRequest, RequestBody.fromBytes(reportContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("s3Uri", "s3://" + s3BucketName + "/" + s3Key);
            result.put("fileName", fileName);
            result.put("serverPort", serverPort); // FIXED cr-java-0077

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", "S3 upload failed: " + e.awsErrorDetails().errorMessage());
            result.put("errorCode", e.awsErrorDetails().errorCode());
        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", "Report generation failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Build report download URL using externalized configuration.
     * FIXED cr-java-0071: Hard-coded environment URL replaced with AWS Systems Manager
     * Parameter Store configuration. URL is now externalized and environment-agnostic.
     * Parameter Store path: /resortslite/reports/download/url
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED cr-java-0071: Retrieve base URL from Parameter Store instead of hard-coding
        return parameterStoreConfig.getReportsDownloadUrl() + "/" + reportName;
    }

    /**
     * Get system information including S3 configuration.
     * FIXED cr-java-0062: Returns S3 bucket information instead of hard-coded file paths.
     * No local file system paths are exposed or used.
     * 
     * @return Map containing system configuration
     */
    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // FIXED cr-java-0111: Replaced java.util.Date/SimpleDateFormat with java.time API
        // Using Instant and UTC timezone to ensure consistent timestamps across distributed cloud environments
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // FIXED cr-java-0062: Return S3 bucket configuration instead of hard-coded paths
        info.put("storageType", "AWS S3");
        info.put("s3BucketName", s3BucketName);
        info.put("s3Region", awsRegion);
        info.put("serverPort", serverPort);        // FIXED cr-java-0077
        info.put("generatedAt", timestamp);
        return info;
    }
}
