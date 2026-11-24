package com.demo.resortslite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController controller;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
    }

    @Test
    void createBooking_returnsConfirmedAndStoresSession() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-ABC");
        when(bookingService.createBooking("Alice", "SUITE", "in", "out")).thenReturn(booking);

        Map<String, Object> resp = controller.createBooking("Alice", "SUITE", "in", "out", session);

        assertEquals("confirmed", resp.get("status"));
        assertSame(booking, resp.get("booking"));
        assertSame(booking, session.getAttribute("lastBooking"));
        assertEquals("Alice", session.getAttribute("guestName"));
        verify(bookingService).createBooking("Alice", "SUITE", "in", "out");
    }

    @Test
    void getBookingStatus_withSessionGuest() {
        session.setAttribute("guestName", "Bob");
        Map<String, Object> details = new HashMap<>();
        details.put("id", "BK-1");
        when(bookingService.getBookingById("BK-1")).thenReturn(details);

        Map<String, Object> resp = controller.getBookingStatus("BK-1", session);

        assertEquals("BK-1", resp.get("bookingId"));
        assertEquals("Bob", resp.get("sessionGuest"));
        assertSame(details, resp.get("details"));
    }

    @Test
    void getBookingStatus_withoutSessionGuest_isNull() {
        when(bookingService.getBookingById("BK-2")).thenReturn(new HashMap<>());
        Map<String, Object> resp = controller.getBookingStatus("BK-2", session);
        assertNull(resp.get("sessionGuest"));
        assertTrue(resp.containsKey("sessionGuest"));
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
        when(bookingService.isRoomAvailable("X")).thenReturn(false);
        Map<String, Object> resp = controller.checkAvailability("X");
        assertEquals(false, resp.get("available"));
    }

    @Test
    void downloadReport_returnsPathAndMessage() {
        when(bookingService.generateReport("MARCH")).thenReturn("done");
        Map<String, Object> resp = controller.downloadReport("MARCH");
        assertEquals("/var/legacy/reports/MARCH_bookings.pdf", resp.get("reportPath"));
        assertEquals("done", resp.get("message"));
    }
}
