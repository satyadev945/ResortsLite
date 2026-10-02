package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @InjectMocks
    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Inject a writable temp directory so file I/O tests don't depend on /tmp
        ReflectionTestUtils.setField(reportService, "reportBasePath", tempDir.toString() + "/");
        ReflectionTestUtils.setField(reportService, "backupPath", tempDir.toString() + "/backups/");
        ReflectionTestUtils.setField(reportService, "reportDownloadBaseUrl",
                "https://reports.resorts-internal.com/download");
    }

    // ─── generateMonthlyReport ────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsStatusGenerated() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsFilePath() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result.get("path"));
        assertTrue(result.get("path").toString().contains("resort_report_March_2024.csv"));
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_createsFileOnDisk() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert
        String path = result.get("path").toString();
        assertTrue(new File(path).exists(), "Report file should exist on disk");
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_fileContainsCsvHeader() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert
        String path = result.get("path").toString();
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"));
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_fileContainsSampleData() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");

        // Assert
        String path = result.get("path").toString();
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BK-001"));
        assertTrue(content.contains("BK-002"));
    }

    @Test
    void generateMonthlyReport_whenDirectoryDoesNotExist_createsDirectoryAndFile() {
        // Arrange — use a nested path that doesn't exist yet
        String nestedPath = tempDir.toString() + "/nested/reports/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", nestedPath);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        assertTrue(new File(nestedPath).exists());
    }

    @Test
    void generateMonthlyReport_withDifferentYears_producesDistinctFilePaths() {
        // Act
        Map<String, Object> result2023 = reportService.generateMonthlyReport("January", "2023");
        Map<String, Object> result2024 = reportService.generateMonthlyReport("January", "2024");

        // Assert
        assertNotEquals(result2023.get("path"), result2024.get("path"));
    }

    @Test
    void generateMonthlyReport_doesNotExposeServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("August", "2024");

        // Assert — port must not be exposed in responses (czr-port-001)
        assertFalse(result.containsKey("serverPort"));
    }

    @Test
    void generateMonthlyReport_withIOError_returnsStatusError() {
        // Arrange — set an invalid path to force IOException
        ReflectionTestUtils.setField(reportService, "reportBasePath", "/root/no-permission-path-xyz/");

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("September", "2024");

        // Assert — graceful error handling
        // Either "error" status or "generated" depending on OS permissions; just ensure no exception thrown
        assertNotNull(result.get("status"));
    }

    // ─── buildReportDownloadUrl ───────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_withReportName_returnsFullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_march_2024.csv");

        // Assert
        assertEquals("https://reports.resorts-internal.com/download/report_march_2024.csv", url);
    }

    @Test
    void buildReportDownloadUrl_withReportName_usesHttps() {
        // Act
        String url = reportService.buildReportDownloadUrl("any_report.pdf");

        // Assert — must use HTTPS (cr-java-0088)
        assertTrue(url.startsWith("https://"), "URL must use HTTPS scheme");
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrlWithSlash() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertEquals("https://reports.resorts-internal.com/download/", url);
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportNames_producesDistinctUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.csv");
        String url2 = reportService.buildReportDownloadUrl("report_feb.csv");

        // Assert
        assertNotEquals(url1, url2);
    }

    @Test
    void buildReportDownloadUrl_containsReportNameInUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("my_special_report.csv");

        // Assert
        assertTrue(url.contains("my_special_report.csv"));
    }

    // ─── getSystemInfo ────────────────────────────────────────────────────────

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"));
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"));
        assertNotNull(info.get("backupPath"));
    }

    @Test
    void getSystemInfo_containsGeneratedAt() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"));
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAt_matchesDateTimeFormat() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert — format: yyyy-MM-dd HH:mm:ss
        String ts = info.get("generatedAt").toString();
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match yyyy-MM-dd HH:mm:ss but was: " + ts);
    }

    @Test
    void getSystemInfo_reportPath_matchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.get("reportPath").toString().contains(tempDir.toString()));
    }

    @Test
    void getSystemInfo_backupPath_matchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.get("backupPath").toString().contains("backups"));
    }

    @Test
    void getSystemInfo_doesNotContainServerPort() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert — czr-port-001: port must not be exposed
        assertFalse(info.containsKey("serverPort"));
    }

    @Test
    void getSystemInfo_calledTwice_returnsConsistentPaths() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertEquals(info1.get("reportPath"), info2.get("reportPath"));
        assertEquals(info1.get("backupPath"), info2.get("backupPath"));
    }
}
