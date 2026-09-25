package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingControllerTest {

    private BookingController bookingController;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        bookingController = new BookingController();
        bookingService = mock(BookingService.class);
        ReflectionTestUtils.setField(bookingController, "bookingService", bookingService);
        ReflectionTestUtils.setField(bookingController, "reportBasePath", "/tmp/reports");
        ReflectionTestUtils.setField(bookingController, "inventoryEndpoint", "https://inventory-svc.internal/rooms");
    }

    // --- createBooking tests ---

    @Test
    void createBooking_withValidParameters_returnsConfirmedResponse() {
        // Arrange
        String guestName = "John Smith";
        String roomType = "SUITE";
        String checkIn = "2024-03-01";
        String checkOut = "2024-03-05";

        Map<String, Object> mockBooking = Map.of(
                "bookingId", "BK-12345678",
                "guestName", guestName,
                "roomType", roomType
        );
        when(bookingService.createBooking(guestName, roomType, checkIn, checkOut)).thenReturn(mockBooking);

        // Act
        Map<String, Object> result = bookingController.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(result);
        assertEquals("confirmed", result.get("status"));
        assertNotNull(result.get("booking"));
        assertEquals("BK-12345678", ((Map<?, ?>) result.get("booking")).get("bookingId"));
    }

    @Test
    void createBooking_withDeluxeRoom_returnsConfirmedResponse() {
        // Arrange
        String guestName = "Jane Doe";
        String roomType = "DELUXE";
        String checkIn = "2024-06-10";
        String checkOut = "2024-06-15";

        Map<String, Object> mockBooking = Map.of(
                "bookingId", "BK-ABCDEF12",
                "guestName", guestName,
                "roomType", roomType
        );
        when(bookingService.createBooking(guestName, roomType, checkIn, checkOut)).thenReturn(mockBooking);

        // Act
        Map<String, Object> result = bookingController.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(result);
        assertEquals("confirmed", result.get("status"));
        assertEquals("DELUXE", ((Map<?, ?>) result.get("booking")).get("roomType"));
    }

    // --- getBookingStatus tests ---

    @Test
    void getBookingStatus_withValidBookingId_returnsStatusResponse() {
        // Arrange
        String bookingId = "BK-12345678";
        Map<String, Object> mockDetails = Map.of("id", bookingId, "guest", "John Smith");
        when(bookingService.getBookingById(bookingId)).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus(bookingId);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("bookingId"));
        assertNotNull(result.get("details"));
        assertEquals("John Smith", ((Map<?, ?>) result.get("details")).get("guest"));
    }

    @Test
    void getBookingStatus_withNonExistentBookingId_returnsErrorDetails() {
        // Arrange
        String bookingId = "BK-NONEXISTENT";
        Map<String, Object> mockDetails = Map.of("error", "Booking not found: BK-NONEXISTENT");
        when(bookingService.getBookingById(bookingId)).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus(bookingId);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("bookingId"));
        assertNotNull(result.get("details"));
        assertEquals("Booking not found: BK-NONEXISTENT", ((Map<?, ?>) result.get("details")).get("error"));
    }

    // --- checkAvailability tests ---

    @Test
    void checkAvailability_withValidRoomType_returnsAvailabilityResponse() {
        // Arrange
        String roomType = "SUITE";
        when(bookingService.isRoomAvailable(roomType)).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability(roomType);

        // Assert
        assertNotNull(result);
        assertEquals(roomType, result.get("roomType"));
        assertEquals(true, result.get("available"));
        assertNotNull(result.get("inventoryEndpoint"));
        assertTrue(((String) result.get("inventoryEndpoint")).contains("/available"));
    }

    @Test
    void checkAvailability_withInvalidRoomType_returnsFalseAvailability() {
        // Arrange
        String roomType = "INVALID";
        when(bookingService.isRoomAvailable(roomType)).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability(roomType);

        // Assert
        assertNotNull(result);
        assertEquals(roomType, result.get("roomType"));
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_withStandardRoomType_returnsTrueAvailability() {
        // Arrange
        String roomType = "STANDARD";
        when(bookingService.isRoomAvailable(roomType)).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability(roomType);

        // Assert
        assertNotNull(result);
        assertEquals(true, result.get("available"));
    }

    // --- downloadReport tests ---

    @Test
    void downloadReport_withValidMonth_returnsReportPathAndMessage() {
        // Arrange
        String month = "03";
        when(bookingService.generateReport(month)).thenReturn("Report generation triggered for: 03 via https://payment-svc.internal/charge");

        // Act
        Map<String, Object> result = bookingController.downloadReport(month);

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("reportPath"));
        assertTrue(((String) result.get("reportPath")).contains("03_bookings.pdf"));
        assertTrue(((String) result.get("reportPath")).startsWith("/tmp/reports/"));
        assertNotNull(result.get("message"));
        assertTrue(((String) result.get("message")).contains("03"));
    }

    @Test
    void downloadReport_withDifferentMonth_returnsReportPathAndMessage() {
        // Arrange
        String month = "12";
        when(bookingService.generateReport(month)).thenReturn("Report generation triggered for: 12 via https://payment-svc.internal/charge");

        // Act
        Map<String, Object> result = bookingController.downloadReport(month);

        // Assert
        assertNotNull(result);
        assertTrue(((String) result.get("reportPath")).contains("12_bookings.pdf"));
        assertNotNull(result.get("message"));
    }
}
