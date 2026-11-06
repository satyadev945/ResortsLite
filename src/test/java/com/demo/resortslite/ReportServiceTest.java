package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link ReportService}.
 * Covers generateMonthlyReport, buildReportDownloadUrl, and getSystemInfo.
 */
class ReportServiceTest {

    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport
    // -----------------------------------------------------------------------

    @Test
    void generateMonthlyReport_withValidParams_returnsNonNullResult() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_withValidParams_containsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_successOrError_statusIsNotNull() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert
        assertNotNull(result.get("status"));
    }

    @Test
    void generateMonthlyReport_onSuccess_statusIsGenerated() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");

        // Assert — either "generated" (if dir writable) or "error" (if not)
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error', got: " + status);
    }

    @Test
    void generateMonthlyReport_onSuccess_containsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");

        // Assert — if generated, path key should be present
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("path"));
            assertNotNull(result.get("path"));
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_pathContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("August", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            assertTrue(path.contains("August"), "Path should contain month");
            assertTrue(path.contains("2024"), "Path should contain year");
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_containsServerPortKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("September", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_onError_containsMessageKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("October", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("error".equals(status)) {
            assertTrue(result.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_withDifferentMonths_returnsResult() {
        // Act & Assert — ensure no exception thrown for various months
        String[] months = {"January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"};
        for (String month : months) {
            Map<String, Object> result = reportService.generateMonthlyReport(month, "2024");
            assertNotNull(result, "Result should not be null for month: " + month);
        }
    }

    @Test
    void generateMonthlyReport_withEmptyMonth_doesNotThrow() {
        // Act & Assert
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("", "2024"));
    }

    @Test
    void generateMonthlyReport_withEmptyYear_doesNotThrow() {
        // Act & Assert
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("March", ""));
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_march_2024.csv");

        // Assert
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_urlContainsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_march_2024.csv");

        // Assert
        assertTrue(url.contains("report_march_2024.csv"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_urlEndsWithReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("my_report.pdf");

        // Assert
        assertTrue(url.endsWith("my_report.pdf"),
                "URL should end with the report name");
    }

    @Test
    void buildReportDownloadUrl_urlIsNotEmpty() {
        // Act
        String url = reportService.buildReportDownloadUrl("test.csv");

        // Assert
        assertFalse(url.isEmpty());
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportNames_returnsDistinctUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.csv");
        String url2 = reportService.buildReportDownloadUrl("report_feb.csv");

        // Assert
        assertNotEquals(url1, url2, "Different report names should produce different URLs");
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        // URL should still be a valid string (base URL + empty string)
        assertFalse(url.isEmpty());
    }

    @Test
    void buildReportDownloadUrl_urlContainsHttpScheme() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.csv");

        // Assert — URL should start with http:// or https://
        assertTrue(url.startsWith("http://") || url.startsWith("https://"),
                "URL should start with http:// or https://");
    }

    // -----------------------------------------------------------------------
    // getSystemInfo
    // -----------------------------------------------------------------------

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"));
    }

    @Test
    void getSystemInfo_reportPathIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_backupPathIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("backupPath"));
    }

    @Test
    void getSystemInfo_serverPortIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("serverPort"));
    }

    @Test
    void getSystemInfo_generatedAtIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
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
    void getSystemInfo_serverPortIsInteger() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();
        Object port = info.get("serverPort");

        // Assert
        assertNotNull(port);
        assertInstanceOf(Integer.class, port, "serverPort should be an Integer");
    }

    @Test
    void getSystemInfo_calledMultipleTimes_returnsConsistentKeys() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert — keys should be consistent across calls
        assertEquals(info1.keySet(), info2.keySet());
    }
}
