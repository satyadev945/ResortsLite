package com.demo.resortslite;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Reports and backups are stored in Amazon S3 instead of local/absolute file system paths.
    // Configure through environment variables (no host-specific directories required).
    private static final String REPORT_BUCKET =
            getEnv("REPORT_S3_BUCKET", "resorts-reports");
    private static final String REPORT_BASE_PATH =
            normalisePrefix(getEnv("REPORT_S3_PREFIX", "reports/"));
    private static final String BACKUP_PATH =
            normalisePrefix(getEnv("REPORT_S3_BACKUP_PREFIX", "backups/nightly/"));

    // Server port is externalised: injected at runtime via the SERVER_PORT environment variable
    // (sourced from AWS Systems Manager Parameter Store through ECS/EKS/Elastic Beanstalk
    // configuration). No hard-coded port number in application logic.
    private static final int SERVER_PORT = resolvePort("SERVER_PORT");

    private volatile S3Client s3Client;

    private S3Client s3() {
        if (s3Client == null) {
            synchronized (this) {
                if (s3Client == null) {
                    S3ClientBuilder builder = S3Client.builder();
                    String region = System.getenv("AWS_REGION");
                    if (region != null && !region.trim().isEmpty()) {
                        builder.region(Region.of(region.trim()));
                    }
                    s3Client = builder.build();
                }
            }
        }
        return s3Client;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String objectKey = REPORT_BASE_PATH + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String csv = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            s3().putObject(
                    PutObjectRequest.builder()
                            .bucket(REPORT_BUCKET)
                            .key(objectKey)
                            .contentType("text/csv")
                            .build(),
                    RequestBody.fromString(csv, StandardCharsets.UTF_8));

            result.put("status", "generated");
            result.put("path", "s3://" + REPORT_BUCKET + "/" + objectKey);
            result.put("serverPort", SERVER_PORT); // czr-port-001

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails() != null
                    ? e.awsErrorDetails().errorMessage() : e.getMessage());
        } catch (RuntimeException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // Standardised on UTC (java.time) so output is independent of server-local timezone.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(ZonedDateTime.now(Clock.systemUTC()).withZoneSameInstant(ZoneOffset.UTC));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", "s3://" + REPORT_BUCKET + "/" + REPORT_BASE_PATH);
        info.put("backupPath", "s3://" + REPORT_BUCKET + "/" + BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);        // czr-port-001
        info.put("generatedAt", timestamp);
        return info;
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.trim().isEmpty()) ? defaultValue : value.trim();
    }

    private static int resolvePort(String envName) {
        String value = System.getenv(envName);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv("PORT");
        }
        if (value != null && !value.trim().isEmpty()) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                // fall through to 0 (dynamic / unassigned)
            }
        }
        return 0;
    }

    private static String normalisePrefix(String prefix) {
        String p = prefix;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (!p.isEmpty() && !p.endsWith("/")) {
            p = p + "/";
        }
        return p;
    }
}
