package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for BookingController.
 * Covers createBooking, getBookingStatus, checkAvailability, and downloadReport endpoints.
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingController, "inventoryEndpoint",
                "https://inventory-svc/rooms");
        ReflectionTestUtils.setField(bookingController, "reportBasePath",
                "/tmp/reports/");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "John Smith");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"),
                "Response status should be 'confirmed'");
    }

    @Test
    void createBooking_withValidParams_returnsBookingInResponse() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Jane Doe");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        assertNotNull(response.get("booking"), "Response should contain 'booking' key");
        @SuppressWarnings("unchecked")
        Map<String, Object> booking = (Map<String, Object>) response.get("booking");
        assertEquals("Jane Doe", booking.get("guestName"));
    }

    @Test
    void createBooking_delegatesToBookingService() {
        // Arrange
        when(bookingService.createBooking("Alice", "VILLA", "2024-05-01", "2024-05-07"))
                .thenReturn(new HashMap<>());

        // Act
        bookingController.createBooking("Alice", "VILLA", "2024-05-01", "2024-05-07");

        // Assert
        verify(bookingService, times(1))
                .createBooking("Alice", "VILLA", "2024-05-01", "2024-05-07");
    }

    @Test
    void createBooking_responseContainsBothStatusAndBooking() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "STANDARD", "2024-06-01", "2024-06-02");

        // Assert
        assertTrue(response.containsKey("status"), "Response must contain 'status'");
        assertTrue(response.containsKey("booking"), "Response must contain 'booking'");
    }

    @Test
    void createBooking_withAllRoomTypes_returnsConfirmed() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        // Act & Assert for each room type
        for (String roomType : new String[]{"STANDARD", "DELUXE", "SUITE", "VILLA"}) {
            Map<String, Object> response = bookingController.createBooking(
                    "Guest", roomType, "2024-07-01", "2024-07-02");
            assertEquals("confirmed", response.get("status"),
                    "Should return confirmed for room type: " + roomType);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingStatus tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingStatus_withValidId_returnsBookingIdInResponse() {
        // Arrange
        when(bookingService.getBookingById("BK-12345678")).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("bookingId"),
                "Response should echo back the bookingId");
    }

    @Test
    void getBookingStatus_withValidId_returnsDetailsFromService() {
        // Arrange
        Map<String, Object> serviceResult = new HashMap<>();
        serviceResult.put("guest", "John Smith");
        serviceResult.put("room", "SUITE");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(serviceResult);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234");

        // Assert
        assertNotNull(result.get("details"), "Response should contain 'details'");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.get("details");
        assertEquals("John Smith", details.get("guest"));
    }

    @Test
    void getBookingStatus_delegatesToBookingService() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-TEST");

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TEST");
    }

    @Test
    void getBookingStatus_responseContainsBothBookingIdAndDetails() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-XYZ");

        // Assert
        assertTrue(result.containsKey("bookingId"), "Response must contain 'bookingId'");
        assertTrue(result.containsKey("details"), "Response must contain 'details'");
    }

    @Test
    void getBookingStatus_withNotFoundId_returnsErrorDetails() {
        // Arrange
        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("error", "Booking not found: BK-NOTFOUND");
        when(bookingService.getBookingById("BK-NOTFOUND")).thenReturn(errorResult);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-NOTFOUND");

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.get("details");
        assertTrue(details.containsKey("error"), "Details should contain error for not-found booking");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAvailability tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void checkAvailability_withValidRoomType_returnsRoomTypeInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertEquals("SUITE", result.get("roomType"));
    }

    @Test
    void checkAvailability_withAvailableRoom_returnsTrueAvailability() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsFalseAvailability() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_includesInventoryEndpointInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertEquals("https://inventory-svc/rooms", result.get("inventoryEndpoint"),
                "Response should include the inventory endpoint (HTTPS)");
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

    @Test
    void checkAvailability_responseContainsAllRequiredKeys() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(result.containsKey("roomType"), "Response must contain 'roomType'");
        assertTrue(result.containsKey("inventoryEndpoint"), "Response must contain 'inventoryEndpoint'");
        assertTrue(result.containsKey("available"), "Response must contain 'available'");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // downloadReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void downloadReport_withValidMonth_returnsReportPath() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        // Act
        Map<String, Object> result = bookingController.downloadReport("March");

        // Assert
        assertNotNull(result.get("reportPath"), "Response should contain 'reportPath'");
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.contains("March"), "Report path should contain the month");
        assertTrue(reportPath.endsWith("_bookings.pdf"), "Report path should end with '_bookings.pdf'");
    }

    @Test
    void downloadReport_reportPathUsesInjectedBasePath() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("Report generated");

        // Act
        Map<String, Object> result = bookingController.downloadReport("April");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.startsWith("/tmp/reports/"),
                "Report path should start with the injected base path");
    }

    @Test
    void downloadReport_containsMessageFromService() {
        // Arrange
        when(bookingService.generateReport("May")).thenReturn("Report triggered for May");

        // Act
        Map<String, Object> result = bookingController.downloadReport("May");

        // Assert
        assertEquals("Report triggered for May", result.get("message"));
    }

    @Test
    void downloadReport_delegatesToBookingService() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Done");

        // Act
        bookingController.downloadReport("June");

        // Assert
        verify(bookingService, times(1)).generateReport("June");
    }

    @Test
    void downloadReport_responseContainsBothReportPathAndMessage() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("Done");

        // Act
        Map<String, Object> result = bookingController.downloadReport("July");

        // Assert
        assertTrue(result.containsKey("reportPath"), "Response must contain 'reportPath'");
        assertTrue(result.containsKey("message"), "Response must contain 'message'");
    }

    @Test
    void downloadReport_reportPathFormat_isCorrect() {
        // Arrange
        when(bookingService.generateReport("08")).thenReturn("Done");

        // Act
        Map<String, Object> result = bookingController.downloadReport("08");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertEquals("/tmp/reports/08_bookings.pdf", reportPath,
                "Report path should be basePath + month + '_bookings.pdf'");
    }
}
