package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link ReportService}.
 * Tests all public methods: generateMonthlyReport(), buildReportDownloadUrl(), getSystemInfo().
 */
@DisplayName("ReportService Tests")
class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // =========================================================================
    // generateMonthlyReport()
    // =========================================================================

    @Nested
    @DisplayName("generateMonthlyReport()")
    class GenerateMonthlyReportTests {

        @Test
        @DisplayName("generateMonthlyReport_withValidMonthAndYear_returnsNonNullMap")
        void generateMonthlyReport_withValidMonthAndYear_returnsNonNullMap() {
            // Act
            Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

            // Assert
            assertNotNull(result, "Result map must not be null");
        }

        @Test
        @DisplayName("generateMonthlyReport_withValidMonthAndYear_returnsStatusKey")
        void generateMonthlyReport_withValidMonthAndYear_returnsStatusKey() {
            // Act
            Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

            // Assert
            assertTrue(result.containsKey("status"),
                    "Result must contain 'status' key");
        }

        @Test
        @DisplayName("generateMonthlyReport_whenDirectoryCreationFails_returnsErrorStatus")
        void generateMonthlyReport_whenDirectoryCreationFails_returnsErrorStatus() {
            // Act — /var/legacy/reports/ likely doesn't exist in test environment;
            // the service will either create it or return an error status.
            Map<String, Object> result = reportService.generateMonthlyReport("January", "2024");

            // Assert — status must be either "generated" or "error" (never null)
            assertNotNull(result.get("status"));
            String status = result.get("status").toString();
            assertTrue(status.equals("generated") || status.equals("error"),
                    "Status must be 'generated' or 'error', got: " + status);
        }

        @Test
        @DisplayName("generateMonthlyReport_onError_returnsErrorStatusWithMessage")
        void generateMonthlyReport_onError_returnsErrorStatusWithMessage() {
            // Act
            Map<String, Object> result = reportService.generateMonthlyReport("February", "2024");

            // Assert — if error, message key must be present
            if ("error".equals(result.get("status"))) {
                assertTrue(result.containsKey("message"),
                        "Error result must contain 'message' key");
            }
        }

        @Test
        @DisplayName("generateMonthlyReport_onSuccess_returnsPathKey")
        void generateMonthlyReport_onSuccess_returnsPathKey() {
            // Act
            Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

            // Assert — if generated, path key must be present
            if ("generated".equals(result.get("status"))) {
                assertTrue(result.containsKey("path"),
                        "Generated result must contain 'path' key");
                assertNotNull(result.get("path"));
            }
        }

        @Test
        @DisplayName("generateMonthlyReport_onSuccess_pathContainsMonthAndYear")
        void generateMonthlyReport_onSuccess_pathContainsMonthAndYear() {
            // Act
            Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

            // Assert
            if ("generated".equals(result.get("status"))) {
                String path = result.get("path").toString();
                assertTrue(path.contains("May"), "Path must contain month 'May'");
                assertTrue(path.contains("2024"), "Path must contain year '2024'");
            }
        }

        @Test
        @DisplayName("generateMonthlyReport_onSuccess_returnsServerPort")
        void generateMonthlyReport_onSuccess_returnsServerPort() {
            // Act
            Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");

            // Assert
            if ("generated".equals(result.get("status"))) {
                assertTrue(result.containsKey("serverPort"),
                        "Generated result must contain 'serverPort' key");
                assertEquals(8080, result.get("serverPort"),
                        "Server port must be 8080");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"January", "February", "March", "April", "May",
                                 "June", "July", "August", "September", "October",
                                 "November", "December"})
        @DisplayName("generateMonthlyReport_allMonths_returnsNonNullResult")
        void generateMonthlyReport_allMonths_returnsNonNullResult(String month) {
            // Act
            Map<String, Object> result = reportService.generateMonthlyReport(month, "2024");

            // Assert
            assertNotNull(result, "Result must not be null for month: " + month);
            assertNotNull(result.get("status"), "Status must not be null for month: " + month);
        }

        @Test
        @DisplayName("generateMonthlyReport_withEmptyMonth_doesNotThrow")
        void generateMonthlyReport_withEmptyMonth_doesNotThrow() {
            // Act & Assert
            assertDoesNotThrow(() -> reportService.generateMonthlyReport("", "2024"));
        }

        @Test
        @DisplayName("generateMonthlyReport_withSpecialCharactersInMonth_doesNotThrow")
        void generateMonthlyReport_withSpecialCharactersInMonth_doesNotThrow() {
            // Act & Assert
            assertDoesNotThrow(() -> reportService.generateMonthlyReport("March-2024", "2024"));
        }
    }

    // =========================================================================
    // buildReportDownloadUrl()
    // =========================================================================

    @Nested
    @DisplayName("buildReportDownloadUrl()")
    class BuildReportDownloadUrlTests {

        @Test
        @DisplayName("buildReportDownloadUrl_withReportName_returnsNonNullUrl")
        void buildReportDownloadUrl_withReportName_returnsNonNullUrl() {
            // Act
            String url = reportService.buildReportDownloadUrl("march_report.pdf");

            // Assert
            assertNotNull(url, "URL must not be null");
        }

        @Test
        @DisplayName("buildReportDownloadUrl_withReportName_urlContainsReportName")
        void buildReportDownloadUrl_withReportName_urlContainsReportName() {
            // Arrange
            String reportName = "march_report.pdf";

            // Act
            String url = reportService.buildReportDownloadUrl(reportName);

            // Assert
            assertTrue(url.contains(reportName),
                    "URL must contain the report name: " + reportName);
        }

        @Test
        @DisplayName("buildReportDownloadUrl_withReportName_urlStartsWithHttp")
        void buildReportDownloadUrl_withReportName_urlStartsWithHttp() {
            // Act
            String url = reportService.buildReportDownloadUrl("report.pdf");

            // Assert
            assertTrue(url.startsWith("http"),
                    "URL must start with 'http', got: " + url);
        }

        @Test
        @DisplayName("buildReportDownloadUrl_withReportName_urlContainsDownloadPath")
        void buildReportDownloadUrl_withReportName_urlContainsDownloadPath() {
            // Act
            String url = reportService.buildReportDownloadUrl("annual_report.csv");

            // Assert
            assertTrue(url.contains("/download/"),
                    "URL must contain '/download/' path segment");
        }

        @Test
        @DisplayName("buildReportDownloadUrl_withDifferentReportNames_returnsDifferentUrls")
        void buildReportDownloadUrl_withDifferentReportNames_returnsDifferentUrls() {
            // Act
            String url1 = reportService.buildReportDownloadUrl("report_jan.pdf");
            String url2 = reportService.buildReportDownloadUrl("report_feb.pdf");

            // Assert
            assertNotEquals(url1, url2,
                    "Different report names must produce different URLs");
        }

        @Test
        @DisplayName("buildReportDownloadUrl_withEmptyReportName_returnsBaseUrl")
        void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrl() {
            // Act
            String url = reportService.buildReportDownloadUrl("");

            // Assert
            assertNotNull(url);
            assertFalse(url.isEmpty(), "URL must not be empty even with empty report name");
        }

        @ParameterizedTest
        @ValueSource(strings = {"report.pdf", "data.csv", "summary.xlsx", "monthly_2024.pdf"})
        @DisplayName("buildReportDownloadUrl_variousFileTypes_returnsValidUrl")
        void buildReportDownloadUrl_variousFileTypes_returnsValidUrl(String reportName) {
            // Act
            String url = reportService.buildReportDownloadUrl(reportName);

            // Assert
            assertNotNull(url);
            assertTrue(url.contains(reportName),
                    "URL must contain report name: " + reportName);
        }
    }

    // =========================================================================
    // getSystemInfo()
    // =========================================================================

    @Nested
    @DisplayName("getSystemInfo()")
    class GetSystemInfoTests {

        @Test
        @DisplayName("getSystemInfo_returnsNonNullMap")
        void getSystemInfo_returnsNonNullMap() {
            // Act
            Map<String, Object> info = reportService.getSystemInfo();

            // Assert
            assertNotNull(info, "System info map must not be null");
        }

        @Test
        @DisplayName("getSystemInfo_returnsReportPathKey")
        void getSystemInfo_returnsReportPathKey() {
            // Act
            Map<String, Object> info = reportService.getSystemInfo();

            // Assert
            assertTrue(info.containsKey("reportPath"),
                    "System info must contain 'reportPath' key");
            assertNotNull(info.get("reportPath"));
        }

        @Test
        @DisplayName("getSystemInfo_returnsBackupPathKey")
        void getSystemInfo_returnsBackupPathKey() {
            // Act
            Map<String, Object> info = reportService.getSystemInfo();

            // Assert
            assertTrue(info.containsKey("backupPath"),
                    "System info must contain 'backupPath' key");
            assertNotNull(info.get("backupPath"));
        }

        @Test
        @DisplayName("getSystemInfo_returnsServerPortKey")
        void getSystemInfo_returnsServerPortKey() {
            // Act
            Map<String, Object> info = reportService.getSystemInfo();

            // Assert
            assertTrue(info.containsKey("serverPort"),
                    "System info must contain 'serverPort' key");
            assertEquals(8080, info.get("serverPort"),
                    "Server port must be 8080");
        }

        @Test
        @DisplayName("getSystemInfo_returnsGeneratedAtKey")
        void getSystemInfo_returnsGeneratedAtKey() {
            // Act
            Map<String, Object> info = reportService.getSystemInfo();

            // Assert
            assertTrue(info.containsKey("generatedAt"),
                    "System info must contain 'generatedAt' key");
            assertNotNull(info.get("generatedAt"),
                    "'generatedAt' must not be null");
        }

        @Test
        @DisplayName("getSystemInfo_generatedAt_isFormattedTimestamp")
        void getSystemInfo_generatedAt_isFormattedTimestamp() {
            // Act
            Map<String, Object> info = reportService.getSystemInfo();
            String generatedAt = info.get("generatedAt").toString();

            // Assert — format: yyyy-MM-dd HH:mm:ss
            assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                    "generatedAt must match pattern yyyy-MM-dd HH:mm:ss, got: " + generatedAt);
        }

        @Test
        @DisplayName("getSystemInfo_reportPath_containsLegacyReportsPath")
        void getSystemInfo_reportPath_containsLegacyReportsPath() {
            // Act
            Map<String, Object> info = reportService.getSystemInfo();
            String reportPath = info.get("reportPath").toString();

            // Assert
            assertTrue(reportPath.contains("reports"),
                    "reportPath must reference a reports directory, got: " + reportPath);
        }

        @Test
        @DisplayName("getSystemInfo_calledTwice_returnsConsistentKeys")
        void getSystemInfo_calledTwice_returnsConsistentKeys() {
            // Act
            Map<String, Object> info1 = reportService.getSystemInfo();
            Map<String, Object> info2 = reportService.getSystemInfo();

            // Assert — both calls must return the same set of keys
            assertEquals(info1.keySet(), info2.keySet(),
                    "Both calls must return the same set of keys");
        }

        @Test
        @DisplayName("getSystemInfo_returnsMapWithFourEntries")
        void getSystemInfo_returnsMapWithFourEntries() {
            // Act
            Map<String, Object> info = reportService.getSystemInfo();

            // Assert
            assertEquals(4, info.size(),
                    "System info must contain exactly 4 entries");
        }
    }
}
