package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        // Inject externalised config values
        ReflectionTestUtils.setField(bookingController, "inventoryEndpoint",
                "https://inventory-svc.internal:8081/rooms");
        ReflectionTestUtils.setField(bookingController, "reportBasePath", "/tmp/reports/");
    }

    // ─── createBooking ────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsStatusConfirmed() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Alice");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidParams_returnsBookingInResponse() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        // Assert
        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_delegatesToBookingService() {
        // Arrange
        when(bookingService.createBooking("Carol", "VILLA", "2024-08-01", "2024-08-10"))
                .thenReturn(new HashMap<>());

        // Act
        bookingController.createBooking("Carol", "VILLA", "2024-08-01", "2024-08-10", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Carol", "VILLA", "2024-08-01", "2024-08-10");
    }

    @Test
    void createBooking_doesNotStoreStateInSession() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        // Act
        bookingController.createBooking("Dave", "STANDARD", "2024-09-01", "2024-09-04", session);

        // Assert — session must remain empty (cr-java-0065)
        assertFalse(session.getAttributeNames().hasMoreElements(),
                "Session should not hold booking state (cr-java-0065)");
    }

    @Test
    void createBooking_responseContainsBothStatusAndBooking() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-XYZ");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-05", session);

        // Assert
        assertTrue(response.containsKey("status"));
        assertTrue(response.containsKey("booking"));
    }

    // ─── getBookingStatus ─────────────────────────────────────────────────────

    @Test
    void getBookingStatus_withValidBookingId_returnsBookingIdInResponse() {
        // Arrange
        when(bookingService.getBookingById("BK-12345678")).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-12345678", session);

        // Assert
        assertEquals("BK-12345678", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_withValidBookingId_returnsDetailsInResponse() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        details.put("guest", "Frank");
        when(bookingService.getBookingById("BK-ABCDEF")).thenReturn(details);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCDEF", session);

        // Assert
        assertNotNull(result.get("details"));
    }

    @Test
    void getBookingStatus_delegatesToBookingService() {
        // Arrange
        when(bookingService.getBookingById("BK-TEST")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-TEST", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TEST");
    }

    @Test
    void getBookingStatus_doesNotReadFromSession() {
        // Arrange — session has no attributes
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act — should not throw even with empty session (cr-java-0065)
        Map<String, Object> result = bookingController.getBookingStatus("BK-NOSESSION", session);

        // Assert
        assertNotNull(result);
    }

    @Test
    void getBookingStatus_whenBookingNotFound_returnsErrorDetails() {
        // Arrange
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put("error", "Booking not found: BK-MISSING");
        when(bookingService.getBookingById("BK-MISSING")).thenReturn(errorMap);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-MISSING", session);

        // Assert
        Map<?, ?> details = (Map<?, ?>) result.get("details");
        assertTrue(details.containsKey("error"));
    }

    // ─── checkAvailability ────────────────────────────────────────────────────

    @Test
    void checkAvailability_withAvailableRoom_returnsAvailableTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsAvailableFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_returnsRoomTypeInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("DELUXE", result.get("roomType"));
    }

    @Test
    void checkAvailability_returnsInventoryEndpointInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert — externalised HTTPS endpoint (cr-java-0088)
        assertNotNull(result.get("inventoryEndpoint"));
        assertTrue(result.get("inventoryEndpoint").toString().startsWith("https://"),
                "Inventory endpoint must use HTTPS");
    }

    @Test
    void checkAvailability_delegatesToBookingService() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        bookingController.checkAvailability("VILLA");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    // ─── downloadReport ───────────────────────────────────────────────────────

    @Test
    void downloadReport_withMonth_returnsReportPathInResponse() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report triggered for March");

        // Act
        Map<String, Object> result = bookingController.downloadReport("March");

        // Assert
        assertNotNull(result.get("reportPath"));
        assertTrue(result.get("reportPath").toString().contains("March"));
    }

    @Test
    void downloadReport_withMonth_returnsMessageInResponse() {
        // Arrange
        when(bookingService.generateReport("April")).thenReturn("Report triggered for April");

        // Act
        Map<String, Object> result = bookingController.downloadReport("April");

        // Assert
        assertNotNull(result.get("message"));
        assertEquals("Report triggered for April", result.get("message"));
    }

    @Test
    void downloadReport_reportPath_usesPdfExtension() {
        // Arrange
        when(bookingService.generateReport("May")).thenReturn("ok");

        // Act
        Map<String, Object> result = bookingController.downloadReport("May");

        // Assert
        assertTrue(result.get("reportPath").toString().endsWith("_bookings.pdf"));
    }

    @Test
    void downloadReport_reportPath_usesExternalisedBasePath() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("ok");

        // Act
        Map<String, Object> result = bookingController.downloadReport("June");

        // Assert — czr-java-001: path must not be hardcoded /var/legacy/reports/
        String path = result.get("reportPath").toString();
        assertFalse(path.startsWith("/var/legacy/reports/"),
                "Report path must not use hardcoded legacy path");
        assertTrue(path.startsWith("/tmp/reports/"),
                "Report path should use injected base path");
    }

    @Test
    void downloadReport_delegatesToBookingService() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("ok");

        // Act
        bookingController.downloadReport("July");

        // Assert
        verify(bookingService, times(1)).generateReport("July");
    }

    @Test
    void downloadReport_withDifferentMonths_producesDistinctPaths() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("ok");

        // Act
        Map<String, Object> result1 = bookingController.downloadReport("January");
        Map<String, Object> result2 = bookingController.downloadReport("February");

        // Assert
        assertNotEquals(result1.get("reportPath"), result2.get("reportPath"));
    }
}
