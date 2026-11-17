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
    void generateMonthlyReport_returnsStatusEntry() {
        Map<String, Object> result = service.generateMonthlyReport("03", "2024");
        assertNotNull(result);
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status));
    }

    @Test
    void generateMonthlyReport_generatedOrErrorContainsExpectedKeys() {
        Map<String, Object> result = service.generateMonthlyReport("04", "2025");
        if ("generated".equals(result.get("status"))) {
            assertEquals("/var/legacy/reports/resort_report_04_2025.csv", result.get("path"));
            assertEquals(8080, result.get("serverPort"));
        } else {
            assertEquals("error", result.get("status"));
            assertTrue(result.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_withNullArgs_doesNotThrow() {
        Map<String, Object> result = assertDoesNotThrow(() -> service.generateMonthlyReport(null, null));
        assertNotNull(result.get("status"));
    }

    @Test
    void buildReportDownloadUrl_appendsReportName() {
        assertEquals("http://reports.resorts-internal.com:8080/download/jan.pdf",
                service.buildReportDownloadUrl("jan.pdf"));
    }

    @Test
    void buildReportDownloadUrl_emptyName() {
        assertEquals("http://reports.resorts-internal.com:8080/download/", service.buildReportDownloadUrl(""));
    }

    @Test
    void getSystemInfo_returnsPathsAndPort() {
        Map<String, Object> info = service.getSystemInfo();
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
        assertEquals("C:\\ResortBackups\\nightly\\", info.get("backupPath"));
        assertEquals(8080, info.get("serverPort"));
        assertEquals(4, info.size());
    }

    @Test
    void getSystemInfo_generatedAtHasTimestampFormat() {
        String ts = (String) service.getSystemInfo().get("generatedAt");
        assertNotNull(ts);
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }
}
