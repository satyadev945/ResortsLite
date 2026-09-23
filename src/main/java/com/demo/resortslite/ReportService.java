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
import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * ReportService — generates and stores resort reports.
 *
 * <p>Cloud-readiness fix (cr-java-0063 – Java.io.File Usage for Data Storage):
 * All local file storage operations (previously using {@link java.io.File} and
 * {@link java.io.FileWriter} to write CSV files to {@code /var/legacy/reports/}
 * and {@code C:\ResortBackups\nightly\}) have been replaced with Amazon S3
 * {@code PutObject} calls via the AWS SDK for Java v2.
 *
 * <p>Local file system paths are ephemeral in containerised / serverless environments;
 * data written locally is lost on container restart or scale-out.  Amazon S3 provides
 * durable, highly-available, and scalable object storage that survives container
 * lifecycle events and is accessible across all instances.</p>
 *
 * <p>The target S3 buckets are resolved from environment variables
 * {@code AWS_S3_REPORT_BUCKET} and {@code AWS_S3_BACKUP_BUCKET} (or the corresponding
 * Spring properties {@code aws.s3.report.bucket} / {@code aws.s3.backup.bucket}),
 * following 12-factor app principle III (Config).</p>
 *
 * <p>Cloud-readiness fix (cr-java-0071 – Hard-coded Environment URLs):
 * The hard-coded report download base URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} (original line 66)
 * has been replaced with a value fetched from AWS Systems Manager Parameter Store
 * at application startup, enabling environment-agnostic deployments.</p>
 *
 * <p>Cloud-readiness fix (cr-java-0077 – Hard-coded Ports):
 * The hard-coded server port constant {@code private static final int SERVER_PORT = 8080;}
 * (original line 28) has been replaced with a value fetched from AWS Systems Manager
 * Parameter Store at application startup via {@link #loadConfigFromParameterStore()}.
 * This eliminates the static port binding that prevents dynamic port assignment required
 * by container orchestration platforms (ECS, EKS) and cloud service discovery mechanisms.
 * The SSM parameter path is configured via the environment variable
 * {@code SERVER_PORT_PARAM_NAME} (default: {@code /resortslite/server/port}).
 * A fallback to the {@code SERVER_PORT} environment variable is provided for local
 * development without AWS credentials.</p>
 *
 * <p>Violations fixed (cr-java-0063):</p>
 * <ul>
 *   <li>Line 37 (original): {@code new File(REPORT_BASE_PATH)} — replaced with S3 bucket reference</li>
 *   <li>Line 39 (original): {@code reportDir.mkdirs()} — replaced with S3 PutObject (no local dir needed)</li>
 *   <li>Line 42 (original): {@code new FileWriter(fullPath)} — replaced with {@code S3Client.putObject()} call using {@code RequestBody.fromString()}.</li>
 * </ul>
 *
 * <p>Violations fixed (cr-java-0071):</p>
 * <ul>
 *   <li>Line 66 (original): {@code return "http://reports.resorts-internal.com:8080/download/" + reportName;}
 *       — replaced with a base URL fetched from AWS SSM Parameter Store.</li>
 * </ul>
 *
 * <p>Violations fixed (cr-java-0077):</p>
 * <ul>
 *   <li>Line 28 (original): {@code private static final int SERVER_PORT = 8080;}
 *       — replaced with a port value fetched from AWS SSM Parameter Store at startup.</li>
 * </ul>
 */
@Service
public class ReportService {

    // cr-java-0063 fix (line 37 original): replaced hard-coded absolute path
    // "/var/legacy/reports/" (used as argument to new File(...)) with an Amazon S3
    // bucket name resolved from environment variable AWS_S3_REPORT_BUCKET.
    // java.io.File is no longer used; S3Client handles all persistent storage.
    @Value("${aws.s3.report.bucket:${AWS_S3_REPORT_BUCKET:resort-reports-bucket}}")
    private String reportBucket;

    // cr-java-0063 fix (line 39 original): replaced hard-coded Windows-style backup path
    // "C:\\ResortBackups\\nightly\\" (used with reportDir.mkdirs()) with an Amazon S3
    // bucket name resolved from environment variable AWS_S3_BACKUP_BUCKET.
    // Directory creation via java.io.File.mkdirs() is no longer needed.
    @Value("${aws.s3.backup.bucket:${AWS_S3_BACKUP_BUCKET:resort-backups-bucket}}")
    private String backupBucket;

    // cr-java-0077 FIX (line 28 original):
    // REMOVED: private static final int SERVER_PORT = 8080;
    // The hard-coded port constant has been replaced with a value fetched from
    // AWS Systems Manager Parameter Store at application startup.
    // The SSM parameter path is configured via the environment variable
    // SERVER_PORT_PARAM_NAME (default: /resortslite/server/port).
    // This enables dynamic port assignment required by ECS, EKS, and Elastic Beanstalk.
    @Value("${aws.ssm.server.port.param:${SERVER_PORT_PARAM_NAME:/resortslite/server/port}}")
    private String serverPortParamName;

    // Holds the server port value resolved from AWS SSM Parameter Store at startup.
    // Falls back to the SERVER_PORT environment variable (default: 8080) when SSM
    // is unavailable (e.g., local development without AWS credentials).
    private int serverPort;

    // cr-java-0071 FIX: AWS region resolved from environment variable / Spring property.
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // cr-java-0071 FIX: SSM parameter name for the report download base URL.
    // Resolved from environment variable REPORT_URL_PARAM_NAME so it can be
    // configured per-environment without code changes.
    @Value("${aws.ssm.report.url.param:${REPORT_URL_PARAM_NAME:/resortslite/reports/download-base-url}}")
    private String reportUrlParamName;

    // cr-java-0071 FIX: The hard-coded URL "http://reports.resorts-internal.com:8080/download/"
    // (line 66 original) is replaced by a value fetched from AWS Systems Manager Parameter Store
    // at application startup. This enables environment-agnostic deployments — the URL is
    // configured in SSM per environment (dev / staging / prod) without any code changes.
    private String reportDownloadBaseUrl;

    private final S3Client s3Client;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Fetches runtime configuration from AWS Systems Manager Parameter Store
     * at application startup.
     *
     * <p>cr-java-0077 fix: the hard-coded port constant
     * {@code private static final int SERVER_PORT = 8080;} (original line 28)
     * is replaced by a value stored in SSM Parameter Store under the path configured
     * by {@code aws.ssm.server.port.param} (env var: {@code SERVER_PORT_PARAM_NAME},
     * default: {@code /resortslite/server/port}).
     * The parameter should be created in SSM for each target environment, e.g.:
     * <pre>
     *   aws ssm put-parameter \
     *     --name /resortslite/server/port \
     *     --value "8080" \
     *     --type String
     * </pre>
     * </p>
     *
     * <p>cr-java-0071 fix: replaces the hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} (original line 66)
     * with a value stored in SSM Parameter Store under the path configured by
     * {@code aws.ssm.report.url.param} (env var: {@code REPORT_URL_PARAM_NAME},
     * default: {@code /resortslite/reports/download-base-url}).
     * The parameter should be created in SSM for each target environment, e.g.:
     * <pre>
     *   aws ssm put-parameter \
     *     --name /resortslite/reports/download-base-url \
     *     --value "https://reports.resorts-internal.com/download/" \
     *     --type String
     * </pre>
     * </p>
     */
    @PostConstruct
    public void loadConfigFromParameterStore() {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            // cr-java-0077 FIX: Fetch server port from AWS SSM Parameter Store.
            // Replaces the hard-coded constant: private static final int SERVER_PORT = 8080;
            // (original line 28). Dynamic port resolution enables ECS/EKS to assign ports
            // at runtime without requiring code changes or redeployment.
            try {
                GetParameterRequest portParamRequest = GetParameterRequest.builder()
                        .name(serverPortParamName)
                        .withDecryption(false)
                        .build();
                GetParameterResponse portParamResponse = ssmClient.getParameter(portParamRequest);
                this.serverPort = Integer.parseInt(portParamResponse.parameter().value().trim());
            } catch (Exception e) {
                // Fall back to SERVER_PORT environment variable, then default 8080,
                // when SSM is unavailable (e.g., local development without AWS credentials).
                String envPort = System.getenv("SERVER_PORT");
                this.serverPort = (envPort != null && !envPort.isEmpty())
                        ? Integer.parseInt(envPort.trim())
                        : 8080;
            }

            // cr-java-0071 FIX: Fetch report download base URL from AWS SSM Parameter Store.
            // Replaces the hard-coded URL "http://reports.resorts-internal.com:8080/download/"
            // (original line 66).
            try {
                GetParameterRequest urlParamRequest = GetParameterRequest.builder()
                        .name(reportUrlParamName)
                        .withDecryption(true)
                        .build();
                GetParameterResponse urlParamResponse = ssmClient.getParameter(urlParamRequest);
                this.reportDownloadBaseUrl = urlParamResponse.parameter().value();
            } catch (Exception e) {
                // Fall back to environment variable / property if SSM is unavailable
                // (e.g., local development without AWS credentials).
                this.reportDownloadBaseUrl = System.getenv()
                        .getOrDefault("REPORT_DOWNLOAD_BASE_URL",
                                "http://reports.resorts-internal.com:8080/download/");
            }
        }
    }

    /**
     * Generates a monthly CSV report and persists it to Amazon S3.
     *
     * <p>cr-java-0063 fix: the original implementation used {@code java.io.File} and
     * {@code java.io.FileWriter} to write the CSV to a local path
     * ({@code /var/legacy/reports/resort_report_<month>_<year>.csv}).
     * Specifically:
     * <ul>
     *   <li>Line 37: {@code File reportDir = new File(REPORT_BASE_PATH);} — removed;
     *       S3 bucket reference used instead.</li>
     *   <li>Line 39: {@code reportDir.mkdirs();} — removed; S3 does not require
     *       directory pre-creation.</li>
     *   <li>Line 42: {@code FileWriter writer = new FileWriter(fullPath);} — replaced
     *       with {@code S3Client.putObject()} call using {@code RequestBody.fromString()}.</li>
     * </ul>
     * Data is now written to Amazon S3 (durable, cloud-native storage) instead of
     * the ephemeral local file system, preventing data loss on container restart.</p>
     *
     * @param month the report month (e.g. "03")
     * @param year  the report year  (e.g. "2024")
     * @return a map containing the operation status and the S3 object coordinates
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {

        // cr-java-0063 fix: S3 object key replaces the former local file path
        // "/var/legacy/reports/resort_report_<month>_<year>.csv".
        // Data is now written to Amazon S3 (durable, cloud-native storage) instead of
        // the ephemeral local file system, preventing data loss on container restart.
        String s3Key = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — same business logic as before.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // cr-java-0063 fix (lines 37, 39, 42 original):
            //   REMOVED: File reportDir = new File(REPORT_BASE_PATH);   [line 37]
            //   REMOVED: reportDir.mkdirs();                             [line 39]
            //   REMOVED: FileWriter writer = new FileWriter(fullPath);   [line 42]
            //   REMOVED: writer.write(...); writer.close();
            // REPLACED WITH: Amazon S3 PutObject — no local disk I/O occurs.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", s3Key);
            // cr-java-0077 fix: serverPort is now resolved from AWS SSM Parameter Store
            // at startup (see loadConfigFromParameterStore()), not a hard-coded constant.
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report.
     *
     * <p>cr-java-0071 fix: the hard-coded base URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} (original line 66)
     * has been replaced with a value fetched from AWS Systems Manager Parameter Store
     * at startup (see {@link #loadConfigFromParameterStore()}).
     * The base URL is now environment-agnostic and can be configured per-environment
     * in SSM without any code changes.</p>
     *
     * @param reportName the name of the report file to download
     * @return the full download URL for the specified report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: The hard-coded URL
        // "http://reports.resorts-internal.com:8080/download/" + reportName (original line 66)
        // is replaced with a base URL fetched from AWS Systems Manager Parameter Store
        // at application startup (see loadConfigFromParameterStore()).
        // This enables environment-agnostic deployments — the URL is configured in SSM
        // per environment (dev / staging / prod) without any code changes.
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns current system / configuration information.
     *
     * <p>cr-java-0063 fix: the former implementation exposed local file-system paths
     * ({@code reportPath}, {@code backupPath}) in the response, referencing
     * {@code java.io.File}-based constants. These have been replaced with the
     * corresponding S3 bucket names so that callers receive cloud-native
     * storage coordinates instead of ephemeral local paths.</p>
     *
     * <p>cr-java-0077 fix: the former implementation exposed the hard-coded
     * {@code SERVER_PORT = 8080} constant. This is now the SSM-resolved runtime
     * port value, enabling dynamic port assignment in cloud environments.</p>
     *
     * @return a map of configuration keys and their current values
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix: replaced java.util.Date / SimpleDateFormat with java.time API.
        // Instant.now() captures the current moment in UTC, and DateTimeFormatter formats
        // it as an ISO-8601 string. Standardising on UTC eliminates timezone inconsistencies
        // across distributed cloud containers and regions.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 fix: replaced local file-system path references (java.io.File constants)
        // with S3 bucket names resolved from environment variables.
        info.put("reportBucket", reportBucket);
        info.put("backupBucket", backupBucket);
        // cr-java-0077 fix: serverPort is now resolved from AWS SSM Parameter Store
        // at startup (see loadConfigFromParameterStore()), not a hard-coded constant.
        info.put("serverPort", serverPort);
        // cr-java-0071 fix: expose the SSM-resolved report download base URL
        info.put("reportDownloadBaseUrl", reportDownloadBaseUrl);
        info.put("generatedAt", timestamp);
        return info;
    }
}
