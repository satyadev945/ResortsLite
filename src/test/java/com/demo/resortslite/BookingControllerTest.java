package com.demo.resortslite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private HttpSession session;

    @InjectMocks
    private BookingController controller;

    @Test
    @SuppressWarnings("unchecked")
    void createBooking_storesSessionAndReturnsConfirmed() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-1234");
        when(bookingService.createBooking("Ann", "SUITE", "in", "out")).thenReturn(booking);

        Map<String, Object> resp = controller.createBooking("Ann", "SUITE", "in", "out", session);

        assertEquals("confirmed", resp.get("status"));
        assertSame(booking, resp.get("booking"));
        verify(session).setAttribute("lastBooking", booking);
        verify(session).setAttribute("guestName", "Ann");
    }

    @Test
    void getBookingStatus_returnsDetailsAndSessionGuest() {
        Map<String, Object> details = new HashMap<>();
        details.put("id", "BK-9");
        when(session.getAttribute("guestName")).thenReturn("Zed");
        when(bookingService.getBookingById("BK-9")).thenReturn(details);

        Map<String, Object> resp = controller.getBookingStatus("BK-9", session);

        assertEquals("BK-9", resp.get("bookingId"));
        assertEquals("Zed", resp.get("sessionGuest"));
        assertSame(details, resp.get("details"));
    }

    @Test
    void getBookingStatus_noSessionGuest_null() {
        when(session.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById("X")).thenReturn(new HashMap<>());
        Map<String, Object> resp = controller.getBookingStatus("X", session);
        assertNull(resp.get("sessionGuest"));
    }

    @Test
    void checkAvailability_available() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);
        Map<String, Object> resp = controller.checkAvailability("SUITE");
        assertEquals("SUITE", resp.get("roomType"));
        assertEquals(true, resp.get("available"));
        assertEquals("http://inventory-service.internal:8081/rooms/available", resp.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_unavailable() {
        when(bookingService.isRoomAvailable("CAVE")).thenReturn(false);
        assertEquals(false, controller.checkAvailability("CAVE").get("available"));
    }

    @Test
    void downloadReport_returnsPathAndMessage() {
        when(bookingService.generateReport("MAY")).thenReturn("done");
        Map<String, Object> resp = controller.downloadReport("MAY");
        assertEquals("/var/legacy/reports/MAY_bookings.pdf", resp.get("reportPath"));
        assertEquals("done", resp.get("message"));
    }
}
