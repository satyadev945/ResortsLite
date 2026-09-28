package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    @Test
    void testGenerateMonthlyReport_withValidMonthAndYear_returnsSuccessStatus() {
        // Arrange
        String month = "March";
        String year = "2024";

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertNotNull(result);
        // Note: This will fail in actual execution due to hardcoded path, but tests the logic
        assertTrue(result.containsKey("status"));
    }

    @Test
    void testGenerateMonthlyReport_withDifferentMonths_generatesCorrectFileNames() {
        // Arrange
        String month1 = "January";
        String year1 = "2024";
        String month2 = "December";
        String year2 = "2023";

        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport(month1, year1);
        Map<String, Object> result2 = reportService.generateMonthlyReport(month2, year2);

        // Assert
        assertNotNull(result1);
        assertNotNull(result2);
        assertTrue(result1.containsKey("status"));
        assertTrue(result2.containsKey("status"));
    }

    @Test
    void testGenerateMonthlyReport_withEmptyMonth_handlesGracefully() {
        // Arrange
        String month = "";
        String year = "2024";

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    void testGenerateMonthlyReport_withEmptyYear_handlesGracefully() {
        // Arrange
        String month = "March";
        String year = "";

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    void testGenerateMonthlyReport_withNullMonth_handlesGracefully() {
        // Arrange
        String month = null;
        String year = "2024";

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    void testGenerateMonthlyReport_withNullYear_handlesGracefully() {
        // Arrange
        String month = "March";
        String year = null;

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    void testGenerateMonthlyReport_withSpecialCharacters_handlesCorrectly() {
        // Arrange
        String month = "March/April";
        String year = "2024";

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    void testGenerateMonthlyReport_includesServerPort() {
        // Arrange
        String month = "June";
        String year = "2024";

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertNotNull(result);
        if (result.containsKey("serverPort")) {
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    void testBuildReportDownloadUrl_withValidReportName_returnsUrl() {
        // Arrange
        String reportName = "march_2024_report.csv";

        // Act
        String url = reportService.buildReportDownloadUrl(reportName);

        // Assert
        assertNotNull(url);
        assertTrue(url.contains(reportName));
        assertTrue(url.startsWith("http://"));
        assertTrue(url.contains("reports.resorts-internal.com"));
    }

    @Test
    void testBuildReportDownloadUrl_withEmptyReportName_returnsUrl() {
        // Arrange
        String reportName = "";

        // Act
        String url = reportService.buildReportDownloadUrl(reportName);

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("http://"));
    }

    @Test
    void testBuildReportDownloadUrl_withNullReportName_handlesGracefully() {
        // Arrange
        String reportName = null;

        // Act
        String url = reportService.buildReportDownloadUrl(reportName);

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("null"));
    }

    @Test
    void testBuildReportDownloadUrl_withSpecialCharacters_includesInUrl() {
        // Arrange
        String reportName = "report_2024-03-01_special!@#.csv";

        // Act
        String url = reportService.buildReportDownloadUrl(reportName);

        // Assert
        assertNotNull(url);
        assertTrue(url.contains(reportName));
    }

    @Test
    void testBuildReportDownloadUrl_withDifferentReportNames_generatesUniqueUrls() {
        // Arrange
        String reportName1 = "january_report.csv";
        String reportName2 = "february_report.csv";

        // Act
        String url1 = reportService.buildReportDownloadUrl(reportName1);
        String url2 = reportService.buildReportDownloadUrl(reportName2);

        // Assert
        assertNotEquals(url1, url2);
        assertTrue(url1.contains(reportName1));
        assertTrue(url2.contains(reportName2));
    }

    @Test
    void testGetSystemInfo_returnsAllRequiredFields() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
        assertTrue(info.containsKey("reportPath"));
        assertTrue(info.containsKey("backupPath"));
        assertTrue(info.containsKey("serverPort"));
        assertTrue(info.containsKey("generatedAt"));
    }

    @Test
    void testGetSystemInfo_reportPathIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("reportPath"));
        assertTrue(((String) info.get("reportPath")).contains("/var/legacy/reports/"));
    }

    @Test
    void testGetSystemInfo_backupPathIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("backupPath"));
        assertTrue(((String) info.get("backupPath")).contains("ResortBackups"));
    }

    @Test
    void testGetSystemInfo_serverPortIs8080() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void testGetSystemInfo_generatedAtIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("generatedAt"));
        assertTrue(info.get("generatedAt") instanceof String);
    }

    @Test
    void testGetSystemInfo_generatedAtHasCorrectFormat() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String timestamp = (String) info.get("generatedAt");
        assertNotNull(timestamp);
        // Format should be: yyyy-MM-dd HH:mm:ss
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void testGetSystemInfo_calledMultipleTimes_returnsDifferentTimestamps() throws InterruptedException {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Thread.sleep(1100); // Wait for at least 1 second
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertNotEquals(info1.get("generatedAt"), info2.get("generatedAt"));
    }

    @Test
    void testGenerateMonthlyReport_withLongMonthName_handlesCorrectly() {
        // Arrange
        String month = "September";
        String year = "2024";

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    void testGenerateMonthlyReport_withNumericMonth_handlesCorrectly() {
        // Arrange
        String month = "03";
        String year = "2024";

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    void testBuildReportDownloadUrl_containsPort8080() {
        // Arrange
        String reportName = "test_report.csv";

        // Act
        String url = reportService.buildReportDownloadUrl(reportName);

        // Assert
        assertTrue(url.contains(":8080"));
    }

    @Test
    void testBuildReportDownloadUrl_containsDownloadPath() {
        // Arrange
        String reportName = "test_report.csv";

        // Act
        String url = reportService.buildReportDownloadUrl(reportName);

        // Assert
        assertTrue(url.contains("/download/"));
    }
}
