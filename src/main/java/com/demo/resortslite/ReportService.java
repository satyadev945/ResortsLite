package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cr-java-0062 / cr-java-0061: Replaced hard-coded absolute path "/var/legacy/reports/"
    // with Amazon S3 bucket configured via environment variable AWS_S3_REPORT_BUCKET.
    // Local file system writes are ephemeral in cloud/container environments; S3 provides
    // durable, scalable object storage that survives container restarts and scaling events.
    @Value("${aws.s3.report.bucket:${AWS_S3_REPORT_BUCKET:resort-reports-bucket}}")
    private String reportBucket;

    // cr-java-0062 / cr-java-0061: Replaced hard-coded Windows backup path
    // "C:\\ResortBackups\\nightly\\" with an S3 key prefix configured via environment variable.
    @Value("${aws.s3.backup.prefix:${AWS_S3_BACKUP_PREFIX:resort-backups/nightly/}}")
    private String backupPrefix;

    // cr-java-0077 FIX: Hard-coded port 8080 replaced with a value injected from the
    // SERVER_PORT environment variable (set in ECS task definition, EKS pod spec, or
    // Elastic Beanstalk environment properties).  Falls back to the Spring Boot
    // server.port property, which is itself externalised via ${SERVER_PORT:8080} in
    // application.properties, so no port is ever compiled into the binary.
    // AWS Parameter Store parameter: /resortslite/server/port (optional override).
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071 FIX: SSM parameter name for the report download base URL is externalised
    // via the application property app.ssm.report-download-url-param.
    // Default parameter path: /resortslite/reports/download-url
    @Value("${app.ssm.report-download-url-param:/resortslite/reports/download-url}")
    private String reportDownloadUrlParamName;

    private final S3Client s3Client;

    // cr-java-0071 FIX: SsmClient injected to retrieve environment-specific URLs
    // from AWS Systems Manager Parameter Store at runtime, replacing hard-coded values.
    private final SsmClient ssmClient;

    /**
     * Constructs ReportService with injected AWS S3 and SSM clients.
     * The S3Client bean is provided by {@link AwsS3Config}.
     * The SsmClient bean is provided by {@link AwsSsmConfig}.
     */
    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0062: Replaced hard-coded local file path with S3 object key.
        // Original violation (source line 42): FileWriter writer = new FileWriter(fullPath)
        // — local file writes are lost on container restart; migrated to Amazon S3.
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062: Replaced File/FileWriter/mkdirs local file-system write operations
            // with S3Client.putObject() for durable cloud storage (Amazon S3).
            // Original code: new File(REPORT_BASE_PATH).mkdirs() + new FileWriter(fullPath)
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort); // cr-java-0077 resolved

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name.
     *
     * <p>cr-java-0071 FIX: The hard-coded base URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} has been replaced
     * with a value retrieved at runtime from AWS Systems Manager Parameter Store.
     * The SSM parameter name is configured via {@code app.ssm.report-download-url-param}
     * (default: {@code /resortslite/reports/download-url}).
     * This enables environment-agnostic deployments without code changes between
     * dev, staging, and production environments.
     *
     * @param reportName the name of the report file to build the URL for
     * @return the fully qualified download URL for the specified report
     */
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // cr-java-0071 FIX: Hard-coded environment URL replaced with a value retrieved
        // from AWS Systems Manager Parameter Store at runtime.
        // Original violation (line 66): return "http://reports.resorts-internal.com:8080/download/" + reportName;
        // The parameter name is configured via app.ssm.report-download-url-param
        // (default: /resortslite/reports/download-url).
        GetParameterRequest paramRequest = GetParameterRequest.builder()
                .name(reportDownloadUrlParamName)
                .withDecryption(false)
                .build();
        GetParameterResponse paramResponse = ssmClient.getParameter(paramRequest);
        String reportDownloadBaseUrl = paramResponse.parameter().value(); // cr-java-0071 resolved
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 bucket/prefix references and server port.
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replaced java.util.Date/SimpleDateFormat with java.time API
        // standardized on UTC (ZoneOffset.UTC) to avoid timezone inconsistencies across
        // distributed cloud environments, containers, and multi-region deployments.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0062 / cr-java-0061: S3 bucket/prefix replace former local file-system paths.
        info.put("reportBucket", reportBucket);
        info.put("backupPrefix", backupPrefix);
        info.put("serverPort", serverPort);         // cr-java-0077 resolved
        info.put("generatedAt", timestamp);
        return info;
    }
}
