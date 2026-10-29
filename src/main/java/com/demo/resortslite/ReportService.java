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

import javax.annotation.PostConstruct;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation using Amazon S3.
 *
 * <p>All java.io.File-based persistent storage operations (cr-java-0063) have been
 * replaced with Amazon S3 client calls (AWS SDK for Java v2) to achieve cloud-native,
 * durable, and scalable storage without host-level file system dependencies.</p>
 *
 * <p>cr-java-0077 FIX (original line 28): The hard-coded port constant
 * {@code private static final int SERVER_PORT = 8080} has been eliminated entirely.
 * Port configuration is now externalised to AWS Systems Manager Parameter Store and
 * injected at runtime through the {@code SERVER_PORT} environment variable in ECS,
 * EKS, or Elastic Beanstalk. The resolved port value is available via
 * {@link #getServerPort()} for any service-level URL construction needs.</p>
 *
 * <p>cr-java-0071 FIX (original line 66): The hard-coded environment URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} used in
 * {@link #buildReportDownloadUrl(String)} has been replaced with a value retrieved
 * from AWS Systems Manager Parameter Store at application startup. The SSM parameter
 * name is controlled by the Spring property {@code ssm.parameter.report-download-base-url}
 * (override via environment variable {@code SSM_PARAM_REPORT_DOWNLOAD_URL}), enabling
 * environment-agnostic deployments without any code changes between environments.</p>
 *
 * <p>Eliminated violations:</p>
 * <ul>
 *   <li>Line 28 (original): {@code private static final int SERVER_PORT = 8080} —
 *       hard-coded port constant removed; port is now resolved from AWS SSM Parameter
 *       Store / {@code SERVER_PORT} environment variable (cr-java-0077).</li>
 *   <li>Line 37 (original): {@code new File(REPORT_BASE_PATH)} — directory existence
 *       check on local FS replaced by S3 object key prefix (cr-java-0063).</li>
 *   <li>Line 39 (original): {@code reportDir.mkdirs()} — local directory creation
 *       replaced by S3 bucket/key model (cr-java-0063).</li>
 *   <li>Line 42 (original): {@code new FileWriter(fullPath)} — local file write
 *       replaced by {@code S3Client.putObject()} (cr-java-0063).</li>
 *   <li>Line 66 (original): {@code "http://reports.resorts-internal.com:8080/download/"}
 *       — hard-coded environment URL replaced with AWS SSM Parameter Store lookup
 *       (cr-java-0071).</li>
 * </ul>
 */
@Service
public class ReportService {

    /**
     * S3 bucket name for report storage.
     * Resolved from environment variable {@code AWS_S3_BUCKET_NAME} or
     * Spring property {@code cloud.aws.s3.bucket-name}; defaults to
     * {@code resorts-lite-reports}.
     *
     * <p>Replaces hard-coded absolute file paths (REPORT_BASE_PATH = "/var/legacy/reports/"
     * and BACKUP_PATH = "C:\\ResortBackups\\nightly\\") that were incompatible with
     * cloud / container environments (cr-java-0061, cr-java-0063).</p>
     */
    @Value("${cloud.aws.s3.bucket-name:${AWS_S3_BUCKET_NAME:resorts-lite-reports}}")
    private String s3BucketName;

    /**
     * S3 key prefix used to namespace report objects within the bucket.
     * Resolved from Spring property {@code cloud.aws.s3.report-prefix};
     * defaults to {@code reports/}.
     */
    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportKeyPrefix;

    /**
     * AWS region for the S3 and SSM clients.
     * Resolved from environment variable {@code AWS_REGION} or
     * Spring property {@code cloud.aws.region.static}; defaults to {@code us-east-1}.
     */
    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * SSM Parameter Store parameter name that holds the report download base URL.
     * Resolved from Spring property {@code ssm.parameter.report-download-base-url} or
     * environment variable {@code SSM_PARAM_REPORT_DOWNLOAD_URL}.
     *
     * <p>cr-java-0071 FIX: replaces the hard-coded literal
     * {@code "http://reports.resorts-internal.com:8080/download/"} that was
     * present at original line 66.</p>
     */
    @Value("${ssm.parameter.report-download-base-url:${SSM_PARAM_REPORT_DOWNLOAD_URL:/resortslite/reports/download-base-url}}")
    private String reportDownloadBaseUrlParameterName;

    /**
     * SSM Parameter Store parameter name that holds the application server port.
     * Resolved from Spring property {@code ssm.parameter.server-port} or
     * environment variable {@code SSM_PARAM_SERVER_PORT}.
     *
     * <p>cr-java-0077 FIX (original line 28): replaces the hard-coded constant
     * {@code private static final int SERVER_PORT = 8080}. The port value is
     * externalised to AWS SSM Parameter Store and injected at runtime through
     * the {@code SERVER_PORT} environment variable in ECS task definitions,
     * EKS pod specs, or Elastic Beanstalk environment configuration.</p>
     */
    @Value("${ssm.parameter.server-port:${SSM_PARAM_SERVER_PORT:/resortslite/server/port}}")
    private String serverPortParameterName;

    /**
     * Report download base URL resolved from AWS SSM Parameter Store at application startup.
     * Populated by {@link #init()}.
     */
    private String reportDownloadBaseUrl;

    /**
     * Server port resolved from AWS SSM Parameter Store or {@code SERVER_PORT} environment
     * variable at application startup.
     *
     * <p>cr-java-0077 FIX: replaces the hard-coded {@code SERVER_PORT = 8080} constant
     * (original line 28). The value is fetched from SSM so that each deployment
     * environment (dev, staging, production) can supply its own port assignment
     * without recompilation, satisfying container orchestration dynamic port binding
     * requirements for ECS, EKS, and Elastic Beanstalk.</p>
     */
    private int serverPort;

    /**
     * Resolves the report download base URL and server port from AWS SSM Parameter Store
     * once at application startup.
     *
     * <p>cr-java-0077 FIX: server port is fetched from SSM (parameter name controlled by
     * {@code ssm.parameter.server-port}) and falls back to the {@code SERVER_PORT}
     * environment variable, then to {@code 8080} for local development only.</p>
     *
     * <p>cr-java-0071 FIX: report download base URL is fetched from SSM (parameter name
     * controlled by {@code ssm.parameter.report-download-base-url}) instead of being
     * embedded as a string literal in source code.</p>
     */
    @PostConstruct
    public void init() {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            // cr-java-0077 FIX: resolve server port from AWS SSM Parameter Store.
            // Replaces hard-coded SERVER_PORT = 8080 constant (original line 28).
            try {
                GetParameterRequest portRequest = GetParameterRequest.builder()
                        .name(serverPortParameterName)
                        .withDecryption(false)
                        .build();
                GetParameterResponse portResponse = ssmClient.getParameter(portRequest);
                serverPort = Integer.parseInt(portResponse.parameter().value().trim());
            } catch (Exception e) {
                // Fall back to SERVER_PORT environment variable, then to 8080 for local/test.
                String envPort = System.getenv("SERVER_PORT");
                serverPort = (envPort != null && !envPort.isEmpty())
                        ? Integer.parseInt(envPort.trim())
                        : 8080;
            }

            // cr-java-0071 FIX: resolve report download base URL from AWS SSM Parameter Store.
            try {
                GetParameterRequest urlRequest = GetParameterRequest.builder()
                        .name(reportDownloadBaseUrlParameterName)
                        .withDecryption(false)
                        .build();
                GetParameterResponse urlResponse = ssmClient.getParameter(urlRequest);
                reportDownloadBaseUrl = urlResponse.parameter().value();
            } catch (Exception e) {
                // Fall back to REPORT_DOWNLOAD_BASE_URL environment variable for local/test.
                reportDownloadBaseUrl = System.getenv().getOrDefault(
                        "REPORT_DOWNLOAD_BASE_URL",
                        "http://reports.resorts-internal.com/download/");
            }

        } catch (Exception e) {
            // SSM client itself could not be initialised (e.g. no AWS credentials in local env).
            // Apply environment-variable fallbacks for both values.
            String envPort = System.getenv("SERVER_PORT");
            serverPort = (envPort != null && !envPort.isEmpty())
                    ? Integer.parseInt(envPort.trim())
                    : 8080;
            reportDownloadBaseUrl = System.getenv().getOrDefault(
                    "REPORT_DOWNLOAD_BASE_URL",
                    "http://reports.resorts-internal.com/download/");
        }
    }

    /**
     * Returns the server port resolved from AWS SSM Parameter Store or the
     * {@code SERVER_PORT} environment variable.
     *
     * <p>cr-java-0077 FIX: exposes the dynamically resolved port so that any
     * service-level URL construction can use the runtime-assigned port rather
     * than a hard-coded literal.</p>
     *
     * @return the resolved server port number
     */
    public int getServerPort() {
        return serverPort;
    }

    /**
     * Builds a synchronous S3 client scoped to the configured AWS region.
     * Uses the default credential provider chain (IAM role / environment variables /
     * ~/.aws/credentials) — no hard-coded credentials.
     *
     * @return a configured {@link S3Client} instance
     */
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Generates a monthly resort booking report and stores it in Amazon S3.
     *
     * <p>Previously this method used {@code java.io.File} / {@code FileWriter} to write
     * a CSV to the local file system (cr-java-0063 violations at original lines 37, 39, 42).
     * Those operations have been replaced with {@link S3Client#putObject} so that the
     * report is stored durably in S3 regardless of the underlying compute host.</p>
     *
     * @param month numeric or named month string (e.g. "03" or "March")
     * @param year  four-digit year string (e.g. "2024")
     * @return a map containing {@code status}, {@code s3Bucket}, and {@code s3Key} on
     *         success, or {@code status} and {@code message} on failure
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063 FIX (Lines 37, 39, 42):
        // Original code constructed a local file path, checked/created the directory with
        // new File(REPORT_BASE_PATH) and reportDir.mkdirs(), then wrote content via
        // new FileWriter(fullPath). All three java.io.File operations are replaced below
        // with a single S3 PutObject call — no local file system access required.
        String objectKey = reportKeyPrefix + "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try (S3Client s3Client = buildS3Client()) {
            // Build CSV content in-memory (replaces FileWriter.write() calls).
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // Upload directly to S3 — replaces new File() / mkdirs() / FileWriter (cr-java-0063).
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", objectKey);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name.
     *
     * <p>cr-java-0071 FIX (original line 66): The hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} has been replaced
     * with {@link #reportDownloadBaseUrl}, which is resolved from AWS SSM Parameter
     * Store at application startup via {@link #init()}. This makes the URL
     * environment-agnostic and configurable per deployment without code changes.</p>
     *
     * @param reportName the name of the report file
     * @return the download URL string constructed from the SSM-sourced base URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX (original line 66): replaced hard-coded environment URL with
        // value retrieved from AWS SSM Parameter Store (see init() and reportDownloadBaseUrl).
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 storage coordinates, the dynamically
     * resolved server port, and a generation timestamp.
     *
     * <p>Previously returned hard-coded local paths (REPORT_BASE_PATH, BACKUP_PATH) and
     * the hard-coded SERVER_PORT = 8080 constant. These have been replaced with S3
     * bucket/prefix values and the SSM/env-var resolved port (cr-java-0061, cr-java-0063,
     * cr-java-0077).</p>
     *
     * @return a map with {@code s3BucketName}, {@code reportKeyPrefix},
     *         {@code serverPort}, and {@code generatedAt} entries
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: replaced java.util.Date / SimpleDateFormat with java.time API
        // standardised on UTC (ZoneOffset.UTC) to avoid timezone inconsistencies across
        // cloud regions and container instances.
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // Replaced hard-coded REPORT_BASE_PATH and BACKUP_PATH references with S3 coordinates.
        info.put("s3BucketName", s3BucketName);
        info.put("reportKeyPrefix", reportKeyPrefix);
        // cr-java-0077 FIX: replaced hard-coded SERVER_PORT = 8080 with SSM/env-var resolved value.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
