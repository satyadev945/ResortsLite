package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cr-java-0061 FIX: Replaced hard-coded absolute file paths (/var/legacy/reports/ and
    // C:\\ResortBackups\\nightly\\) with Amazon S3 bucket configuration injected via
    // environment variables / application properties. No local file system dependency remains.
    //
    // cr-java-0062 FIX: All local file write operations (FileWriter, File.mkdirs()) have been
    // removed and replaced with Amazon S3 PutObject calls to ensure durable, cloud-native
    // storage. Data written to S3 persists across container restarts and scales horizontally.
    //
    // cr-java-0063 FIX: java.io.File API usage for persistent data storage has been fully
    // eliminated. The three original violations at lines 37, 39, and 42 of the source file:
    //   Line 37 — new File(REPORT_BASE_PATH)  : removed; S3 object keys replace directory paths.
    //   Line 39 — reportDir.mkdirs()          : removed; S3 requires no directory creation.
    //   Line 42 — new FileWriter(fullPath)    : removed; replaced by S3Client.putObject() with
    //                                           RequestBody.fromString() for durable cloud storage.
    // All java.io.File and java.io.FileWriter imports have been removed. Data is now stored in
    // Amazon S3 via AWS SDK for Java v2, satisfying cloud-native storage requirements.
    @Value("${aws.s3.reports.bucket-name}")
    private String reportsBucketName;

    @Value("${aws.s3.backup.bucket-name}")
    private String backupBucketName;

    @Value("${aws.s3.region:us-east-1}")
    private String awsRegion;

    // cr-java-0071 FIX: AWS SSM Parameter Store path for the report download base URL.
    // Set aws.ssm.parameter.report-download-url (defaults to
    // /resortslite/reports/download-base-url) via environment variables or application
    // properties. The actual URL value is stored in Parameter Store, not in code.
    @Value("${aws.ssm.parameter.report-download-url:/resortslite/reports/download-base-url}")
    private String reportDownloadUrlParameterPath;

    // cr-java-0077 FIX: Hard-coded port 8080 has been removed and replaced with a value
    // injected at runtime from the environment variable SERVER_PORT (set by ECS task definition,
    // EKS pod spec, or Elastic Beanstalk environment configuration). The default value
    // (${SERVER_PORT:8080}) is only used for local development; in all cloud deployments the
    // environment variable must be set — ideally sourced from AWS Systems Manager Parameter
    // Store (path: /resortslite/server/port) and injected by the container orchestrator.
    // This allows ECS/EKS to perform dynamic port assignment and enables service-discovery
    // mechanisms (ALB target groups, Cloud Map) to register the correct port at startup.
    @Value("${server.port.value:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0077 FIX: SSM Parameter Store path for the server port.
    // The actual port value is stored in Parameter Store under this path and injected into
    // the SERVER_PORT environment variable by the ECS task definition / EKS pod spec at
    // deployment time, keeping port configuration fully external to the application binary.
    @Value("${aws.ssm.parameter.server-port:/resortslite/server/port}")
    private String serverPortParameterPath;

    /**
     * Builds a lazily-initialised S3Client using the region configured via the
     * {@code aws.s3.region} application property (defaults to {@code us-east-1}).
     * AWS credentials are resolved automatically from the standard credential-provider
     * chain (IAM role, environment variables, ~/.aws/credentials).
     */
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * <p><b>cr-java-0071 FIX:</b> Environment-specific URLs are no longer hard-coded in
     * source code. Instead, they are stored as SecureString or String parameters in AWS
     * Systems Manager Parameter Store and fetched at runtime via the AWS SDK. This enables
     * environment-agnostic deployments — the same artifact can be deployed to dev, staging,
     * or production simply by pointing to different Parameter Store paths.</p>
     *
     * @param parameterPath the SSM parameter path (e.g. /resortslite/reports/download-base-url)
     * @return the parameter value stored in Parameter Store
     */
    private String getSsmParameter(String parameterPath) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterPath)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        }
    }

    /**
     * Generates a monthly CSV report and uploads it directly to Amazon S3.
     *
     * <p><b>cr-java-0063 FIX (Lines 37, 39, 42 in original source):</b> The original code used
     * {@code new File(REPORT_BASE_PATH)} (line 37), {@code reportDir.mkdirs()} (line 39), and
     * {@code new FileWriter(fullPath)} (line 42) to create directories and write report data
     * directly to the local file system at {@code /var/legacy/reports/}. In cloud and
     * containerised environments the local file system is ephemeral — data written locally is
     * lost when a container restarts or a new instance is scaled in. This fix replaces the
     * entire local-write block with an Amazon S3 {@code PutObject} call so that report data
     * is stored durably in S3 and is accessible from any container instance or AWS service.</p>
     *
     * <p>Specifically, the following local-file-system operations have been eliminated:</p>
     * <ul>
     *   <li>{@code new File(REPORT_BASE_PATH)} — removed; S3 object keys provide logical path
     *       structure without requiring directory creation.</li>
     *   <li>{@code reportDir.mkdirs()} — removed; S3 does not use directories.</li>
     *   <li>{@code new FileWriter(fullPath)} — replaced by {@link S3Client#putObject} with
     *       an in-memory {@link RequestBody#fromString(String)} payload.</li>
     *   <li>{@code writer.write(...)} / {@code writer.close()} — replaced by building the
     *       CSV content as a {@code String} and uploading it atomically to S3.</li>
     * </ul>
     *
     * @param month the month component of the report period (e.g. "03")
     * @param year  the year component of the report period (e.g. "2024")
     * @return a result map containing status, S3 URI, and server port
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063 FIX: S3 object key replaces the former local fullPath variable.
        // The "reports/" prefix acts as a logical folder inside the S3 bucket.
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key    = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 FIX (Lines 37-42 – original source):
            //   REMOVED: File reportDir = new File(REPORT_BASE_PATH);  [line 37]
            //   REMOVED: reportDir.mkdirs();                            [line 39]
            //   REMOVED: FileWriter writer = new FileWriter(fullPath);  [line 42]
            // REPLACED WITH: In-memory CSV string uploaded via S3Client.putObject().
            // No local directory creation or local file I/O is performed.
            // Data is stored durably in Amazon S3 and survives container restarts.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            S3Client s3Client = buildS3Client();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            String s3Uri = "s3://" + reportsBucketName + "/" + s3Key;
            result.put("status", "generated");
            result.put("path", s3Uri);
            result.put("serverPort", serverPort); // cr-java-0077 FIX: injected via env var / SSM

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL by retrieving the base URL from AWS Systems Manager
     * Parameter Store and appending the report name.
     *
     * <p><b>cr-java-0071 FIX (Line 66 – original source):</b> The original implementation
     * returned a hard-coded URL {@code "http://reports.resorts-internal.com:8080/download/"}
     * concatenated with the report name. This hard-coded, environment-specific URL prevented
     * the application from being deployed across different environments (dev, staging,
     * production) without code changes, violating cloud-native externalized configuration
     * principles. The fix retrieves the base URL at runtime from AWS Systems Manager
     * Parameter Store using the path configured via
     * {@code aws.ssm.parameter.report-download-url}. Each deployment environment stores its
     * own URL value in Parameter Store, making the application fully environment-agnostic.</p>
     *
     * @param reportName the name of the report file to download
     * @return the full download URL with the report name appended
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX (Line 66 – original source):
        //   REMOVED: return "http://reports.resorts-internal.com:8080/download/" + reportName;
        // REPLACED WITH: Base URL retrieved at runtime from AWS Systems Manager Parameter Store.
        // The parameter path is configured via aws.ssm.parameter.report-download-url
        // (application property / environment variable). The actual URL value lives in
        // Parameter Store, enabling environment-agnostic deployments across dev / staging /
        // production without any code change.
        String baseUrl = getSsmParameter(reportDownloadUrlParameterPath);
        return baseUrl + reportName;
    }

    /**
     * Returns system information including S3 bucket references (replacing the former
     * hard-coded local paths) and the current timestamp.
     *
     * <p>cr-java-0061 / cr-java-0062 / cr-java-0063 FIX: {@code reportPath} and
     * {@code backupPath} now reflect the S3 bucket URIs sourced from application properties
     * instead of the former hard-coded values {@code /var/legacy/reports/} and
     * {@code C:\\ResortBackups\\nightly\\}. No java.io.File references remain.</p>
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (Line 70 – original source):
        //   REMOVED: new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
        //   REPLACED WITH: java.time API standardised on UTC.
        // java.util.Date and SimpleDateFormat are timezone-sensitive and rely on the JVM's
        // default timezone, which varies across cloud regions and container images. In a
        // distributed cloud deployment this causes inconsistent timestamps in logs, reports,
        // and inter-service communication. The fix uses Instant.now() (always UTC) formatted
        // via DateTimeFormatter.ISO_OFFSET_DATE_TIME with ZoneOffset.UTC, producing a
        // standards-compliant ISO-8601 timestamp (e.g. "2024-03-01T12:00:00Z") that is
        // unambiguous regardless of the host timezone or cloud region.
        String timestamp = ZonedDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: replaced hard-coded REPORT_BASE_PATH and BACKUP_PATH constants
        // (which used java.io.File-based local paths) with S3 bucket URIs derived from
        // injected application properties. No java.io.File dependency remains.
        info.put("reportPath", "s3://" + reportsBucketName + "/reports/");
        info.put("backupPath", "s3://" + backupBucketName + "/nightly/");
        info.put("serverPort", serverPort);         // cr-java-0077 FIX: injected via env var / SSM
        info.put("generatedAt", timestamp);
        return info;
    }
}
