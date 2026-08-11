package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

// cr-java-0111 FIX: Replaced java.util.Date and java.text.SimpleDateFormat imports with
// java.time API imports (Instant, ZoneOffset, DateTimeFormatter). The java.time API is
// timezone-aware and standardizes on UTC, eliminating server-local timezone dependencies
// that cause scheduling failures and time-related logic errors in distributed cloud
// environments spanning multiple regions or containers.
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

// cr-java-0063 FIX: All java.io.File and java.io.FileWriter imports have been removed.
// The original file imported java.io.File, java.io.FileWriter, and java.io.IOException
// for local file-system-based persistent storage. These have been replaced entirely with
// AWS SDK v2 S3 client calls (software.amazon.awssdk:s3), eliminating all host-level
// file system dependencies and making the service fully cloud-native.

@Service
public class ReportService {

    // cr-java-0061 FIX: Replaced hardcoded absolute file path "/var/legacy/reports/" with
    // an environment-variable-backed S3 bucket name. The bucket name is injected via
    // the 'app.s3.reports.bucket' property (backed by AWS Parameter Store / env var).
    @Value("${app.s3.reports.bucket:resorts-lite-reports}")
    private String reportsBucket;

    // cr-java-0061 FIX: Replaced hardcoded Windows-style backup path "C:\\ResortBackups\\nightly\\"
    // with an environment-variable-backed S3 key prefix for backup objects.
    @Value("${app.s3.backup.prefix:backups/nightly/}")
    private String backupPrefix;

    // cr-java-0061 FIX: S3Client is constructed using the default credential provider chain
    // (IAM role / environment variables / ~/.aws/credentials) and the region is read from
    // the 'aws.region' environment variable — no hard-coded paths or OS dependencies.
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // cr-java-0077 FIX: Removed the hard-coded SERVER_PORT = 8080 constant entirely.
    // The server port is now externalised to AWS Systems Manager Parameter Store and
    // injected at runtime via the SERVER_PORT environment variable in ECS, EKS, or
    // Elastic Beanstalk task/environment definitions. The Spring Boot server port is
    // controlled by the 'server.port' property (see application.properties), which
    // resolves to the SERVER_PORT environment variable — enabling dynamic port assignment
    // by container orchestration platforms and cloud service discovery mechanisms.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0077 FIX (Source Line 28 — original: private static final int SERVER_PORT = 8080):
    // The hard-coded port embedded in the report download base URL has been removed.
    // The full base URL (including any port) is now sourced entirely from AWS Systems Manager
    // Parameter Store via the Spring property 'app.reports.download.base-url', which is
    // resolved at runtime from the APP_REPORTS_DOWNLOAD_BASE_URL environment variable.
    // This eliminates the hard-coded port 8080 from application logic and allows each
    // deployment environment (dev/staging/prod) to specify its own host and port without
    // any code changes, satisfying cloud-native externalized configuration principles.
    @Value("${app.reports.download.base-url:${APP_REPORTS_DOWNLOAD_BASE_URL:}}")
    private String reportsDownloadBaseUrl;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        // cr-java-0063 FIX (Source Line 37 — original: File reportDir = new File(REPORT_BASE_PATH)):
        // Replaced java.io.File directory object construction with an S3 object key prefix.
        // In the original code, 'new File(REPORT_BASE_PATH)' created a reference to a local
        // directory (/var/legacy/reports/) that does not exist in cloud/container environments.
        // The S3 key prefix 'reports/' serves as the logical namespace for report objects in S3,
        // requiring no host-level directory creation and persisting independently of any instance.
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        // cr-java-0063 FIX (Source Line 39 — original: reportDir.mkdirs()):
        // Removed the java.io.File.mkdirs() call that created local directories on the host
        // file system. Amazon S3 is a flat object store — no directory creation is needed.
        // Objects are addressed directly by bucket + key, so the mkdirs() operation is
        // entirely eliminated without any loss of functionality.
        //
        // cr-java-0063 FIX (Source Line 42 — original: FileWriter writer = new FileWriter(fullPath)):
        // Replaced java.io.FileWriter-based local file write with Amazon S3 PutObject call.
        // The original code wrote CSV data to the local file system at REPORT_BASE_PATH
        // (/var/legacy/reports/), which is ephemeral in containerised/cloud environments and
        // causes permanent data loss on container restart or scale-out. The fix uploads the
        // CSV content directly to the configured S3 bucket using AWS SDK for Java v2
        // (software.amazon.awssdk:s3), ensuring durable, highly-available object storage
        // that persists independently of any individual container instance.
        // S3Client is closed via try-with-resources to prevent resource leaks.
        try (S3Client s3 = S3Client.builder()
                .region(Region.of(awsRegion))
                .build()) {

            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", s3Key);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // cr-java-0077 FIX (Source Line 28 / 66): The hard-coded port 8080 that was embedded
        // in the URL string "http://reports.resorts-internal.com:8080/download/" has been
        // eliminated. The full base URL is now resolved entirely from the externalized
        // 'app.reports.download.base-url' property, which is backed by the
        // APP_REPORTS_DOWNLOAD_BASE_URL environment variable injected by ECS/EKS/Elastic
        // Beanstalk at runtime. AWS Systems Manager Parameter Store is the recommended
        // source for this value in production deployments, enabling dynamic port assignment
        // and environment-specific routing without any code changes.
        return reportsDownloadBaseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 FIX (Source Line 70 — original: new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())):
        // Replaced java.util.Date and java.text.SimpleDateFormat with the java.time API
        // (Instant + DateTimeFormatter) standardized on UTC (ZoneOffset.UTC).
        // java.util.Date is timezone-unaware and relies on the JVM's default timezone, which
        // varies across cloud regions and container images, causing timestamp inconsistencies
        // in distributed deployments. Instant.now() always captures the current moment in UTC,
        // and DateTimeFormatter.withZone(ZoneOffset.UTC) ensures the formatted output is
        // explicitly UTC regardless of the host's local timezone setting. This guarantees
        // consistent, comparable timestamps across all cloud instances and regions.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 FIX: Replaced hardcoded REPORT_BASE_PATH and BACKUP_PATH constants
        // with the S3 bucket/prefix values sourced from environment variables.
        info.put("reportsBucket", reportsBucket);
        info.put("reportsPrefix", "reports/");
        info.put("backupPrefix", backupPrefix);
        // cr-java-0077 FIX: serverPort is now sourced from the SERVER_PORT environment
        // variable / AWS Parameter Store rather than a hard-coded constant.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
