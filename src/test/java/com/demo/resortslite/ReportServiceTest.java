package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
        ReflectionTestUtils.setField(reportService, "reportBasePath", "/tmp/test_reports");
        ReflectionTestUtils.setField(reportService, "backupPath", "/tmp/test_backups");
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsGeneratedStatus() throws Exception {
        // Arrange
        String month = "03";
        String year = "2024";

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertEquals("generated", result.get("status"));
        assertNotNull(result.get("path"));
        assertTrue(((String) result.get("path")).contains("resort_report_03_2024.csv"));

        // Cleanup
        String path = (String) result.get("path");
        Files.deleteIfExists(Path.of(path));
        Files.deleteIfExists(Path.of("/tmp/test_reports"));
    }

    @Test
    void generateMonthlyReport_withDifferentMonthAndYear_returnsGeneratedStatus() throws Exception {
        // Arrange
        String month = "12";
        String year = "2023";

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertEquals("generated", result.get("status"));
        assertNotNull(result.get("path"));
        assertTrue(((String) result.get("path")).contains("resort_report_12_2023.csv"));

        // Cleanup
        String path = (String) result.get("path");
        Files.deleteIfExists(Path.of(path));
        Files.deleteIfExists(Path.of("/tmp/test_reports"));
    }

    @Test
    void generateMonthlyReport_createsReportDirectoryIfNotExists() throws Exception {
        // Arrange
        String month = "06";
        String year = "2024";
        File reportDir = new File("/tmp/test_reports");
        if (reportDir.exists()) {
            deleteDirectory(reportDir);
        }

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, year);

        // Assert
        assertEquals("generated", result.get("status"));
        assertTrue(reportDir.exists());

        // Cleanup
        String path = (String) result.get("path");
        Files.deleteIfExists(Path.of(path));
        deleteDirectory(reportDir);
    }

    @Test
    void buildReportDownloadUrl_withValidReportName_returnsHttpsUrl() {
        // Arrange
        String reportName = "monthly_report.csv";

        // Act
        String url = reportService.buildReportDownloadUrl(reportName);

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://"));
        assertTrue(url.contains(reportName));
        assertEquals("https://reports.resorts-internal.com/download/monthly_report.csv", url);
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportName_returnsHttpsUrl() {
        // Arrange
        String reportName = "annual_summary.pdf";

        // Act
        String url = reportService.buildReportDownloadUrl(reportName);

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://"));
        assertTrue(url.contains("annual_summary.pdf"));
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsHttpsUrl() {
        // Arrange
        String reportName = "";

        // Act
        String url = reportService.buildReportDownloadUrl(reportName);

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://"));
        assertEquals("https://reports.resorts-internal.com/download/", url);
    }

    @Test
    void getSystemInfo_returnsMapWithExpectedKeys() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
        assertTrue(info.containsKey("reportPath"));
        assertTrue(info.containsKey("backupPath"));
        assertTrue(info.containsKey("generatedAt"));
        assertEquals("/tmp/test_reports", info.get("reportPath"));
        assertEquals("/tmp/test_backups", info.get("backupPath"));
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String timestamp = (String) info.get("generatedAt");
        assertNotNull(timestamp);
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    private void deleteDirectory(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }
}
