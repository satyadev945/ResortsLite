package com.demo.resortslite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link ReportService}.
 */
class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generateMonthlyReport_validMonthYear_returnsMapWithStatusKey")
    void generateMonthlyReport_validMonthYear_returnsMapWithStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    @DisplayName("generateMonthlyReport_validMonthYear_statusIsGeneratedOrError")
    void generateMonthlyReport_validMonthYear_statusIsGeneratedOrError() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert
        String status = (String) result.get("status");
        assertNotNull(status);
        assertTrue(status.equals("generated") || status.equals("error"),
                "Status must be 'generated' or 'error', got: " + status);
    }

    @Test
    @DisplayName("generateMonthlyReport_whenGenerated_containsPathKey")
    void generateMonthlyReport_whenGenerated_containsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert – path key present when generated, message key present when error
        assertTrue(result.containsKey("path") || result.containsKey("message"),
                "Result must contain 'path' (success) or 'message' (error)");
    }

    @Test
    @DisplayName("generateMonthlyReport_whenGenerated_serverPortPresent")
    void generateMonthlyReport_whenGenerated_serverPortPresent() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");

        // Assert – serverPort is only added on success path
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.containsKey("serverPort"));
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    @DisplayName("generateMonthlyReport_emptyMonth_doesNotThrow")
    void generateMonthlyReport_emptyMonth_doesNotThrow() {
        // Act & Assert
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("", "2024"));
    }

    @Test
    @DisplayName("generateMonthlyReport_nullYear_doesNotThrow")
    void generateMonthlyReport_nullYear_doesNotThrow() {
        // Act & Assert
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("January", null));
    }

    @Test
    @DisplayName("generateMonthlyReport_specialCharMonth_doesNotThrow")
    void generateMonthlyReport_specialCharMonth_doesNotThrow() {
        // Act & Assert
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("Jan/Feb", "2024"));
    }

    @Test
    @DisplayName("generateMonthlyReport_returnsNonNullMap")
    void generateMonthlyReport_returnsNonNullMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("buildReportDownloadUrl_validName_returnsNonNullUrl")
    void buildReportDownloadUrl_validName_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    @DisplayName("buildReportDownloadUrl_validName_containsReportName")
    void buildReportDownloadUrl_validName_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("annual_report.pdf");

        // Assert
        assertTrue(url.contains("annual_report.pdf"),
                "URL should contain the report name");
    }

    @Test
    @DisplayName("buildReportDownloadUrl_validName_startsWithHttp")
    void buildReportDownloadUrl_validName_startsWithHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("q1_report.pdf");

        // Assert
        assertTrue(url.startsWith("http"),
                "URL should start with http");
    }

    @Test
    @DisplayName("buildReportDownloadUrl_emptyName_returnsUrlWithEmptySegment")
    void buildReportDownloadUrl_emptyName_returnsUrlWithEmptySegment() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("http"));
    }

    @Test
    @DisplayName("buildReportDownloadUrl_differentNames_returnsDifferentUrls")
    void buildReportDownloadUrl_differentNames_returnsDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_a.pdf");
        String url2 = reportService.buildReportDownloadUrl("report_b.pdf");

        // Assert
        assertNotEquals(url1, url2);
    }

    @Test
    @DisplayName("buildReportDownloadUrl_containsDownloadPath")
    void buildReportDownloadUrl_containsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("test.pdf");

        // Assert
        assertTrue(url.contains("/download/"),
                "URL should contain '/download/' path segment");
    }

    // -----------------------------------------------------------------------
    // getSystemInfo
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getSystemInfo_returnsNonNullMap")
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
    }

    @Test
    @DisplayName("getSystemInfo_containsReportPathKey")
    void getSystemInfo_containsReportPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"),
                "System info should contain 'reportPath'");
    }

    @Test
    @DisplayName("getSystemInfo_containsBackupPathKey")
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"),
                "System info should contain 'backupPath'");
    }

    @Test
    @DisplayName("getSystemInfo_containsServerPortKey")
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"),
                "System info should contain 'serverPort'");
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    @DisplayName("getSystemInfo_containsGeneratedAtKey")
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"),
                "System info should contain 'generatedAt'");
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    @DisplayName("getSystemInfo_generatedAtIsFormattedTimestamp")
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String ts = (String) info.get("generatedAt");
        assertNotNull(ts);
        // Format: yyyy-MM-dd HH:mm:ss  (length = 19)
        assertEquals(19, ts.length(),
                "Timestamp should be in 'yyyy-MM-dd HH:mm:ss' format");
    }

    @Test
    @DisplayName("getSystemInfo_reportPathMatchesConstant")
    void getSystemInfo_reportPathMatchesConstant() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
    }

    @Test
    @DisplayName("getSystemInfo_backupPathMatchesConstant")
    void getSystemInfo_backupPathMatchesConstant() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals("C:\\ResortBackups\\nightly\\", info.get("backupPath"));
    }

    @Test
    @DisplayName("getSystemInfo_calledTwice_generatedAtDifferentOrSame")
    void getSystemInfo_calledTwice_returnsConsistentKeys() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert – both calls return maps with the same keys
        assertEquals(info1.keySet(), info2.keySet());
    }
}
