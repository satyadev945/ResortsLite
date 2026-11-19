package com.demo.resortslite;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private final ReportService service = new ReportService();

    @Test
    void generateMonthlyReport_returnsStatusAndHandlesFilesystem() {
        Map<String, Object> r = service.generateMonthlyReport("03", "2024");
        assertNotNull(r);
        String status = (String) r.get("status");
        assertTrue("generated".equals(status) || "error".equals(status));
        if ("generated".equals(status)) {
            assertEquals("/var/legacy/reports/resort_report_03_2024.csv", r.get("path"));
            assertEquals(8080, r.get("serverPort"));
        } else {
            assertNotNull(r.get("message"));
        }
    }

    @Test
    void buildReportDownloadUrl_appendsName() {
        assertEquals("http://reports.resorts-internal.com:8080/download/r.pdf",
                service.buildReportDownloadUrl("r.pdf"));
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
