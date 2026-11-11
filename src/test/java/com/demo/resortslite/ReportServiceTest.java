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
    void generateMonthlyReport_returnsStatus() {
        Map<String, Object> result = service.generateMonthlyReport("MAR", "2024");
        assertNotNull(result);
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status));
        if ("generated".equals(status)) {
            assertEquals("/var/legacy/reports/resort_report_MAR_2024.csv", result.get("path"));
            assertEquals(8080, result.get("serverPort"));
        } else {
            assertTrue(result.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_invalidFileName_returnsError() {
        // A month containing a path separator points to a non-existent directory => IOException
        Map<String, Object> result = service.generateMonthlyReport("no/such/dir", "x");
        assertEquals("error", result.get("status"));
        assertNotNull(result.get("message"));
    }

    @Test
    void buildReportDownloadUrl_concatenatesName() {
        assertEquals("http://reports.resorts-internal.com:8080/download/r.pdf",
                service.buildReportDownloadUrl("r.pdf"));
    }

    @Test
    void buildReportDownloadUrl_emptyName() {
        assertEquals("http://reports.resorts-internal.com:8080/download/", service.buildReportDownloadUrl(""));
    }

    @Test
    void getSystemInfo_containsExpectedKeys() {
        Map<String, Object> info = service.getSystemInfo();
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
        assertEquals("C:\\ResortBackups\\nightly\\", info.get("backupPath"));
        assertEquals(8080, info.get("serverPort"));
        assertNotNull(info.get("generatedAt"));
        assertTrue(((String) info.get("generatedAt")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }
}
