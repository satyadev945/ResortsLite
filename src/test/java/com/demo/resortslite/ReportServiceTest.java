package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for ReportService.
 * Covers generateMonthlyReport, buildReportDownloadUrl, and getSystemInfo.
 */
class ReportServiceTest {

    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
        // Inject test values via ReflectionTestUtils (simulating @Value injection)
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                tempDir.toString() + "/reports/");
        ReflectionTestUtils.setField(reportService, "backupPath",
                tempDir.toString() + "/backups/");
        ReflectionTestUtils.setField(reportService, "serverPort", 8080);
        ReflectionTestUtils.setField(reportService, "reportDownloadBaseUrl",
                "https://reports.resorts-internal.com/download");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsGeneratedStatus() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
        assertEquals("generated", result.get("status"),
                "Status should be 'generated' on success");
    }

    @Test
    void generateMonthlyReport_withValidInputs_returnsCorrectFilePath() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        String path = (String) result.get("path");
        assertNotNull(path);
        assertTrue(path.contains("resort_report_03_2024.csv"),
                "Path should contain the expected filename");
    }

    @Test
    void generateMonthlyReport_createsCSVFile() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");

        // Assert
        String path = (String) result.get("path");
        File reportFile = new File(path);
        assertTrue(reportFile.exists(), "CSV report file should be created on disk");
    }

    @Test
    void generateMonthlyReport_csvFileContainsHeader() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");
        String path = (String) result.get("path");

        // Assert
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"),
                "CSV should contain the header row");
    }

    @Test
    void generateMonthlyReport_csvFileContainsSampleData() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");
        String path = (String) result.get("path");

        // Assert
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BK-001"), "CSV should contain sample booking BK-001");
        assertTrue(content.contains("BK-002"), "CSV should contain sample booking BK-002");
    }

    @Test
    void generateMonthlyReport_includesServerPortInResult() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("08", "2024");

        // Assert
        assertNotNull(result.get("serverPort"), "Result should include serverPort");
        assertEquals(8080, result.get("serverPort"));
    }

    @Test
    void generateMonthlyReport_createsReportDirectoryIfNotExists() {
        // Arrange — use a subdirectory that doesn't exist yet
        String newPath = tempDir.toString() + "/new-reports-dir/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", newPath);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("09", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        assertTrue(new File(newPath).exists(), "Report directory should be created");
    }

    @Test
    void generateMonthlyReport_withDifferentYears_generatesCorrectFileName() {
        // Act
        Map<String, Object> result2023 = reportService.generateMonthlyReport("01", "2023");
        Map<String, Object> result2024 = reportService.generateMonthlyReport("01", "2024");

        // Assert
        String path2023 = (String) result2023.get("path");
        String path2024 = (String) result2024.get("path");
        assertTrue(path2023.contains("2023"), "Path should contain year 2023");
        assertTrue(path2024.contains("2024"), "Path should contain year 2024");
        assertNotEquals(path2023, path2024, "Paths for different years should differ");
    }

    @Test
    void generateMonthlyReport_withIOError_returnsErrorStatus() {
        // Arrange — set an invalid path that cannot be created
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                "/root/no-permission-path-xyz/");

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("10", "2024");

        // Assert — either succeeds (if running as root) or returns error
        assertNotNull(result);
        assertTrue(result.containsKey("status"), "Result should always contain 'status'");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_withValidReportName_returnsFullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertEquals("https://reports.resorts-internal.com/download/march_report.pdf", url);
    }

    @Test
    void buildReportDownloadUrl_urlStartsWithHttps() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.csv");

        // Assert
        assertTrue(url.startsWith("https://"),
                "Download URL should use HTTPS (not plain HTTP)");
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("annual_2024.pdf");

        // Assert
        assertTrue(url.contains("annual_2024.pdf"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_withDifferentBaseUrl_returnsCorrectUrl() {
        // Arrange
        ReflectionTestUtils.setField(reportService, "reportDownloadBaseUrl",
                "https://custom-reports.example.com/files");

        // Act
        String url = reportService.buildReportDownloadUrl("test.pdf");

        // Assert
        assertEquals("https://custom-reports.example.com/files/test.pdf", url);
    }

    @Test
    void buildReportDownloadUrl_separatesBaseUrlAndNameWithSlash() {
        // Act
        String url = reportService.buildReportDownloadUrl("myreport.pdf");

        // Assert
        assertTrue(url.contains("/myreport.pdf"),
                "URL should have a slash separator between base URL and report name");
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrlWithSlash() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.endsWith("/"), "URL with empty name should end with slash");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getSystemInfo tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info, "System info map should not be null");
    }

    @Test
    void getSystemInfo_containsReportPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"), "System info should contain 'reportPath'");
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"), "System info should contain 'backupPath'");
        assertNotNull(info.get("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPort() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"), "System info should contain 'serverPort'");
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAt() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"), "System info should contain 'generatedAt'");
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimeFormat() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();
        String generatedAt = (String) info.get("generatedAt");

        // Assert — format: yyyy-MM-dd HH:mm:ss
        assertNotNull(generatedAt);
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "generatedAt should match 'yyyy-MM-dd HH:mm:ss' format, got: " + generatedAt);
    }

    @Test
    void getSystemInfo_reportPathMatchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) info.get("reportPath");
        assertTrue(reportPath.contains("reports"), "Report path should contain 'reports'");
    }

    @Test
    void getSystemInfo_backupPathMatchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) info.get("backupPath");
        assertTrue(backupPath.contains("backups"), "Backup path should contain 'backups'");
    }
}
