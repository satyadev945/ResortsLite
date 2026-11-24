package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController controller;

    @Test
    void createBooking_storesSessionAndReturnsConfirmed() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-123");
        when(bookingService.createBooking("Ann", "SUITE", "d1", "d2")).thenReturn(booking);
        MockHttpSession session = new MockHttpSession();

        Map<String, Object> response = controller.createBooking("Ann", "SUITE", "d1", "d2", session);

        assertEquals("confirmed", response.get("status"));
        assertSame(booking, response.get("booking"));
        assertSame(booking, session.getAttribute("lastBooking"));
        assertEquals("Ann", session.getAttribute("guestName"));
    }

    @Test
    void getBookingStatus_withSessionGuest_returnsDetails() {
        Map<String, Object> details = Map.of("id", "BK-1");
        when(bookingService.getBookingById("BK-1")).thenReturn(details);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("guestName", "Zed");

        Map<String, Object> result = controller.getBookingStatus("BK-1", session);

        assertEquals("BK-1", result.get("bookingId"));
        assertEquals("Zed", result.get("sessionGuest"));
        assertEquals(details, result.get("details"));
    }

    @Test
    void getBookingStatus_noSessionGuest_returnsNullGuest() {
        when(bookingService.getBookingById("BK-2")).thenReturn(Map.of());

        Map<String, Object> result = controller.getBookingStatus("BK-2", new MockHttpSession());

        assertNull(result.get("sessionGuest"));
        assertTrue(result.containsKey("sessionGuest"));
    }

    @Test
    void checkAvailability_available() {
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        Map<String, Object> result = controller.checkAvailability("VILLA");

        assertEquals("VILLA", result.get("roomType"));
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
        when(bookingService.generateReport("JAN")).thenReturn("done");

        Map<String, Object> result = controller.downloadReport("JAN");

        assertEquals("/var/legacy/reports/JAN_bookings.pdf", result.get("reportPath"));
        assertEquals("done", result.get("message"));
    }
}
