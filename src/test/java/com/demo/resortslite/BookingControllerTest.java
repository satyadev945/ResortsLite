package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    private BookingController controller;

    @BeforeEach
    void setUp() {
        controller = new BookingController();
        ReflectionTestUtils.setField(controller, "bookingService", bookingService);
    }

    @Test
    void createBooking_storesInSessionAndReturnsConfirmed() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-123");
        when(bookingService.createBooking("Ann", "SUITE", "in", "out")).thenReturn(booking);
        MockHttpSession session = new MockHttpSession();

        Map<String, Object> r = controller.createBooking("Ann", "SUITE", "in", "out", session);

        assertEquals("confirmed", r.get("status"));
        assertSame(booking, r.get("booking"));
        assertSame(booking, session.getAttribute("lastBooking"));
        assertEquals("Ann", session.getAttribute("guestName"));
    }

    @Test
    void getBookingStatus_withSession_returnsGuest() {
        Map<String, Object> details = new HashMap<>();
        details.put("id", "BK-1");
        when(bookingService.getBookingById("BK-1")).thenReturn(details);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("guestName", "Bob");

        Map<String, Object> r = controller.getBookingStatus("BK-1", session);

        assertEquals("BK-1", r.get("bookingId"));
        assertEquals("Bob", r.get("sessionGuest"));
        assertSame(details, r.get("details"));
    }

    @Test
    void getBookingStatus_noSession_guestNull() {
        when(bookingService.getBookingById("X")).thenReturn(new HashMap<>());
        Map<String, Object> r = controller.getBookingStatus("X", new MockHttpSession());
        assertNull(r.get("sessionGuest"));
    }

    @Test
    void checkAvailability_returnsAvailabilityAndEndpoint() {
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);
        Map<String, Object> r = controller.checkAvailability("VILLA");
        assertEquals("VILLA", r.get("roomType"));
        assertEquals(true, r.get("available"));
        assertNotNull(r.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_unavailable() {
        when(bookingService.isRoomAvailable("CABIN")).thenReturn(false);
        assertEquals(false, controller.checkAvailability("CABIN").get("available"));
    }

    @Test
    void downloadReport_buildsPathAndMessage() {
        when(bookingService.generateReport("MARCH")).thenReturn("done");
        Map<String, Object> r = controller.downloadReport("MARCH");
        assertEquals("/var/legacy/reports/MARCH_bookings.pdf", r.get("reportPath"));
        assertEquals("done", r.get("message"));
    }
}
