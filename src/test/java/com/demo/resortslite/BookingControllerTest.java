package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingController Tests")
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingController, "inventoryEndpoint",
                "https://inventory-svc.internal:8081/rooms");
        ReflectionTestUtils.setField(bookingController, "reportBasePath",
                "/tmp/reports");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createBooking_validParams_returnsConfirmedStatus")
    void createBooking_validParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-12345678");
        mockBooking.put("guestName", "John Smith");
        mockBooking.put("roomType", "SUITE");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(response, "Response should not be null");
        assertEquals("confirmed", response.get("status"),
                "Status should be 'confirmed'");
    }

    @Test
    @DisplayName("createBooking_validParams_returnsBookingInResponse")
    void createBooking_validParams_returnsBookingInResponse() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCDEF12");
        mockBooking.put("guestName", "Jane Doe");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        assertTrue(response.containsKey("booking"), "Response should contain 'booking' key");
        assertNotNull(response.get("booking"), "Booking should not be null");
    }

    @Test
    @DisplayName("createBooking_delegatesToBookingService")
    void createBooking_delegatesToBookingService() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        when(bookingService.createBooking("Alice", "VILLA", "2024-05-01", "2024-05-07"))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Alice", "VILLA", "2024-05-01", "2024-05-07");

        // Assert
        verify(bookingService, times(1))
                .createBooking("Alice", "VILLA", "2024-05-01", "2024-05-07");
    }

    @Test
    @DisplayName("createBooking_responseContainsBothStatusAndBooking")
    void createBooking_responseContainsBothStatusAndBooking() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-TEST1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "STANDARD", "2024-06-01", "2024-06-02");

        // Assert
        assertEquals(2, response.size(), "Response should have exactly 2 keys: status and booking");
        assertTrue(response.containsKey("status"));
        assertTrue(response.containsKey("booking"));
    }

    @Test
    @DisplayName("createBooking_bookingServiceReturnsData_responseContainsSameData")
    void createBooking_bookingServiceReturnsData_responseContainsSameData() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-XYZ99999");
        mockBooking.put("roomType", "SUITE");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Charlie", "SUITE", "2024-07-01", "2024-07-05");

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> booking = (Map<String, Object>) response.get("booking");
        assertEquals("BK-XYZ99999", booking.get("bookingId"));
        assertEquals("SUITE", booking.get("roomType"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingStatus tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getBookingStatus_existingBookingId_returnsBookingIdInResponse")
    void getBookingStatus_existingBookingId_returnsBookingIdInResponse() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-12345678");
        mockDetails.put("guest", "John Smith");
        when(bookingService.getBookingById("BK-12345678")).thenReturn(mockDetails);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-12345678");

        // Assert
        assertNotNull(response);
        assertEquals("BK-12345678", response.get("bookingId"),
                "Response should echo back the booking ID");
    }

    @Test
    @DisplayName("getBookingStatus_existingBookingId_returnsDetailsInResponse")
    void getBookingStatus_existingBookingId_returnsDetailsInResponse() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-ABCDEF12");
        when(bookingService.getBookingById("BK-ABCDEF12")).thenReturn(mockDetails);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-ABCDEF12");

        // Assert
        assertTrue(response.containsKey("details"), "Response should contain 'details' key");
        assertNotNull(response.get("details"));
    }

    @Test
    @DisplayName("getBookingStatus_delegatesToBookingService")
    void getBookingStatus_delegatesToBookingService() {
        // Arrange
        when(bookingService.getBookingById("BK-TEST0001")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-TEST0001");

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TEST0001");
    }

    @Test
    @DisplayName("getBookingStatus_responseContainsBothBookingIdAndDetails")
    void getBookingStatus_responseContainsBothBookingIdAndDetails() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-ANY");

        // Assert
        assertEquals(2, response.size(), "Response should have exactly 2 keys");
        assertTrue(response.containsKey("bookingId"));
        assertTrue(response.containsKey("details"));
    }

    @Test
    @DisplayName("getBookingStatus_notFoundBooking_returnsErrorDetails")
    void getBookingStatus_notFoundBooking_returnsErrorDetails() {
        // Arrange
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put("error", "Booking not found: BK-MISSING");
        when(bookingService.getBookingById("BK-MISSING")).thenReturn(errorMap);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-MISSING");

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) response.get("details");
        assertTrue(details.containsKey("error"),
                "Details should contain error when booking not found");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAvailability tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkAvailability_availableRoom_returnsAvailableTrue")
    void checkAvailability_availableRoom_returnsAvailableTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(response);
        assertEquals(true, response.get("available"),
                "Available should be true for SUITE");
    }

    @Test
    @DisplayName("checkAvailability_unavailableRoom_returnsAvailableFalse")
    void checkAvailability_unavailableRoom_returnsAvailableFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, response.get("available"),
                "Available should be false for PENTHOUSE");
    }

    @Test
    @DisplayName("checkAvailability_responseContainsRoomType")
    void checkAvailability_responseContainsRoomType() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("DELUXE", response.get("roomType"),
                "Response should echo back the room type");
    }

    @Test
    @DisplayName("checkAvailability_responseContainsInventoryEndpoint")
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(response.containsKey("inventoryEndpoint"),
                "Response should contain 'inventoryEndpoint'");
        String endpoint = (String) response.get("inventoryEndpoint");
        assertTrue(endpoint.startsWith("https://"),
                "Inventory endpoint should use HTTPS");
    }

    @Test
    @DisplayName("checkAvailability_delegatesToBookingService")
    void checkAvailability_delegatesToBookingService() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        bookingController.checkAvailability("VILLA");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    @Test
    @DisplayName("checkAvailability_responseHasThreeKeys")
    void checkAvailability_responseHasThreeKeys() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertEquals(3, response.size(),
                "Response should have 3 keys: roomType, inventoryEndpoint, available");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // downloadReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("downloadReport_validMonth_returnsReportPath")
    void downloadReport_validMonth_returnsReportPath() {
        // Arrange
        when(bookingService.generateReport("2024-03"))
                .thenReturn("Report generation triggered for: 2024-03");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-03");

        // Assert
        assertNotNull(response);
        assertTrue(response.containsKey("reportPath"),
                "Response should contain 'reportPath'");
    }

    @Test
    @DisplayName("downloadReport_reportPathContainsMonth")
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("2024-06"))
                .thenReturn("Report generation triggered for: 2024-06");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-06");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.contains("2024-06"),
                "Report path should contain the month");
    }

    @Test
    @DisplayName("downloadReport_reportPathEndsWith_bookingsPdf")
    void downloadReport_reportPathEndsWith_bookingsPdf() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-09");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.endsWith("_bookings.pdf"),
                "Report path should end with '_bookings.pdf'");
    }

    @Test
    @DisplayName("downloadReport_responseContainsMessage")
    void downloadReport_responseContainsMessage() {
        // Arrange
        String expectedMessage = "Report generation triggered for: 2024-10";
        when(bookingService.generateReport("2024-10")).thenReturn(expectedMessage);

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-10");

        // Assert
        assertTrue(response.containsKey("message"),
                "Response should contain 'message' key");
        assertEquals(expectedMessage, response.get("message"));
    }

    @Test
    @DisplayName("downloadReport_delegatesToBookingService")
    void downloadReport_delegatesToBookingService() {
        // Arrange
        when(bookingService.generateReport("2024-11")).thenReturn("triggered");

        // Act
        bookingController.downloadReport("2024-11");

        // Assert
        verify(bookingService, times(1)).generateReport("2024-11");
    }

    @Test
    @DisplayName("downloadReport_responseHasTwoKeys")
    void downloadReport_responseHasTwoKeys() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-12");

        // Assert
        assertEquals(2, response.size(),
                "Response should have 2 keys: reportPath and message");
        assertTrue(response.containsKey("reportPath"));
        assertTrue(response.containsKey("message"));
    }

    @Test
    @DisplayName("downloadReport_reportPathUsesConfiguredBasePath")
    void downloadReport_reportPathUsesConfiguredBasePath() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-01");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.startsWith("/tmp/reports"),
                "Report path should start with the configured base path");
    }
}
