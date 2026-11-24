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
    void generateMonthlyReport_returnsStatusEitherGeneratedOrError() {
        Map<String, Object> r = service.generateMonthlyReport("03", "2024");
        assertNotNull(r.get("status"));
        if ("generated".equals(r.get("status"))) {
            assertEquals("/var/legacy/reports/resort_report_03_2024.csv", r.get("path"));
            assertEquals(8080, r.get("serverPort"));
        } else {
            assertEquals("error", r.get("status"));
            assertTrue(r.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_nullArgs_stillReturnsMap() {
        Map<String, Object> r = service.generateMonthlyReport(null, null);
        assertNotNull(r);
        assertTrue(r.containsKey("status"));
    }

    @Test
    void buildReportDownloadUrl_appendsName() {
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
        assertTrue(((String) info.get("generatedAt")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }
}
