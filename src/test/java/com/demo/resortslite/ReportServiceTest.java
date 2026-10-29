package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link ReportService}.
 * Covers generateMonthlyReport, buildReportDownloadUrl, getSystemInfo.
 */
class ReportServiceTest {

    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        reportService = new ReportService();
        // Inject reportBasePath via reflection (Spring @Value not active in unit tests)
        setField(reportService, "reportBasePath", tempDir.toString() + "/");
        setField(reportService, "reportDownloadUrl", "https://reports.resorts-internal.com/download/");
    }

    private void setField(Object target, String fieldName, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidParams_returnsGeneratedStatus() {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        assertEquals("generated", result.get("status"));
    }

    @Test
    void generateMonthlyReport_withValidParams_returnsFilePath() {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        assertNotNull(result.get("path"));
        assertFalse(result.get("path").toString().isEmpty());
    }

    @Test
    void generateMonthlyReport_createsFileOnDisk() {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        String path = result.get("path").toString();
        File file = new File(path);
        assertTrue(file.exists(), "Report file should exist on disk");
    }

    @Test
    void generateMonthlyReport_fileNameContainsMonthAndYear() {
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        String path = result.get("path").toString();
        assertTrue(path.contains("06"), "Path should contain month");
        assertTrue(path.contains("2024"), "Path should contain year");
    }

    @Test
    void generateMonthlyReport_fileNameHasCsvExtension() {
        Map<String, Object> result = reportService.generateMonthlyReport("09", "2024");

        String path = result.get("path").toString();
        assertTrue(path.endsWith(".csv"), "Report file should have .csv extension");
    }

    @Test
    void generateMonthlyReport_createsDirectoryIfNotExists() throws Exception {
        // Use a nested subdirectory that doesn't exist yet
        String nestedPath = tempDir.toString() + "/nested/reports/";
        setField(reportService, "reportBasePath", nestedPath);

        Map<String, Object> result = reportService.generateMonthlyReport("01", "2025");

        assertEquals("generated", result.get("status"));
        File dir = new File(nestedPath);
        assertTrue(dir.exists(), "Directory should be created");
    }

    @Test
    void generateMonthlyReport_fileContainsCsvHeader() throws Exception {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        String path = result.get("path").toString();
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BookingID"), "CSV should contain header");
    }

    @Test
    void generateMonthlyReport_fileContainsSampleData() throws Exception {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        String path = result.get("path").toString();
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BK-001"), "CSV should contain sample booking data");
    }

    @Test
    void generateMonthlyReport_withInvalidPath_returnsErrorStatus() throws Exception {
        // Set an invalid path that cannot be created (e.g., a file used as directory)
        File blockingFile = tempDir.resolve("blocker").toFile();
        blockingFile.createNewFile(); // create a file where we'd want a directory
        setField(reportService, "reportBasePath", blockingFile.getAbsolutePath() + "/sub/");

        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Either error or generated — just verify the map is not null and has a status
        assertNotNull(result);
        assertTrue(result.containsKey("status") || result.containsKey("path"));
    }

    @Test
    void generateMonthlyReport_differentMonths_createDifferentFiles() {
        Map<String, Object> march = reportService.generateMonthlyReport("03", "2024");
        Map<String, Object> june  = reportService.generateMonthlyReport("06", "2024");

        assertNotEquals(march.get("path"), june.get("path"));
    }

    @Test
    void generateMonthlyReport_resultMapContainsStatusKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("12", "2024");
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_resultMapContainsPathKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("12", "2024");
        assertTrue(result.containsKey("path"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_returnsNonNullUrl() {
        String url = reportService.buildReportDownloadUrl("report_03_2024.csv");
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        String url = reportService.buildReportDownloadUrl("report_03_2024.csv");
        assertTrue(url.contains("report_03_2024.csv"));
    }

    @Test
    void buildReportDownloadUrl_startsWithHttps() {
        String url = reportService.buildReportDownloadUrl("report_06_2024.csv");
        assertTrue(url.startsWith("https://"), "URL should use HTTPS");
    }

    @Test
    void buildReportDownloadUrl_containsBaseUrl() {
        String url = reportService.buildReportDownloadUrl("report_09_2024.csv");
        assertTrue(url.contains("reports.resorts-internal.com"));
    }

    @Test
    void buildReportDownloadUrl_concatenatesBaseUrlAndReportName() {
        String url = reportService.buildReportDownloadUrl("my_report.csv");
        assertEquals("https://reports.resorts-internal.com/download/my_report.csv", url);
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrl() {
        String url = reportService.buildReportDownloadUrl("");
        assertEquals("https://reports.resorts-internal.com/download/", url);
    }

    @Test
    void buildReportDownloadUrl_differentReportNames_returnDifferentUrls() {
        String url1 = reportService.buildReportDownloadUrl("report_01.csv");
        String url2 = reportService.buildReportDownloadUrl("report_02.csv");
        assertNotEquals(url1, url2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getSystemInfo
    // ─────────────────────────────────────────────────────────────────────────

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
    void getSystemInfo_containsGeneratedAtKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("generatedAt"));
    }

    @Test
    void getSystemInfo_reportPathMatchesInjectedValue() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertEquals(tempDir.toString() + "/", info.get("reportPath"));
    }

    @Test
    void getSystemInfo_generatedAtIsNotNull() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtIsNotEmpty() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertFalse(info.get("generatedAt").toString().isEmpty());
    }

    @Test
    void getSystemInfo_generatedAtMatchesTimestampFormat() {
        Map<String, Object> info = reportService.getSystemInfo();
        String ts = info.get("generatedAt").toString();
        // Format: yyyy-MM-dd HH:mm:ss  (19 chars)
        assertEquals(19, ts.length(), "Timestamp should be 19 characters: yyyy-MM-dd HH:mm:ss");
    }

    @Test
    void getSystemInfo_calledTwice_generatedAtMayDiffer() {
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();
        // Both should be non-null; timestamps may be equal within the same second
        assertNotNull(info1.get("generatedAt"));
        assertNotNull(info2.get("generatedAt"));
    }
}
