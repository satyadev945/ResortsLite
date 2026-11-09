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
 * Unit tests for {@link BookingController}.
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

    // ── createBooking ────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidInputs_returnsNonNullResponse() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Alice");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        assertNotNull(response);
    }

    @Test
    void createBooking_withValidInputs_returnsConfirmedStatus() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidInputs_returnsBookingInResponse() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Bob");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        assertNotNull(response.get("booking"));
        assertEquals(mockBooking, response.get("booking"));
    }

    @Test
    void createBooking_storesLastBookingInSession() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        bookingController.createBooking("Carol", "STANDARD", "2024-08-01", "2024-08-03", session);

        assertNotNull(session.getAttribute("lastBooking"));
    }

    @Test
    void createBooking_storesGuestNameInSession() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        bookingController.createBooking("Dave", "VILLA", "2024-09-01", "2024-09-05", session);

        assertEquals("Dave", session.getAttribute("guestName"));
    }

    @Test
    void createBooking_callsBookingServiceCreateBooking() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-03"))
                .thenReturn(mockBooking);

        bookingController.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-03", session);

        verify(bookingService, times(1))
                .createBooking("Eve", "SUITE", "2024-10-01", "2024-10-03");
    }

    @Test
    void createBooking_responseContainsTwoKeys() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Frank", "STANDARD", "2024-11-01", "2024-11-02", session);

        assertEquals(2, response.size());
        assertTrue(response.containsKey("status"));
        assertTrue(response.containsKey("booking"));
    }

    // ── getBookingStatus ─────────────────────────────────────────────────────

    @Test
    void getBookingStatus_withValidBookingId_returnsNonNullResponse() {
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-ABCD1234");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(mockDetails);

        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertNotNull(result);
    }

    @Test
    void getBookingStatus_withValidBookingId_returnsBookingIdInResponse() {
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(mockDetails);

        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertEquals("BK-ABCD1234", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_withSessionGuest_returnsSessionGuestInResponse() {
        session.setAttribute("guestName", "Alice");
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(mockDetails);

        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertEquals("Alice", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_withNoSessionGuest_returnsNullSessionGuest() {
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(mockDetails);

        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_returnsDetailsFromService() {
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("guest", "Bob");
        mockDetails.put("room", "SUITE");
        when(bookingService.getBookingById("BK-XYZ")).thenReturn(mockDetails);

        Map<String, Object> result = bookingController.getBookingStatus("BK-XYZ", session);

        assertEquals(mockDetails, result.get("details"));
    }

    @Test
    void getBookingStatus_callsBookingServiceGetBookingById() {
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById("BK-TEST")).thenReturn(mockDetails);

        bookingController.getBookingStatus("BK-TEST", session);

        verify(bookingService, times(1)).getBookingById("BK-TEST");
    }

    // ── checkAvailability ────────────────────────────────────────────────────

    @Test
    void checkAvailability_withValidRoomType_returnsNonNullResponse() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        assertNotNull(result);
    }

    @Test
    void checkAvailability_withAvailableRoom_returnsTrueAvailability() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        assertTrue((Boolean) result.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsFalseAvailability() {
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        assertFalse((Boolean) result.get("available"));
    }

    @Test
    void checkAvailability_returnsRoomTypeInResponse() {
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        assertEquals("DELUXE", result.get("roomType"));
    }

    @Test
    void checkAvailability_returnsInventoryEndpointInResponse() {
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        assertNotNull(result.get("inventoryEndpoint"));
        assertTrue(result.get("inventoryEndpoint").toString().contains("inventory-service.internal"));
    }

    @Test
    void checkAvailability_callsBookingServiceIsRoomAvailable() {
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        bookingController.checkAvailability("VILLA");

        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    // ── downloadReport ───────────────────────────────────────────────────────

    @Test
    void downloadReport_withValidMonth_returnsNonNullResponse() {
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        Map<String, Object> result = bookingController.downloadReport("March");

        assertNotNull(result);
    }

    @Test
    void downloadReport_withValidMonth_returnsReportPathInResponse() {
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        Map<String, Object> result = bookingController.downloadReport("March");

        assertNotNull(result.get("reportPath"));
        assertTrue(result.get("reportPath").toString().contains("March"));
    }

    @Test
    void downloadReport_withValidMonth_returnsMessageInResponse() {
        when(bookingService.generateReport("April")).thenReturn("Report generated for April");

        Map<String, Object> result = bookingController.downloadReport("April");

        assertEquals("Report generated for April", result.get("message"));
    }

    @Test
    void downloadReport_reportPathContainsLegacyPath() {
        when(bookingService.generateReport("May")).thenReturn("Report generated");

        Map<String, Object> result = bookingController.downloadReport("May");

        assertTrue(result.get("reportPath").toString().contains("/var/legacy/reports/"));
    }

    @Test
    void downloadReport_reportPathContainsPdfExtension() {
        when(bookingService.generateReport("June")).thenReturn("Report generated");

        Map<String, Object> result = bookingController.downloadReport("June");

        assertTrue(result.get("reportPath").toString().endsWith("_bookings.pdf"));
    }

    @Test
    void downloadReport_callsBookingServiceGenerateReport() {
        when(bookingService.generateReport("July")).thenReturn("Report generated");

        bookingController.downloadReport("July");

        verify(bookingService, times(1)).generateReport("July");
    }
}
