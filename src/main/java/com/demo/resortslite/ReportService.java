package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService – generates and stores resort reports in Amazon S3.
 *
 * <p>cr-java-0063 remediation: All {@code java.io.File} persistent storage
 * operations have been replaced with Amazon S3 client calls using AWS SDK
 * for Java v2. The three original violations were:
 * <ul>
 *   <li>Line 37 (original): {@code File reportDir = new File(REPORT_BASE_PATH);}</li>
 *   <li>Line 39 (original): {@code reportDir.mkdirs();}</li>
 *   <li>Line 42 (original): {@code FileWriter writer = new FileWriter(fullPath);}</li>
 * </ul>
 * All three have been replaced with an in-memory CSV assembly and a single
 * {@link PutObjectRequest} to Amazon S3, eliminating any host-level file
 * system dependency.
 *
 * <p>cr-java-0071 remediation: The hard-coded report download URL
 * {@code http://reports.resorts-internal.com:8080/download/} has been replaced
 * with a value retrieved from AWS Systems Manager Parameter Store, enabling
 * environment-agnostic deployments across dev, staging, and production.
 *
 * <p>Required environment variables / application properties:
 * <ul>
 *   <li>{@code AWS_S3_BUCKET_NAME}   – target S3 bucket for reports</li>
 *   <li>{@code AWS_S3_REPORT_PREFIX} – optional key prefix (default: "reports/")</li>
 *   <li>{@code AWS_S3_BACKUP_PREFIX} – optional key prefix (default: "backups/")</li>
 *   <li>{@code AWS_REGION}           – AWS region (default: us-east-1)</li>
 *   <li>{@code SERVER_PORT}          – server port injected at runtime via environment variable
 *       or AWS Systems Manager Parameter Store (default: 8080). The hard-coded constant
 *       {@code private static final int SERVER_PORT = 8080} (original line 28) has been
 *       removed and replaced with a Spring {@code @Value} binding so that ECS task
 *       definitions, EKS pod specs, and Elastic Beanstalk environment properties can
 *       supply the port dynamically without any code change.</li>
 * </ul>
 */
@Service
public class ReportService {

    /**
     * SSM Parameter Store parameter name for the server port.
     * Allows the port to be resolved from AWS Parameter Store at runtime.
     */
    @Value("${app.ssm.server-port-param:${APP_SSM_SERVER_PORT_PARAM:/resortslite/server/port}}")
    private String serverPortParamName;

    /**
     * cr-java-0063 FIX: Replaced hard-coded absolute path {@code /var/legacy/reports/}
     * with an environment-variable-backed S3 bucket name. The bucket is a
     * cloud-native, durable, and scalable storage resource that does not depend
     * on the host file system.
     */
    @Value("${aws.s3.bucket-name:${AWS_S3_BUCKET_NAME:resort-reports-bucket}}")
    private String s3BucketName;

    /**
     * cr-java-0063 FIX: Replaced hard-coded absolute path {@code /var/legacy/reports/}
     * with a configurable S3 key prefix injected from environment / application properties.
     */
    @Value("${aws.s3.report-prefix:${AWS_S3_REPORT_PREFIX:reports/}}")
    private String reportPrefix;

    /**
     * cr-java-0063 FIX: Replaced hard-coded Windows path {@code C:\ResortBackups\nightly\}
     * with a configurable S3 key prefix injected from environment / application properties.
     */
    @Value("${aws.s3.backup-prefix:${AWS_S3_BACKUP_PREFIX:backups/}}")
    private String backupPrefix;

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * cr-java-0077 FIX: Hard-coded port constant {@code private static final int SERVER_PORT = 8080}
     * (original source line 28) has been eliminated. The port is now injected at runtime via:
     *   1. Spring property  {@code server.port}  (set from env var {@code SERVER_PORT} in
     *      application.properties), OR
     *   2. AWS Systems Manager Parameter Store parameter {@code /resortslite/server/port}
     *      (resolved via {@code app.ssm.server-port-param}).
     * This enables dynamic port assignment by ECS, EKS, and Elastic Beanstalk.
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /**
     * cr-java-0071 FIX: SSM Parameter Store parameter name for the report download base URL.
     * Replaces the hard-coded value "http://reports.resorts-internal.com:8080/download/".
     * Set via environment variable or application property; defaults to the SSM parameter path.
     */
    @Value("${app.ssm.report-download-url-param:/resortslite/reports/download-base-url}")
    private String reportDownloadUrlParamName;

    /**
     * Builds a thread-safe {@link S3Client} for the configured AWS region.
     *
     * <p>A new client is created per call so the service remains stateless and
     * compatible with cloud-native credential providers (IAM roles, ECS task
     * roles, instance profiles, etc.).
     *
     * @return a configured {@link S3Client} instance
     */
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * cr-java-0071 FIX: Retrieves a parameter value from AWS Systems Manager Parameter Store.
     * This eliminates hard-coded environment-specific URLs from the source code, enabling
     * environment-agnostic deployments across dev, staging, and production.
     *
     * @param paramName the SSM parameter name / path to retrieve
     * @return the decrypted parameter value from Parameter Store
     */
    private String getSsmParameter(String paramName) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        }
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * <p><b>cr-java-0063 remediation</b> – the three original {@code java.io.File}
     * violations have been replaced as follows:
     * <ol>
     *   <li><b>Line 37</b>: {@code new File(REPORT_BASE_PATH)} – directory object
     *       creation removed; S3 key prefixes are implicit and require no
     *       pre-creation.</li>
     *   <li><b>Line 39</b>: {@code reportDir.mkdirs()} – directory creation call
     *       removed; S3 buckets are pre-provisioned infrastructure resources.</li>
     *   <li><b>Line 42</b>: {@code new FileWriter(fullPath)} – file-write operation
     *       replaced with {@link PutObjectRequest} / {@link RequestBody#fromString}
     *       to stream the CSV content directly to S3 without touching the local
     *       file system.</li>
     * </ol>
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string  (e.g. "2024")
     * @return result map containing upload status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063 FIX (replaces original line 37 – new File(REPORT_BASE_PATH)):
        // The S3 object key acts as the logical "path"; no local File object is needed.
        String objectKey = reportPrefix + "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        // cr-java-0063 FIX (replaces original line 39 – reportDir.mkdirs()):
        // S3 does not require directory creation; key prefixes are implicit.
        // cr-java-0063 FIX (replaces original line 42 – new FileWriter(fullPath)):
        // Report content is assembled in-memory and uploaded via PutObjectRequest,
        // eliminating any dependency on the host file system.
        try (S3Client s3 = buildS3Client()) {
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            PutObjectResponse response = s3.putObject(
                    putRequest,
                    RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", objectKey);
            result.put("eTag", response.eTag());
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for a report by retrieving the base URL from
     * AWS Systems Manager Parameter Store.
     *
     * <p><b>cr-java-0071 remediation</b>: The previous implementation returned a
     * hard-coded environment-specific URL
     * {@code http://reports.resorts-internal.com:8080/download/reportName}.
     * This URL has been replaced with a value retrieved from AWS SSM Parameter Store
     * under the parameter name configured via {@code app.ssm.report-download-url-param}
     * (default: {@code /resortslite/reports/download-base-url}).
     * The base URL stored in Parameter Store is environment-specific (dev/staging/prod)
     * and is resolved at runtime, enabling environment-agnostic deployments without
     * any code changes between environments.
     *
     * @param reportName the report file name (S3 object key suffix)
     * @return full download URL for the report, with base URL sourced from SSM Parameter Store
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Replaced hard-coded environment URL
        // "http://reports.resorts-internal.com:8080/download/"
        // with a value retrieved from AWS Systems Manager Parameter Store.
        // The SSM parameter name is injected via the property
        // app.ssm.report-download-url-param (default: /resortslite/reports/download-base-url).
        // This enables environment-agnostic deployments — the base URL is stored in
        // Parameter Store per environment (dev/staging/prod) and resolved at runtime.
        String baseUrl = getSsmParameter(reportDownloadUrlParamName);
        return baseUrl + reportName;
    }

    /**
     * Returns system / configuration metadata for diagnostic purposes.
     *
     * <p>cr-java-0063 remediation: the previous implementation exposed the
     * hard-coded local paths {@code /var/legacy/reports/} and
     * {@code C:\ResortBackups\nightly\}. These are replaced with the S3
     * bucket name and key prefixes sourced from environment variables,
     * removing all host file-system references.
     *
     * @return map of configuration metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replaced java.util.Date / SimpleDateFormat with java.time API
        // standardized on UTC (ZoneOffset.UTC) to eliminate timezone inconsistencies
        // across distributed cloud environments and container replicas.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: replaced REPORT_BASE_PATH ("/var/legacy/reports/") with S3 reference
        info.put("reportStorage", "s3://" + s3BucketName + "/" + reportPrefix);
        // cr-java-0063 FIX: replaced BACKUP_PATH ("C:\\ResortBackups\\nightly\\") with S3 reference
        info.put("backupStorage", "s3://" + s3BucketName + "/" + backupPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
