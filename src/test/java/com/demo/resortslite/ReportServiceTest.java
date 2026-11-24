package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    @Test
    void generateMonthlyReport_returnsStatusMap() {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        assertNotNull(result);
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status));
        if ("generated".equals(status)) {
            assertEquals("/var/legacy/reports/resort_report_03_2024.csv", result.get("path"));
            assertEquals(8080, result.get("serverPort"));
        } else {
            assertNotNull(result.get("message"));
        }
    }

    @Test
    void buildReportDownloadUrl_appendsName() {
        assertEquals("http://reports.resorts-internal.com:8080/download/r.pdf",
                reportService.buildReportDownloadUrl("r.pdf"));
    }

    @Test
    void buildReportDownloadUrl_emptyName() {
        assertEquals("http://reports.resorts-internal.com:8080/download/",
                reportService.buildReportDownloadUrl(""));
    }

    @Test
    void getSystemInfo_containsExpectedKeys() {
        Map<String, Object> info = reportService.getSystemInfo();

        assertEquals("/var/legacy/reports/", info.get("reportPath"));
        assertEquals("C:\\ResortBackups\\nightly\\", info.get("backupPath"));
        assertEquals(8080, info.get("serverPort"));
        assertTrue(((String) info.get("generatedAt")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }
}
