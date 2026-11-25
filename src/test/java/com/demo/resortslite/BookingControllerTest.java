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
        booking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking("John", "SUITE", "a", "b")).thenReturn(booking);
        MockHttpSession session = new MockHttpSession();

        Map<String, Object> resp = controller.createBooking("John", "SUITE", "a", "b", session);

        assertEquals("confirmed", resp.get("status"));
        assertSame(booking, resp.get("booking"));
        assertSame(booking, session.getAttribute("lastBooking"));
        assertEquals("John", session.getAttribute("guestName"));
    }

    @Test
    void getBookingStatus_returnsDetailsAndSessionGuest() {
        Map<String, Object> details = Map.of("id", "BK-1");
        when(bookingService.getBookingById("BK-1")).thenReturn(details);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("guestName", "Jane");

        Map<String, Object> r = controller.getBookingStatus("BK-1", session);

        assertEquals("BK-1", r.get("bookingId"));
        assertEquals("Jane", r.get("sessionGuest"));
        assertEquals(details, r.get("details"));
    }

    @Test
    void getBookingStatus_noSessionGuest_isNull() {
        when(bookingService.getBookingById("BK-2")).thenReturn(Map.of());
        Map<String, Object> r = controller.getBookingStatus("BK-2", new MockHttpSession());
        assertNull(r.get("sessionGuest"));
    }

    @Test
    void checkAvailability_returnsAvailability() {
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);
        Map<String, Object> r = controller.checkAvailability("VILLA");
        assertEquals("VILLA", r.get("roomType"));
        assertEquals(true, r.get("available"));
        assertEquals("http://inventory-service.internal:8081/rooms/available", r.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_unavailable() {
        when(bookingService.isRoomAvailable("CABIN")).thenReturn(false);
        assertEquals(false, controller.checkAvailability("CABIN").get("available"));
    }

    @Test
    void downloadReport_returnsPathAndMessage() {
        when(bookingService.generateReport("MAR")).thenReturn("done");
        Map<String, Object> r = controller.downloadReport("MAR");
        assertEquals("/var/legacy/reports/MAR_bookings.pdf", r.get("reportPath"));
        assertEquals("done", r.get("message"));
    }
}
