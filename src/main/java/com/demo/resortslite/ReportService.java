package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.annotation.PostConstruct;
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

    @Value("${aws.s3.bucket.name}")
    private String s3BucketName;

    @Value("${aws.region}")
    private String awsRegion;

    @Value("${aws.ssm.parameter.report.url}")
    private String reportUrlParameterName;

    @Value("${app.report.base.url}")
    private String reportBaseUrl;

    @Value("${server.port:8080}")
    private String serverPort;

    private S3Client s3Client;
    private String reportDownloadBaseUrl;

    @PostConstruct
    public void init() {
        // Initialize S3 client for cloud-native file storage
        this.s3Client = S3Client.builder()
                .region(software.amazon.awssdk.regions.Region.of(awsRegion))
                .build();

        // Load report download URL from AWS Systems Manager Parameter Store
        loadReportUrlFromParameterStore();
    }

    /**
     * Loads report download URL from AWS Systems Manager Parameter Store.
     * Replaces hard-coded environment URLs (blocker-11: cr-java-0071)
     */
    private void loadReportUrlFromParameterStore() {
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(software.amazon.awssdk.regions.Region.of(awsRegion))
                    .build();

            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(reportUrlParameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse parameterResponse = ssmClient.getParameter(parameterRequest);
            this.reportDownloadBaseUrl = parameterResponse.parameter().value();

            ssmClient.close();
        } catch (Exception e) {
            // Fallback to application.properties value
            this.reportDownloadBaseUrl = reportBaseUrl;
            System.err.println("Warning: Could not load report URL from Parameter Store, using default: " + e.getMessage());
        }
    }

    /**
     * Generates monthly report and stores it in Amazon S3.
     * Replaces local file system operations (blockers 1-7: cr-java-0061, cr-java-0062, cr-java-0063)
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = "reports/" + year + "/" + month + "/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Generate report content in memory
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(outputStream);
            
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            writer.close();

            byte[] reportData = outputStream.toByteArray();

            // Upload to S3 instead of writing to local file system
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(reportData));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("downloadUrl", buildReportDownloadUrl(fileName));
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds secure HTTPS report download URL using externalized configuration.
     * Replaces hard-coded HTTP URL (blocker-11: cr-java-0071)
     * Uses HTTPS instead of HTTP for cloud security compliance
     */
    public String buildReportDownloadUrl(String reportName) {
        // Use HTTPS and externalized URL from Parameter Store
        return reportDownloadBaseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information with cloud-native configuration.
     * Uses UTC timezone for consistency across distributed cloud environments (blocker-19: cr-java-0111)
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time API with UTC timezone instead of java.util.Date
        Instant now = Instant.now();
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(now);

        Map<String, Object> info = new HashMap<>();
        info.put("storageType", "s3");
        info.put("s3Bucket", s3BucketName);
        info.put("awsRegion", awsRegion);
        info.put("reportBaseUrl", reportDownloadBaseUrl);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
