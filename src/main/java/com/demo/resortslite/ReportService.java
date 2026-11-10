package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
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

    // Reports are stored in Amazon S3 instead of the local file system.
    // Bucket and key prefixes are externalised (env vars REPORT_S3_BUCKET, REPORT_S3_PREFIX, BACKUP_S3_PREFIX).
    private final S3Client s3Client;

    @Autowired
    private ParameterStoreService parameterStore;

    // Report download base URL is resolved from AWS Systems Manager Parameter Store
    @Value("${app.report.download.ssm-parameter:/resortslite/config/report-download-base-url}")
    private String downloadBaseUrlParameter;

    @Value("${app.report.download.base-url:${REPORT_DOWNLOAD_BASE_URL:}}")
    private String downloadBaseUrlFallback;

    @Value("${app.report.s3.bucket:resorts-reports}")
    private String reportBucket;

    @Value("${app.report.s3.prefix:reports/}")
    private String reportPrefix;

    @Value("${app.report.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    // Server port is resolved from AWS Systems Manager Parameter Store, falling back to the
    // externally injected SERVER_PORT environment variable (no hard-coded port in code).
    @Value("${app.server.port.ssm-parameter:/resortslite/config/server-port}")
    private String serverPortParameter;

    @Value("${app.server.port:${server.port:}}")
    private String serverPortFallback;

    @Autowired
    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    private Object resolveServerPort() {
        String value = parameterStore.getParameter(serverPortParameter, serverPortFallback);
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return value.trim();
        }
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String objectKey = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csv.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csv.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();
            s3Client.putObject(request,
                    RequestBody.fromBytes(csv.toString().getBytes(StandardCharsets.UTF_8)));

            result.put("status", "generated");
            result.put("path", "s3://" + reportBucket + "/" + objectKey);
            result.put("serverPort", resolveServerPort());

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
        String baseUrl = parameterStore.getParameter(downloadBaseUrlParameter, downloadBaseUrlFallback);
        if (baseUrl == null) {
            baseUrl = "";
        }
        return (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // Standardise on UTC so timestamps are consistent across regions/containers
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(ZonedDateTime.now(Clock.system(ZoneOffset.UTC)));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", "s3://" + reportBucket + "/" + reportPrefix);
        info.put("backupPath", "s3://" + reportBucket + "/" + backupPrefix);
        info.put("serverPort", resolveServerPort());
        info.put("generatedAt", timestamp);
        return info;
    }
}
