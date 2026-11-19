package com.demo.resortslite;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportServiceTest {

    private final ReportService service = new ReportService();

    @Test
    void generateMonthlyReport_returnsStatusGeneratedOrError() {
        Map<String, Object> result = service.generateMonthlyReport("03", "2024");
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
    void generateMonthlyReport_invalidName_returnsError() {
        Map<String, Object> result = service.generateMonthlyReport("a/b/c", "x");
        assertEquals("error", result.get("status"));
    }

    @Test
    void buildReportDownloadUrl_appendsName() {
        assertEquals("http://reports.resorts-internal.com:8080/download/r.csv",
                service.buildReportDownloadUrl("r.csv"));
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
