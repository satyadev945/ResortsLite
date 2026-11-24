package com.demo.resortslite;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportServiceTest {

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService();
    }

    @Test
    void buildReportDownloadUrl_returnsUrlWithReportName() {
        assertEquals("http://reports.resorts-internal.com:8080/download/r.pdf",
                service.buildReportDownloadUrl("r.pdf"));
    }

    @Test
    void buildReportDownloadUrl_nullName_appendsNullText() {
        assertEquals("http://reports.resorts-internal.com:8080/download/null",
                service.buildReportDownloadUrl(null));
    }

    @Test
    void buildReportDownloadUrl_emptyName_returnsBaseUrl() {
        assertEquals("http://reports.resorts-internal.com:8080/download/",
                service.buildReportDownloadUrl(""));
    }

    @Test
    void getSystemInfo_containsExpectedEntries() {
        Map<String, Object> info = service.getSystemInfo();
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
        assertEquals("C:\\ResortBackups\\nightly\\", info.get("backupPath"));
        assertEquals(8080, info.get("serverPort"));
        assertNotNull(info.get("generatedAt"));
        assertEquals(4, info.size());
    }

    @Test
    void getSystemInfo_timestampMatchesFormat() {
        String ts = (String) service.getSystemInfo().get("generatedAt");
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void generateMonthlyReport_returnsStatusGeneratedOrError() {
        Map<String, Object> result = service.generateMonthlyReport("03", "2024");
        assertNotNull(result.get("status"));
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertEquals("/var/legacy/reports/resort_report_03_2024.csv", result.get("path"));
            assertEquals(8080, result.get("serverPort"));
        } else {
            assertEquals("error", status);
            assertTrue(result.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_invalidMonthWithSeparator_returnsError() {
        // File name contains a path separator to a nonexistent directory -> IOException
        Map<String, Object> result = service.generateMonthlyReport("no/such/dir", "2024");
        assertEquals("error", result.get("status"));
        assertTrue(result.containsKey("message"));
    }
}
