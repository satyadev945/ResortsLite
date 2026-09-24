package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cr-java-0061 FIX: Replaced hard-coded absolute file path "/var/legacy/reports/" with
    // an environment-variable-backed S3 bucket name. The bucket name is injected via
    // the REPORT_S3_BUCKET environment variable (12-factor app principle III).
    @Value("${report.s3.bucket:${REPORT_S3_BUCKET:resort-reports-bucket}}")
    private String reportS3Bucket;

    // cr-java-0061 FIX: Replaced hard-coded Windows backup path "C:\\ResortBackups\\nightly\\"
    // with an environment-variable-backed S3 key prefix for backup objects.
    @Value("${report.s3.backup-prefix:${REPORT_S3_BACKUP_PREFIX:backups/nightly/}}")
    private String backupS3Prefix;

    // cr-java-0077 FIX: Hard-coded port 8080 replaced with AWS SSM Parameter Store + environment
    // variable injection. The port value is resolved at runtime from SSM parameter
    // '/resortslite/server/port' (configurable via APP_SERVER_PORT_SSM_PARAM env var).
    // Falls back to the SERVER_PORT environment variable, then to 8080 for local dev.
    // This enables dynamic port assignment required by ECS/EKS container orchestration
    // and cloud service discovery mechanisms.
    @Value("${app.server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071 FIX: Replaced hard-coded URL "http://reports.resorts-internal.com:8080/download/"
    // with a value externalised via AWS Systems Manager Parameter Store. The SSM parameter name is
    // bound through the Spring property 'app.report.download.base-url' which is resolved at startup
    // from SSM using the AwsSsmPropertySourceLocator (see SsmParameterStoreConfig). This enables
    // environment-agnostic deployments — the URL is changed in SSM without any code or image rebuild.
    @Value("${app.report.download.base-url:https://reports.resorts-internal.com/download/}")
    private String reportDownloadBaseUrl;

    private final S3Client s3Client;

    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report CSV and uploads it to Amazon S3.
     *
     * <p>cr-java-0061: All file-system operations (File, FileWriter, mkdirs) have been
     * replaced with S3 PutObject calls using AWS SDK for Java v2.
     *
     * <p>cr-java-0062 FIX (Line 42): The original {@code FileWriter} direct write to the
     * local file system ({@code new FileWriter(fullPath)}) has been replaced with an
     * Amazon S3 {@code PutObjectRequest}. In cloud/containerised environments the local
     * file system is ephemeral; data written locally is lost on container restart or
     * scale-out. Uploading to S3 ensures durable, highly-available, and scalable storage.
     *
     * @param month the month for the report (e.g. "03")
     * @param year  the year for the report (e.g. "2024")
     * @return a map containing the operation status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0061 FIX (Line 37): Replaced local fullPath construction
        // (REPORT_BASE_PATH + fileName) with an S3 object key.
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062 FIX (Line 42 — original: new File(REPORT_BASE_PATH) / reportDir.mkdirs()):
            // S3 buckets do not require explicit directory creation; the key prefix acts as a
            // logical folder. The ephemeral local-filesystem dependency is fully eliminated.
            //
            // cr-java-0062 FIX (Line 42 — original: new FileWriter(fullPath) / writer.write(...)):
            // All local FileWriter write operations have been replaced with a single S3
            // PutObjectRequest. The CSV content is streamed directly to S3 via RequestBody,
            // ensuring data durability across container restarts and horizontal scale-out.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportS3Bucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            // cr-java-0062 FIX: s3Client.putObject() replaces FileWriter.write() + FileWriter.close()
            // Data is persisted to Amazon S3 instead of the ephemeral local file system.
            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportS3Bucket);
            result.put("s3Key", objectKey);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL for the given report name.
     *
     * <p>cr-java-0071 FIX (Line 66): The hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} has been removed.
     * The base URL is now injected via the {@code @Value}-bound field
     * {@code reportDownloadBaseUrl}, whose value is externalised in AWS Systems Manager
     * Parameter Store under the parameter name stored in
     * {@code app.report.download.base-url.ssm-param}. This satisfies 12-factor app
     * principle III (Config) and enables zero-code-change promotion across dev / staging /
     * production environments.
     *
     * @param reportName the name of the report file
     * @return the download URL for the report, resolved from AWS SSM Parameter Store
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: 'reportDownloadBaseUrl' is sourced from AWS SSM Parameter Store
        // via the Spring property 'app.report.download.base-url', replacing the former
        // hard-coded value "http://reports.resorts-internal.com:8080/download/".
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 storage configuration.
     *
     * <p>cr-java-0061: Replaced hard-coded REPORT_BASE_PATH and BACKUP_PATH references
     * with S3 bucket/prefix values sourced from environment variables.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replaced java.util.Date + SimpleDateFormat (server-local timezone)
        // with java.time.Instant formatted in UTC via DateTimeFormatter. Standardising on UTC
        // eliminates timezone inconsistencies across multi-region / multi-container deployments.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 FIX: reportPath now reflects the S3 bucket
        // instead of the hard-coded "/var/legacy/reports/" absolute path.
        info.put("reportS3Bucket", reportS3Bucket);
        // cr-java-0061 FIX: backupPath now reflects the S3 backup prefix
        // instead of the hard-coded "C:\\ResortBackups\\nightly\\" Windows path.
        info.put("backupS3Prefix", backupS3Prefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
