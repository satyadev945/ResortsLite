package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService();
    }

    @Test
    void buildReportDownloadUrl_returnsUrlWithReportName() {
        String url = service.buildReportDownloadUrl("march.pdf");
        assertEquals("http://reports.resorts-internal.com:8080/download/march.pdf", url);
    }

    @Test
    void buildReportDownloadUrl_emptyName_returnsBaseUrl() {
        assertTrue(service.buildReportDownloadUrl("").endsWith("/download/"));
    }

    @Test
    void getSystemInfo_containsExpectedKeys() {
        Map<String, Object> info = service.getSystemInfo();
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
        assertEquals("C:\\ResortBackups\\nightly\\", info.get("backupPath"));
        assertEquals(8080, info.get("serverPort"));
        assertNotNull(info.get("generatedAt"));
        assertTrue(info.get("generatedAt").toString().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void generateMonthlyReport_returnsStatusGeneratedOrError() {
        Map<String, Object> result = service.generateMonthlyReport("03", "2024");
        assertNotNull(result.get("status"));
        String status = (String) result.get("status");
        assertTrue(status.equals("generated") || status.equals("error"));
        if (status.equals("generated")) {
            assertEquals("/var/legacy/reports/resort_report_03_2024.csv", result.get("path"));
            assertEquals(8080, result.get("serverPort"));
        } else {
            assertNotNull(result.get("message"));
        }
    }

    @Test
    void generateMonthlyReport_invalidFileName_returnsError() {
        // A month containing a path separator to a non-existent directory forces an IOException
        Map<String, Object> result = service.generateMonthlyReport("no/such/dir", "2024");
        assertEquals("error", result.get("status"));
        assertNotNull(result.get("message"));
    }
}
