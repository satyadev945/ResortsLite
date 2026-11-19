package com.demo.resortslite;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingControllerTest {

    private BookingController controller;
    private BookingService bookingService;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        controller = new BookingController();
        bookingService = mock(BookingService.class);
        session = mock(HttpSession.class);
        ReflectionTestUtils.setField(controller, "bookingService", bookingService);
    }

    @Test
    void createBooking_storesSessionAndReturnsConfirmed() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-123");
        when(bookingService.createBooking("John", "SUITE", "in", "out")).thenReturn(booking);

        Map<String, Object> response = controller.createBooking("John", "SUITE", "in", "out", session);

        assertEquals("confirmed", response.get("status"));
        assertSame(booking, response.get("booking"));
        verify(session).setAttribute("lastBooking", booking);
        verify(session).setAttribute("guestName", "John");
    }

    @Test
    void getBookingStatus_returnsSessionGuestAndDetails() {
        Map<String, Object> details = Map.of("id", "BK-1");
        when(session.getAttribute("guestName")).thenReturn("Jane");
        when(bookingService.getBookingById("BK-1")).thenReturn(details);

        Map<String, Object> result = controller.getBookingStatus("BK-1", session);

        assertEquals("BK-1", result.get("bookingId"));
        assertEquals("Jane", result.get("sessionGuest"));
        assertEquals(details, result.get("details"));
    }

    @Test
    void getBookingStatus_noSessionGuest_returnsNull() {
        when(session.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById("BK-2")).thenReturn(new HashMap<>());

        Map<String, Object> result = controller.getBookingStatus("BK-2", session);

        assertNull(result.get("sessionGuest"));
    }

    @Test
    void checkAvailability_available() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        Map<String, Object> result = controller.checkAvailability("SUITE");

        assertEquals("SUITE", result.get("roomType"));
        assertEquals(true, result.get("available"));
        assertNotNull(result.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_unavailable() {
        when(bookingService.isRoomAvailable("CABIN")).thenReturn(false);

        Map<String, Object> result = controller.checkAvailability("CABIN");

        assertEquals(false, result.get("available"));
    }

    @Test
    void downloadReport_returnsPathAndMessage() {
        when(bookingService.generateReport("MARCH")).thenReturn("done");

        Map<String, Object> result = controller.downloadReport("MARCH");

        assertEquals("/var/legacy/reports/MARCH_bookings.pdf", result.get("reportPath"));
        assertEquals("done", result.get("message"));
    }
}
