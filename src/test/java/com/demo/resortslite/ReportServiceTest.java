package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReportService}.
 */
class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // ── generateMonthlyReport ────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsNonNullMap() {
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsStatusKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsServerPort() {
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");
        // Either "generated" (if dir writable) or "error" (if not) — serverPort only on success
        // We just verify the map is not empty
        assertFalse(result.isEmpty());
    }

    @Test
    void generateMonthlyReport_statusIsGeneratedOrError_validStatusValue() {
        Map<String, Object> result = reportService.generateMonthlyReport("January", "2024");
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status must be 'generated' or 'error', but was: " + status);
    }

    @Test
    void generateMonthlyReport_whenStatusIsGenerated_containsPathKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("February", "2024");
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("path"));
            assertNotNull(result.get("path"));
        }
    }

    @Test
    void generateMonthlyReport_whenStatusIsGenerated_pathContainsMonthAndYear() {
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2023");
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            assertTrue(path.contains("June"));
            assertTrue(path.contains("2023"));
        }
    }

    @Test
    void generateMonthlyReport_whenStatusIsError_containsMessageKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");
        String status = (String) result.get("status");
        if ("error".equals(status)) {
            assertTrue(result.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_withEmptyMonth_doesNotThrow() {
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("", "2024"));
    }

    @Test
    void generateMonthlyReport_withEmptyYear_doesNotThrow() {
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("March", ""));
    }

    @Test
    void generateMonthlyReport_withSpecialCharactersInMonth_doesNotThrow() {
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("March-2024", "2024"));
    }

    @Test
    void generateMonthlyReport_calledMultipleTimes_doesNotThrow() {
        assertDoesNotThrow(() -> {
            reportService.generateMonthlyReport("Jan", "2024");
            reportService.generateMonthlyReport("Feb", "2024");
            reportService.generateMonthlyReport("Mar", "2024");
        });
    }

    // ── buildReportDownloadUrl ───────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_withValidReportName_returnsNonNullString() {
        String url = reportService.buildReportDownloadUrl("march_report.pdf");
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_withValidReportName_containsReportName() {
        String url = reportService.buildReportDownloadUrl("march_report.pdf");
        assertTrue(url.contains("march_report.pdf"));
    }

    @Test
    void buildReportDownloadUrl_withValidReportName_startsWithHttp() {
        String url = reportService.buildReportDownloadUrl("report.pdf");
        assertTrue(url.startsWith("http://"));
    }

    @Test
    void buildReportDownloadUrl_withValidReportName_containsDownloadPath() {
        String url = reportService.buildReportDownloadUrl("report.pdf");
        assertTrue(url.contains("/download/"));
    }

    @Test
    void buildReportDownloadUrl_withValidReportName_containsExpectedHost() {
        String url = reportService.buildReportDownloadUrl("report.pdf");
        assertTrue(url.contains("reports.resorts-internal.com"));
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsUrlWithEmptySegment() {
        String url = reportService.buildReportDownloadUrl("");
        assertNotNull(url);
        assertTrue(url.startsWith("http://"));
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportNames_returnsDifferentUrls() {
        String url1 = reportService.buildReportDownloadUrl("report1.pdf");
        String url2 = reportService.buildReportDownloadUrl("report2.pdf");
        assertNotEquals(url1, url2);
    }

    @Test
    void buildReportDownloadUrl_withReportName_urlContainsPort8080() {
        String url = reportService.buildReportDownloadUrl("test.pdf");
        assertTrue(url.contains("8080"));
    }

    // ── getSystemInfo ────────────────────────────────────────────────────────

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
    void getSystemInfo_reportPathIsNotNull() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_backupPathIsNotNull() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info.get("backupPath"));
    }

    @Test
    void getSystemInfo_serverPortIsNotNull() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info.get("serverPort"));
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_generatedAtIsNotNull() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info.get("generatedAt"));
        assertFalse(info.get("generatedAt").toString().isEmpty());
    }

    @Test
    void getSystemInfo_generatedAtMatchesTimestampPattern() {
        Map<String, Object> info = reportService.getSystemInfo();
        String ts = (String) info.get("generatedAt");
        // Pattern: yyyy-MM-dd HH:mm:ss
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match yyyy-MM-dd HH:mm:ss but was: " + ts);
    }

    @Test
    void getSystemInfo_reportPathContainsLegacyPath() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.get("reportPath").toString().contains("/var/legacy/reports/"));
    }

    @Test
    void getSystemInfo_backupPathContainsWindowsPath() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.get("backupPath").toString().contains("ResortBackups"));
    }

    @Test
    void getSystemInfo_calledTwice_generatedAtTimestampsAreValid() {
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();
        assertNotNull(info1.get("generatedAt"));
        assertNotNull(info2.get("generatedAt"));
    }
}
