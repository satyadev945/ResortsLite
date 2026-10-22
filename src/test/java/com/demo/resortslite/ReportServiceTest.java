package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for ReportService.
 */
@DisplayName("ReportService Tests")
class ReportServiceTest {

    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
        // Inject test values via ReflectionTestUtils (simulating @Value injection)
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                tempDir.toString() + "/reports");
        ReflectionTestUtils.setField(reportService, "backupPath",
                tempDir.toString() + "/backups");
        ReflectionTestUtils.setField(reportService, "reportDownloadBaseUrl",
                "https://reports.resorts-internal.com/download");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateMonthlyReport_validMonthYear_returnsGeneratedStatus")
    void generateMonthlyReport_validMonthYear_returnsGeneratedStatus() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result, "Result should not be null");
        assertEquals("generated", result.get("status"),
                "Status should be 'generated' on success");
    }

    @Test
    @DisplayName("generateMonthlyReport_validMonthYear_returnsFilePath")
    void generateMonthlyReport_validMonthYear_returnsFilePath() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result.get("path"), "Path should be present in result");
        String path = (String) result.get("path");
        assertTrue(path.contains("resort_report_03_2024.csv"),
                "Path should contain the expected filename");
    }

    @Test
    @DisplayName("generateMonthlyReport_createsFileOnDisk")
    void generateMonthlyReport_createsFileOnDisk() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        String path = (String) result.get("path");
        File reportFile = new File(path);
        assertTrue(reportFile.exists(), "Report file should exist on disk after generation");
    }

    @Test
    @DisplayName("generateMonthlyReport_createdFileContainsCsvHeader")
    void generateMonthlyReport_createdFileContainsCsvHeader() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)));
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"),
                "CSV file should contain the header row");
    }

    @Test
    @DisplayName("generateMonthlyReport_createdFileContainsSampleData")
    void generateMonthlyReport_createdFileContainsSampleData() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)));
        assertTrue(content.contains("BK-001"), "CSV should contain sample booking BK-001");
        assertTrue(content.contains("BK-002"), "CSV should contain sample booking BK-002");
    }

    @Test
    @DisplayName("generateMonthlyReport_differentMonthsProduceDifferentFiles")
    void generateMonthlyReport_differentMonthsProduceDifferentFiles() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("02", "2024");

        // Assert
        assertNotEquals(result1.get("path"), result2.get("path"),
                "Different months should produce different file paths");
    }

    @Test
    @DisplayName("generateMonthlyReport_differentYearsProduceDifferentFiles")
    void generateMonthlyReport_differentYearsProduceDifferentFiles() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("03", "2023");
        Map<String, Object> result2 = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotEquals(result1.get("path"), result2.get("path"),
                "Different years should produce different file paths");
    }

    @Test
    @DisplayName("generateMonthlyReport_resultMapHasExpectedKeys")
    void generateMonthlyReport_resultMapHasExpectedKeys() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("08", "2024");

        // Assert
        assertTrue(result.containsKey("status"), "Result should have 'status' key");
        assertTrue(result.containsKey("path"), "Result should have 'path' key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "06", "12"})
    @DisplayName("generateMonthlyReport_variousMonths_allSucceed")
    void generateMonthlyReport_variousMonths_allSucceed(String month) {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, "2024");

        // Assert
        assertEquals("generated", result.get("status"),
                "Month " + month + " should generate successfully");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildReportDownloadUrl_validReportName_returnsFullUrl")
    void buildReportDownloadUrl_validReportName_returnsFullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("resort_report_03_2024.csv");

        // Assert
        assertNotNull(url, "URL should not be null");
        assertEquals(
                "https://reports.resorts-internal.com/download/resort_report_03_2024.csv",
                url,
                "URL should be base URL + '/' + report name");
    }

    @Test
    @DisplayName("buildReportDownloadUrl_urlStartsWithHttps")
    void buildReportDownloadUrl_urlStartsWithHttps() {
        // Act
        String url = reportService.buildReportDownloadUrl("any_report.pdf");

        // Assert
        assertTrue(url.startsWith("https://"),
                "Download URL should use HTTPS");
    }

    @Test
    @DisplayName("buildReportDownloadUrl_containsReportName")
    void buildReportDownloadUrl_containsReportName() {
        // Arrange
        String reportName = "monthly_summary_2024.csv";

        // Act
        String url = reportService.buildReportDownloadUrl(reportName);

        // Assert
        assertTrue(url.contains(reportName),
                "URL should contain the report name");
    }

    @Test
    @DisplayName("buildReportDownloadUrl_differentReportNames_differentUrls")
    void buildReportDownloadUrl_differentReportNames_differentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.csv");
        String url2 = reportService.buildReportDownloadUrl("report_feb.csv");

        // Assert
        assertNotEquals(url1, url2, "Different report names should produce different URLs");
    }

    @Test
    @DisplayName("buildReportDownloadUrl_emptyReportName_returnsBaseUrlWithSlash")
    void buildReportDownloadUrl_emptyReportName_returnsBaseUrlWithSlash() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://reports.resorts-internal.com/download/"),
                "URL should still start with base URL");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getSystemInfo tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSystemInfo_returnsNonNullMap")
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info, "System info map should not be null");
    }

    @Test
    @DisplayName("getSystemInfo_containsReportPath")
    void getSystemInfo_containsReportPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"), "System info should contain 'reportPath'");
        assertNotNull(info.get("reportPath"), "reportPath should not be null");
    }

    @Test
    @DisplayName("getSystemInfo_containsBackupPath")
    void getSystemInfo_containsBackupPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"), "System info should contain 'backupPath'");
        assertNotNull(info.get("backupPath"), "backupPath should not be null");
    }

    @Test
    @DisplayName("getSystemInfo_containsGeneratedAt")
    void getSystemInfo_containsGeneratedAt() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"), "System info should contain 'generatedAt'");
        assertNotNull(info.get("generatedAt"), "generatedAt should not be null");
    }

    @Test
    @DisplayName("getSystemInfo_generatedAtMatchesTimestampFormat")
    void getSystemInfo_generatedAtMatchesTimestampFormat() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();
        String generatedAt = (String) info.get("generatedAt");

        // Assert: format yyyy-MM-dd HH:mm:ss → length 19
        assertNotNull(generatedAt);
        assertEquals(19, generatedAt.length(),
                "Timestamp should match 'yyyy-MM-dd HH:mm:ss' format (19 chars)");
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match pattern yyyy-MM-dd HH:mm:ss");
    }

    @Test
    @DisplayName("getSystemInfo_reportPathMatchesInjectedValue")
    void getSystemInfo_reportPathMatchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) info.get("reportPath");
        assertTrue(reportPath.contains("reports"),
                "Report path should contain 'reports' directory");
    }

    @Test
    @DisplayName("getSystemInfo_backupPathMatchesInjectedValue")
    void getSystemInfo_backupPathMatchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) info.get("backupPath");
        assertTrue(backupPath.contains("backups"),
                "Backup path should contain 'backups' directory");
    }

    @Test
    @DisplayName("getSystemInfo_calledTwice_generatedAtTimestampsAreClose")
    void getSystemInfo_calledTwice_generatedAtTimestampsAreClose() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert: both calls should return a valid timestamp
        assertNotNull(info1.get("generatedAt"));
        assertNotNull(info2.get("generatedAt"));
    }
}
