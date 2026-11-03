package com.demo.resortslite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    @Test
    void generateMonthlyReport_createsReportFileAndReturnsMetadata() throws Exception {
        ReportService reportService = new ReportService();
        Path tempDir = Files.createTempDirectory("reports-test");
        TestUtils.setField(reportService, "reportBasePath", tempDir.toString());
        TestUtils.setField(reportService, "backupPath", "backup-dir");
        TestUtils.setField(reportService, "serverPort", 9090);
        TestUtils.setField(reportService, "reportDownloadBaseUrl", "https://example.test/download");

        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        assertEquals("generated", result.get("status"));
        assertEquals(9090, result.get("serverPort"));
        Path reportPath = Path.of(result.get("path").toString());
        assertTrue(Files.exists(reportPath));
        String content = Files.readString(reportPath);
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"));
        assertTrue(content.contains("BK-001"));
    }

    @Test
    void buildReportDownloadUrl_appendsReportName() {
        ReportService reportService = new ReportService();
        TestUtils.setField(reportService, "reportDownloadBaseUrl", "https://example.test/download");

        String url = reportService.buildReportDownloadUrl("monthly.csv");

        assertEquals("https://example.test/download/monthly.csv", url);
    }

    @Test
    void getSystemInfo_returnsConfiguredValues() {
        ReportService reportService = new ReportService();
        TestUtils.setField(reportService, "reportBasePath", "reports-dir");
        TestUtils.setField(reportService, "backupPath", "backup-dir");
        TestUtils.setField(reportService, "serverPort", 8088);

        Map<String, Object> info = reportService.getSystemInfo();

        assertEquals("reports-dir", info.get("reportPath"));
        assertEquals("backup-dir", info.get("backupPath"));
        assertEquals(8088, info.get("serverPort"));
        assertNotNull(info.get("generatedAt"));
        assertFalse(info.get("generatedAt").toString().isBlank());
    }
}
