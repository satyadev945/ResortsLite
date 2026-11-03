package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Replaced hard-coded absolute file paths (cr-java-0061) with Amazon S3 object storage.
    // The S3 bucket name and key prefix are externalised via environment-backed properties
    // so the application has no dependency on the host file system.
    @Value("${app.s3.bucket-name}")
    private String s3BucketName;

    @Value("${app.s3.reports-prefix:reports/}")
    private String reportsPrefix;

    @Value("${app.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    private final S3Client s3Client;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly resort report and uploads it to Amazon S3.
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year for which the report is generated (e.g. "2024")
     * @return a map containing the operation status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Replaced hard-coded REPORT_BASE_PATH + local FileWriter (cr-java-0061 lines 23, 37, 42)
        // with an S3 key built from the environment-configured prefix.
        String s3Key = reportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a pre-signed or direct S3 URL for downloading a named report object.
     *
     * @param reportName the S3 object key (or file name) of the report
     * @return the HTTPS URL pointing to the report object in S3
     */
    public String buildReportDownloadUrl(String reportName) {
        // Replaced plain HTTP hard-coded URL (cr-java-0088) with an HTTPS S3 URL.
        GetUrlRequest urlRequest = GetUrlRequest.builder()
                .bucket(s3BucketName)
                .key(reportsPrefix + reportName)
                .build();
        return s3Client.utilities().getUrl(urlRequest).toString();
    }

    /**
     * Returns system information including S3 storage configuration details.
     *
     * @return a map of system metadata values
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111: Replaced java.util.Date / SimpleDateFormat (server-local timezone)
        // with java.time.Instant + DateTimeFormatter standardised on UTC so that the
        // timestamp is consistent across all cloud regions, containers, and replicas.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // Replaced hard-coded REPORT_BASE_PATH and BACKUP_PATH (cr-java-0061) with
        // S3 bucket/prefix values sourced from environment-backed application properties.
        info.put("s3Bucket", s3BucketName);
        info.put("reportsPrefix", reportsPrefix);
        info.put("backupPrefix", backupPrefix);
        info.put("generatedAt", timestamp);
        return info;
    }
}
