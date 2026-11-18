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
    void generateMonthlyReport_returnsStatusAndNeverNull() {
        Map<String, Object> result = service.generateMonthlyReport("03", "2024");
        assertNotNull(result);
        Object status = result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status));
    }

    @Test
    void generateMonthlyReport_generatedContainsPathAndPort() {
        Map<String, Object> result = service.generateMonthlyReport("04", "2024");
        if ("generated".equals(result.get("status"))) {
            assertEquals("/var/legacy/reports/resort_report_04_2024.csv", result.get("path"));
            assertEquals(8080, result.get("serverPort"));
        } else {
            assertEquals("error", result.get("status"));
            assertTrue(result.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_invalidFileName_returnsError() {
        // a slash in the month produces a path whose parent directory does not exist
        Map<String, Object> result = service.generateMonthlyReport("no/such/dir", "2024");
        assertEquals("error", result.get("status"));
        assertNotNull(result.get("message"));
    }

    @Test
    void buildReportDownloadUrl_appendsReportName() {
        assertEquals("http://reports.resorts-internal.com:8080/download/r.pdf",
                service.buildReportDownloadUrl("r.pdf"));
    }

    @Test
    void buildReportDownloadUrl_emptyName() {
        assertEquals("http://reports.resorts-internal.com:8080/download/",
                service.buildReportDownloadUrl(""));
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
}
