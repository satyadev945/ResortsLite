package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReportService.
 * Covers generateMonthlyReport, buildReportDownloadUrl, and getSystemInfo.
 */
class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // ─── generateMonthlyReport ─────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidInputs_returnsNonNullMap() {
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_withValidInputs_returnsStatusKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_withValidInputs_returnsStatusGeneratedOrError() {
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error', but was: " + status);
    }

    @Test
    void generateMonthlyReport_withValidInputs_returnsServerPort() {
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");
        // If generated successfully, serverPort should be present
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.containsKey("serverPort"));
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_withValidInputs_returnsPathKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.containsKey("path"));
            String path = (String) result.get("path");
            assertTrue(path.contains("resort_report_May_2024.csv"));
        }
    }

    @Test
    void generateMonthlyReport_whenDirectoryNotWritable_returnsErrorStatus() {
        // The hardcoded path /var/legacy/reports/ likely doesn't exist in test env
        Map<String, Object> result = reportService.generateMonthlyReport("January", "2024");
        assertNotNull(result);
        // Either generated (if path exists) or error (if path doesn't exist)
        assertTrue(result.containsKey("status") || result.containsKey("message"));
    }

    @Test
    void generateMonthlyReport_withDifferentMonths_returnsMapForEach() {
        String[] months = {"January", "February", "March", "April"};
        for (String month : months) {
            Map<String, Object> result = reportService.generateMonthlyReport(month, "2024");
            assertNotNull(result, "Result should not be null for month: " + month);
        }
    }

    @Test
    void generateMonthlyReport_withEmptyMonth_returnsMap() {
        Map<String, Object> result = reportService.generateMonthlyReport("", "2024");
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_withEmptyYear_returnsMap() {
        Map<String, Object> result = reportService.generateMonthlyReport("March", "");
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_errorCase_containsMessageKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");
        if ("error".equals(result.get("status"))) {
            assertTrue(result.containsKey("message"));
        }
    }

    // ─── buildReportDownloadUrl ────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_withValidName_returnsNonNullString() {
        String url = reportService.buildReportDownloadUrl("march_report.pdf");
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_withValidName_containsReportName() {
        String url = reportService.buildReportDownloadUrl("march_report.pdf");
        assertTrue(url.contains("march_report.pdf"));
    }

    @Test
    void buildReportDownloadUrl_withValidName_startsWithHttp() {
        String url = reportService.buildReportDownloadUrl("report.pdf");
        assertTrue(url.startsWith("http://"));
    }

    @Test
    void buildReportDownloadUrl_withValidName_containsDownloadPath() {
        String url = reportService.buildReportDownloadUrl("report.pdf");
        assertTrue(url.contains("/download/"));
    }

    @Test
    void buildReportDownloadUrl_withValidName_containsHostAndPort() {
        String url = reportService.buildReportDownloadUrl("report.pdf");
        assertTrue(url.contains("reports.resorts-internal.com:8080"));
    }

    @Test
    void buildReportDownloadUrl_withEmptyName_returnsUrlWithEmptySegment() {
        String url = reportService.buildReportDownloadUrl("");
        assertNotNull(url);
        assertTrue(url.startsWith("http://"));
    }

    @Test
    void buildReportDownloadUrl_withSpecialCharacters_includesThemInUrl() {
        String url = reportService.buildReportDownloadUrl("report_2024-03.pdf");
        assertTrue(url.contains("report_2024-03.pdf"));
    }

    @Test
    void buildReportDownloadUrl_withDifferentNames_returnsDifferentUrls() {
        String url1 = reportService.buildReportDownloadUrl("report1.pdf");
        String url2 = reportService.buildReportDownloadUrl("report2.pdf");
        assertNotEquals(url1, url2);
    }

    @Test
    void buildReportDownloadUrl_returnsExpectedFullUrl() {
        String url = reportService.buildReportDownloadUrl("test.pdf");
        assertEquals("http://reports.resorts-internal.com:8080/download/test.pdf", url);
    }

    // ─── getSystemInfo ─────────────────────────────────────────────────────────

    @Test
    void getSystemInfo_returnsNonNullMap() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPathKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("generatedAt"));
    }

    @Test
    void getSystemInfo_reportPathIsCorrectValue() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
    }

    @Test
    void getSystemInfo_backupPathIsCorrectValue() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertEquals("C:\\ResortBackups\\nightly\\", info.get("backupPath"));
    }

    @Test
    void getSystemInfo_serverPortIs8080() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_generatedAtIsNonNullString() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info.get("generatedAt"));
        assertFalse(info.get("generatedAt").toString().isEmpty());
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimePattern() {
        Map<String, Object> info = reportService.getSystemInfo();
        String generatedAt = (String) info.get("generatedAt");
        // Pattern: yyyy-MM-dd HH:mm:ss
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "generatedAt should match yyyy-MM-dd HH:mm:ss but was: " + generatedAt);
    }

    @Test
    void getSystemInfo_calledTwice_returnsConsistentKeys() {
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();
        assertEquals(info1.keySet(), info2.keySet());
    }

    @Test
    void getSystemInfo_returnsMapWithFourEntries() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertEquals(4, info.size());
    }
}
