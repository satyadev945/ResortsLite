package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingControllerTest {

    private BookingController controller;
    private BookingService service;

    @BeforeEach
    void setUp() {
        controller = new BookingController();
        service = mock(BookingService.class);
        ReflectionTestUtils.setField(controller, "bookingService", service);
    }

    @Test
    void createBooking_storesSessionAndReturnsConfirmed() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-1");
        when(service.createBooking("Al", "SUITE", "a", "b")).thenReturn(booking);
        MockHttpSession session = new MockHttpSession();

        Map<String, Object> r = controller.createBooking("Al", "SUITE", "a", "b", session);

        assertEquals("confirmed", r.get("status"));
        assertSame(booking, r.get("booking"));
        assertSame(booking, session.getAttribute("lastBooking"));
        assertEquals("Al", session.getAttribute("guestName"));
    }

    @Test
    void getBookingStatus_returnsSessionGuestAndDetails() {
        Map<String, Object> details = Map.of("id", "BK-1");
        when(service.getBookingById("BK-1")).thenReturn(details);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("guestName", "Zed");

        Map<String, Object> r = controller.getBookingStatus("BK-1", session);

        assertEquals("BK-1", r.get("bookingId"));
        assertEquals("Zed", r.get("sessionGuest"));
        assertEquals(details, r.get("details"));
    }

    @Test
    void getBookingStatus_noSession_guestNull() {
        when(service.getBookingById("X")).thenReturn(new HashMap<>());
        Map<String, Object> r = controller.getBookingStatus("X", new MockHttpSession());
        assertNull(r.get("sessionGuest"));
    }

    @Test
    void checkAvailability_available() {
        when(service.isRoomAvailable("SUITE")).thenReturn(true);
        Map<String, Object> r = controller.checkAvailability("SUITE");
        assertEquals("SUITE", r.get("roomType"));
        assertEquals(true, r.get("available"));
        assertNotNull(r.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_unavailable() {
        when(service.isRoomAvailable("CABIN")).thenReturn(false);
        assertEquals(false, controller.checkAvailability("CABIN").get("available"));
    }

    @Test
    void downloadReport_buildsPathAndMessage() {
        when(service.generateReport("JAN")).thenReturn("msg");
        Map<String, Object> r = controller.downloadReport("JAN");
        assertEquals("/var/legacy/reports/JAN_bookings.pdf", r.get("reportPath"));
        assertEquals("msg", r.get("message"));
    }
}
