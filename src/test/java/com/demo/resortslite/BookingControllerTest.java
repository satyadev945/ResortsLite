package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BookingController.
 * Covers createBooking, getBookingStatus, checkAvailability, and downloadReport endpoints.
 */
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
    }

    // ─── createBooking ─────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidInputs_returnsNonNullResponse() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "John Smith");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        Map<String, Object> response = bookingController.createBooking(
                "John Smith", "SUITE", "2024-06-01", "2024-06-05", session);

        assertNotNull(response);
    }

    @Test
    void createBooking_withValidInputs_returnsConfirmedStatus() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        Map<String, Object> response = bookingController.createBooking(
                "John Smith", "SUITE", "2024-06-01", "2024-06-05", session);

        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidInputs_returnsBookingInResponse() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Jane Doe");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Jane Doe", "DELUXE", "2024-07-01", "2024-07-03", session);

        assertNotNull(response.get("booking"));
        assertEquals(mockBooking, response.get("booking"));
    }

    @Test
    void createBooking_storesLastBookingInSession() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-TEST001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        bookingController.createBooking(
                "Alice", "STANDARD", "2024-08-01", "2024-08-02", session);

        assertNotNull(session.getAttribute("lastBooking"));
    }

    @Test
    void createBooking_storesGuestNameInSession() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-TEST002");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        bookingController.createBooking(
                "Bob", "VILLA", "2024-09-01", "2024-09-05", session);

        assertEquals("Bob", session.getAttribute("guestName"));
    }

    @Test
    void createBooking_callsBookingServiceCreateBooking() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-TEST003");
        when(bookingService.createBooking("Carol", "SUITE", "2024-10-01", "2024-10-04"))
                .thenReturn(mockBooking);

        bookingController.createBooking(
                "Carol", "SUITE", "2024-10-01", "2024-10-04", session);

        verify(bookingService, times(1))
                .createBooking("Carol", "SUITE", "2024-10-01", "2024-10-04");
    }

    @Test
    void createBooking_addsBookingToCache() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-CACHE01");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Dave", "DELUXE", "2024-11-01", "2024-11-03", session);

        // Verify response contains booking
        assertNotNull(response.get("booking"));
    }

    // ─── getBookingStatus ──────────────────────────────────────────────────────

    @Test
    void getBookingStatus_withValidBookingId_returnsNonNullResponse() {
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-ABCD1234");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(mockDetails);

        Map<String, Object> response = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertNotNull(response);
    }

    @Test
    void getBookingStatus_withValidBookingId_returnsBookingIdInResponse() {
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-ABCD1234");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(mockDetails);

        Map<String, Object> response = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertEquals("BK-ABCD1234", response.get("bookingId"));
    }

    @Test
    void getBookingStatus_withSessionGuest_returnsSessionGuestInResponse() {
        session.setAttribute("guestName", "Eve");
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        Map<String, Object> response = bookingController.getBookingStatus("BK-TEST", session);

        assertEquals("Eve", response.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_withNoSessionGuest_returnsNullSessionGuest() {
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        Map<String, Object> response = bookingController.getBookingStatus("BK-TEST", session);

        assertNull(response.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_returnsDetailsFromService() {
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-DETAIL");
        mockDetails.put("guest", "Frank");
        when(bookingService.getBookingById("BK-DETAIL")).thenReturn(mockDetails);

        Map<String, Object> response = bookingController.getBookingStatus("BK-DETAIL", session);

        assertEquals(mockDetails, response.get("details"));
    }

    @Test
    void getBookingStatus_callsBookingServiceGetBookingById() {
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById("BK-VERIFY")).thenReturn(mockDetails);

        bookingController.getBookingStatus("BK-VERIFY", session);

        verify(bookingService, times(1)).getBookingById("BK-VERIFY");
    }

    // ─── checkAvailability ─────────────────────────────────────────────────────

    @Test
    void checkAvailability_withValidRoomType_returnsNonNullResponse() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        assertNotNull(response);
    }

    @Test
    void checkAvailability_withAvailableRoom_returnsTrue() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        assertTrue((Boolean) response.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsFalse() {
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        assertFalse((Boolean) response.get("available"));
    }

    @Test
    void checkAvailability_returnsRoomTypeInResponse() {
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        assertEquals("DELUXE", response.get("roomType"));
    }

    @Test
    void checkAvailability_returnsInventoryEndpointInResponse() {
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        assertNotNull(response.get("inventoryEndpoint"));
        assertTrue(response.get("inventoryEndpoint").toString().contains("inventory-service.internal"));
    }

    @Test
    void checkAvailability_callsBookingServiceIsRoomAvailable() {
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        bookingController.checkAvailability("VILLA");

        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    // ─── downloadReport ────────────────────────────────────────────────────────

    @Test
    void downloadReport_withValidMonth_returnsNonNullResponse() {
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        Map<String, Object> response = bookingController.downloadReport("March");

        assertNotNull(response);
    }

    @Test
    void downloadReport_withValidMonth_returnsReportPathInResponse() {
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        Map<String, Object> response = bookingController.downloadReport("March");

        assertNotNull(response.get("reportPath"));
        assertTrue(response.get("reportPath").toString().contains("March"));
    }

    @Test
    void downloadReport_withValidMonth_returnsMessageFromService() {
        when(bookingService.generateReport("April")).thenReturn("Report generated for April");

        Map<String, Object> response = bookingController.downloadReport("April");

        assertEquals("Report generated for April", response.get("message"));
    }

    @Test
    void downloadReport_reportPathContainsPdfExtension() {
        when(bookingService.generateReport("May")).thenReturn("Report generated");

        Map<String, Object> response = bookingController.downloadReport("May");

        assertTrue(response.get("reportPath").toString().endsWith("_bookings.pdf"));
    }

    @Test
    void downloadReport_reportPathContainsLegacyBasePath() {
        when(bookingService.generateReport("June")).thenReturn("Report generated");

        Map<String, Object> response = bookingController.downloadReport("June");

        assertTrue(response.get("reportPath").toString().startsWith("/var/legacy/reports/"));
    }

    @Test
    void downloadReport_callsBookingServiceGenerateReport() {
        when(bookingService.generateReport("July")).thenReturn("Report generated");

        bookingController.downloadReport("July");

        verify(bookingService, times(1)).generateReport("July");
    }
}
