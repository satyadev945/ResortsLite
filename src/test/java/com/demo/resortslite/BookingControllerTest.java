package com.demo.resortslite;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private HttpSession session;

    @InjectMocks
    private BookingController controller;

    @Test
    void createBooking_storesInSessionAndReturnsConfirmed() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-ABC12345");
        when(bookingService.createBooking("Bob", "SUITE", "d1", "d2")).thenReturn(booking);

        Map<String, Object> resp = controller.createBooking("Bob", "SUITE", "d1", "d2", session);

        assertEquals("confirmed", resp.get("status"));
        assertSame(booking, resp.get("booking"));
        verify(session).setAttribute("lastBooking", booking);
        verify(session).setAttribute("guestName", "Bob");
    }

    @Test
    void getBookingStatus_returnsSessionGuestAndDetails() {
        Map<String, Object> details = new HashMap<>();
        details.put("id", "BK-1");
        when(session.getAttribute("guestName")).thenReturn("Bob");
        when(bookingService.getBookingById("BK-1")).thenReturn(details);

        Map<String, Object> resp = controller.getBookingStatus("BK-1", session);

        assertEquals("BK-1", resp.get("bookingId"));
        assertEquals("Bob", resp.get("sessionGuest"));
        assertSame(details, resp.get("details"));
    }

    @Test
    void getBookingStatus_noSessionGuest_returnsNullGuest() {
        when(session.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById("X")).thenReturn(new HashMap<>());

        Map<String, Object> resp = controller.getBookingStatus("X", session);

        assertNull(resp.get("sessionGuest"));
        assertTrue(resp.containsKey("sessionGuest"));
    }

    @Test
    void checkAvailability_available() {
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        Map<String, Object> resp = controller.checkAvailability("VILLA");

        assertEquals("VILLA", resp.get("roomType"));
        assertEquals(true, resp.get("available"));
        assertEquals("http://inventory-service.internal:8081/rooms/available", resp.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_unavailable() {
        when(bookingService.isRoomAvailable("CABIN")).thenReturn(false);

        Map<String, Object> resp = controller.checkAvailability("CABIN");

        assertEquals(false, resp.get("available"));
    }

    @Test
    void downloadReport_returnsPathAndMessage() {
        when(bookingService.generateReport("MAR")).thenReturn("msg");

        Map<String, Object> resp = controller.downloadReport("MAR");

        assertEquals("/var/legacy/reports/MAR_bookings.pdf", resp.get("reportPath"));
        assertEquals("msg", resp.get("message"));
    }
}
