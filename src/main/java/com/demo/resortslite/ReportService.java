package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
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

    // Replaced hard-coded absolute path "/var/legacy/reports/" (cr-java-0061) with
    // environment-variable-backed S3 bucket name for cloud-native object storage.
    @Value("${cloud.aws.s3.reports-bucket:resorts-reports-bucket}")
    private String reportsBucketName;

    // Replaced hard-coded Windows path "C:\\ResortBackups\\nightly\\" (cr-java-0061) with
    // environment-variable-backed S3 bucket name for cloud-native object storage.
    @Value("${cloud.aws.s3.backup-bucket:resorts-backup-bucket}")
    private String backupBucketName;

    // cr-java-0077 FIX: Hard-coded port 8080 (line 28) replaced with AWS Systems Manager
    // Parameter Store lookup. The SSM parameter name is resolved from the environment variable
    // SERVER_PORT_PARAM_NAME (or the Spring property app.ssm.server-port-param).
    // At runtime the actual port value is fetched from AWS SSM Parameter Store so no
    // hard-coded port ever appears in source code or version control, enabling dynamic port
    // assignment required by ECS, EKS, and Elastic Beanstalk container orchestration.
    @Value("${app.ssm.server-port-param:${SERVER_PORT_PARAM_NAME:/resortslite/server/port}}")
    private String serverPortParamName;

    // Replaced hard-coded AWS region with environment-variable-backed value.
    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    // cr-java-0071 FIX: SSM Parameter Store parameter name for the report download base URL.
    // The parameter name is resolved from the environment variable REPORT_URL_PARAM_NAME
    // (or the Spring property app.ssm.report-download-url-param).  At runtime the actual
    // base URL is fetched from AWS Systems Manager Parameter Store so no environment-specific
    // URL ever appears in source code or version control.
    @Value("${app.ssm.report-download-url-param:${REPORT_URL_PARAM_NAME:/resortslite/reports/download-base-url}}")
    private String reportDownloadUrlParamName;

    /**
     * Builds an S3Client using the configured AWS region.
     * The client uses the default credential provider chain (IAM role / env vars / ~/.aws/credentials).
     */
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Retrieves the server port from AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0077 FIX: Replaces the hard-coded port constant {@code SERVER_PORT = 8080}
     * (original line 28) with a dynamic SSM Parameter Store lookup. This enables container
     * orchestration platforms (ECS, EKS, Elastic Beanstalk) to assign ports dynamically
     * without requiring code changes or redeployment.</p>
     *
     * <p>The SSM parameter name defaults to {@code /resortslite/server/port} and can be
     * overridden via the environment variable {@code SERVER_PORT_PARAM_NAME}.</p>
     *
     * @return the server port value stored in AWS SSM Parameter Store
     */
    private int getServerPortFromSsm() {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(serverPortParamName)
                    .withDecryption(false)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return Integer.parseInt(response.parameter().value());
        }
    }

    /**
     * Retrieves the report download base URL from AWS Systems Manager Parameter Store.
     * Replaces the hard-coded URL "http://reports.resorts-internal.com:8080/download/"
     * (cr-java-0071, line 66) with a dynamic lookup so the same artifact can be deployed
     * to dev, staging, and production without code changes.
     *
     * @return the report download base URL stored in Parameter Store
     */
    private String getReportDownloadBaseUrlFromSsm() {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(reportDownloadUrlParamName)
                    .withDecryption(false)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        }
    }

    /**
     * Generates a monthly report CSV and uploads it to Amazon S3.
     * Replaces the previous local-file-system write to "/var/legacy/reports/" (cr-java-0061,
     * lines 23, 37, 42) with an S3 PutObject operation using AWS SDK for Java v2.
     *
     * @param month the month for the report (e.g. "03")
     * @param year  the year for the report  (e.g. "2024")
     * @return a result map containing status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // Line 37 fix: replaced "REPORT_BASE_PATH + fileName" local path with an S3 object key
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        // Line 42 fix: replaced "new File(REPORT_BASE_PATH)" / mkdirs() with S3 PutObject
        // Line 23 fix: REPORT_BASE_PATH constant removed; bucket name comes from @Value
        String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        try (S3Client s3 = buildS3Client()) {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucketName)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3.putObject(putRequest, RequestBody.fromString(csvContent));

            // cr-java-0077 FIX: server port is now fetched from AWS SSM Parameter Store
            // instead of using the hard-coded constant SERVER_PORT = 8080.
            result.put("status", "generated");
            result.put("bucket", reportsBucketName);
            result.put("key", objectKey);
            result.put("s3Uri", "s3://" + reportsBucketName + "/" + objectKey);
            result.put("serverPort", getServerPortFromSsm());

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL by retrieving the base URL from AWS Systems Manager
     * Parameter Store (cr-java-0071).
     * Replaces the hard-coded environment-specific URL
     * "http://reports.resorts-internal.com:8080/download/" with a dynamic SSM lookup,
     * enabling environment-agnostic deployments without code changes.
     *
     * @param reportName the name of the report object
     * @return the full download URL for the report, with base URL sourced from SSM
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Hard-coded environment URL replaced with AWS Systems Manager
        // Parameter Store lookup.  The parameter /resortslite/reports/download-base-url
        // (or the name configured via REPORT_URL_PARAM_NAME) holds the actual base URL,
        // enabling environment-agnostic deployments without code changes.
        String baseUrl = getReportDownloadBaseUrlFromSsm(); // cr-java-0071
        // Ensure the base URL ends with a slash before appending the report name
        if (!baseUrl.endsWith("/")) {
            baseUrl = baseUrl + "/";
        }
        return baseUrl + reportName;
    }

    /**
     * Returns system information using cloud-native configuration values.
     * Replaces hard-coded REPORT_BASE_PATH and BACKUP_PATH (cr-java-0061, line 23)
     * with S3 bucket references sourced from environment variables.
     * cr-java-0077 FIX: server port is now fetched from AWS SSM Parameter Store.
     * cr-java-0111 FIX: timestamp now uses java.time API (Instant + DateTimeFormatter)
     * standardized on UTC, replacing legacy java.util.Date / SimpleDateFormat.
     *
     * @return a map of system information entries
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replaced java.util.Date / SimpleDateFormat with java.time API.
        // Instant.now() captures the current moment in UTC, and DateTimeFormatter formats it
        // in ISO-8601 UTC (e.g. "2024-03-15T10:30:00Z"), ensuring consistent timestamps
        // across all cloud regions and container instances without timezone drift.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // Replaced REPORT_BASE_PATH ("/var/legacy/reports/") with S3 bucket reference
        info.put("reportsBucket", reportsBucketName);
        // Replaced BACKUP_PATH ("C:\\ResortBackups\\nightly\\") with S3 bucket reference
        info.put("backupBucket", backupBucketName);
        // cr-java-0077 FIX: server port fetched from AWS SSM Parameter Store
        info.put("serverPort", getServerPortFromSsm());
        info.put("generatedAt", timestamp);
        return info;
    }
}
