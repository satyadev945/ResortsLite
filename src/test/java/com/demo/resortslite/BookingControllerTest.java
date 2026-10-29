package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link BookingController}.
 * Covers createBooking, getBookingStatus, checkAvailability, downloadReport.
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> serviceResult = new HashMap<>();
        serviceResult.put("bookingId", "BK-ABCD1234");
        serviceResult.put("guestName", "Alice Smith");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(serviceResult);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice Smith", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidParams_returnsBookingInResponse() {
        Map<String, Object> serviceResult = new HashMap<>();
        serviceResult.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(serviceResult);

        Map<String, Object> response = bookingController.createBooking(
                "Bob Jones", "DELUXE", "2024-07-01", "2024-07-05");

        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_delegatesToBookingService() {
        Map<String, Object> serviceResult = new HashMap<>();
        when(bookingService.createBooking("Carol", "VILLA", "2024-08-01", "2024-08-10"))
                .thenReturn(serviceResult);

        bookingController.createBooking("Carol", "VILLA", "2024-08-01", "2024-08-10");

        verify(bookingService, times(1))
                .createBooking("Carol", "VILLA", "2024-08-01", "2024-08-10");
    }

    @Test
    void createBooking_responseContainsStatusKey() {
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        Map<String, Object> response = bookingController.createBooking(
                "Dave", "STANDARD", "2024-09-01", "2024-09-03");

        assertTrue(response.containsKey("status"));
    }

    @Test
    void createBooking_responseContainsBookingKey() {
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        Map<String, Object> response = bookingController.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-07");

        assertTrue(response.containsKey("booking"));
    }

    @Test
    void createBooking_bookingValueMatchesServiceResult() {
        Map<String, Object> serviceResult = new HashMap<>();
        serviceResult.put("bookingId", "BK-XYZ99999");
        serviceResult.put("guestName", "Frank");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(serviceResult);

        Map<String, Object> response = bookingController.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-04");

        @SuppressWarnings("unchecked")
        Map<String, Object> booking = (Map<String, Object>) response.get("booking");
        assertEquals("BK-XYZ99999", booking.get("bookingId"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingStatus
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingStatus_returnsBookingIdInResponse() {
        when(bookingService.getBookingById("BK-001")).thenReturn(new HashMap<>());

        Map<String, Object> response = bookingController.getBookingStatus("BK-001");

        assertEquals("BK-001", response.get("bookingId"));
    }

    @Test
    void getBookingStatus_returnsDetailsInResponse() {
        Map<String, Object> details = new HashMap<>();
        details.put("guest", "Grace");
        when(bookingService.getBookingById("BK-002")).thenReturn(details);

        Map<String, Object> response = bookingController.getBookingStatus("BK-002");

        assertNotNull(response.get("details"));
    }

    @Test
    void getBookingStatus_delegatesToBookingService() {
        when(bookingService.getBookingById("BK-003")).thenReturn(new HashMap<>());

        bookingController.getBookingStatus("BK-003");

        verify(bookingService, times(1)).getBookingById("BK-003");
    }

    @Test
    void getBookingStatus_detailsMatchServiceResult() {
        Map<String, Object> details = new HashMap<>();
        details.put("guest", "Henry");
        details.put("room", "SUITE");
        when(bookingService.getBookingById("BK-004")).thenReturn(details);

        Map<String, Object> response = bookingController.getBookingStatus("BK-004");

        @SuppressWarnings("unchecked")
        Map<String, Object> returnedDetails = (Map<String, Object>) response.get("details");
        assertEquals("Henry", returnedDetails.get("guest"));
        assertEquals("SUITE", returnedDetails.get("room"));
    }

    @Test
    void getBookingStatus_responseContainsBothKeys() {
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        Map<String, Object> response = bookingController.getBookingStatus("BK-005");

        assertTrue(response.containsKey("bookingId"));
        assertTrue(response.containsKey("details"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAvailability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void checkAvailability_returnsRoomTypeInResponse() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        assertEquals("SUITE", response.get("roomType"));
    }

    @Test
    void checkAvailability_returnsAvailabilityFromService_whenTrue() {
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        assertEquals(true, response.get("available"));
    }

    @Test
    void checkAvailability_returnsAvailabilityFromService_whenFalse() {
        when(bookingService.isRoomAvailable("UNKNOWN")).thenReturn(false);

        Map<String, Object> response = bookingController.checkAvailability("UNKNOWN");

        assertEquals(false, response.get("available"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("VILLA");

        assertTrue(response.containsKey("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_delegatesToBookingService() {
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        bookingController.checkAvailability("STANDARD");

        verify(bookingService, times(1)).isRoomAvailable("STANDARD");
    }

    @Test
    void checkAvailability_responseContainsAllThreeKeys() {
        when(bookingService.isRoomAvailable(anyString())).thenReturn(false);

        Map<String, Object> response = bookingController.checkAvailability("BASIC");

        assertTrue(response.containsKey("roomType"));
        assertTrue(response.containsKey("inventoryEndpoint"));
        assertTrue(response.containsKey("available"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // downloadReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void downloadReport_returnsReportPathInResponse() {
        when(bookingService.generateReport("2024-03")).thenReturn("Report generated");

        Map<String, Object> response = bookingController.downloadReport("2024-03");

        assertNotNull(response.get("reportPath"));
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        when(bookingService.generateReport("2024-03")).thenReturn("Report generated");

        Map<String, Object> response = bookingController.downloadReport("2024-03");

        assertTrue(response.get("reportPath").toString().contains("2024-03"));
    }

    @Test
    void downloadReport_reportPathContainsPdfExtension() {
        when(bookingService.generateReport("2024-06")).thenReturn("Report generated");

        Map<String, Object> response = bookingController.downloadReport("2024-06");

        assertTrue(response.get("reportPath").toString().endsWith("_bookings.pdf"));
    }

    @Test
    void downloadReport_returnsMessageFromService() {
        when(bookingService.generateReport("2024-09")).thenReturn("Report triggered for 2024-09");

        Map<String, Object> response = bookingController.downloadReport("2024-09");

        assertEquals("Report triggered for 2024-09", response.get("message"));
    }

    @Test
    void downloadReport_delegatesToBookingService() {
        when(bookingService.generateReport("2024-12")).thenReturn("Done");

        bookingController.downloadReport("2024-12");

        verify(bookingService, times(1)).generateReport("2024-12");
    }

    @Test
    void downloadReport_responseContainsBothKeys() {
        when(bookingService.generateReport(anyString())).thenReturn("OK");

        Map<String, Object> response = bookingController.downloadReport("2024-01");

        assertTrue(response.containsKey("reportPath"));
        assertTrue(response.containsKey("message"));
    }
}
