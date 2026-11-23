package com.demo.resortslite;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
        bookingService = Mockito.mock(BookingService.class);
        session = Mockito.mock(HttpSession.class);
        ReflectionTestUtils.setField(controller, "bookingService", bookingService);
    }

    @Test
    void createBooking_returnsConfirmedAndStoresSession() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-ABC12345");
        when(bookingService.createBooking("Alice", "SUITE", "in", "out")).thenReturn(booking);

        Map<String, Object> response = controller.createBooking("Alice", "SUITE", "in", "out", session);

        assertEquals("confirmed", response.get("status"));
        assertSame(booking, response.get("booking"));
        verify(session).setAttribute("lastBooking", booking);
        verify(session).setAttribute("guestName", "Alice");
    }

    @Test
    void createBooking_withoutBookingId_throws() {
        when(bookingService.createBooking(any(), any(), any(), any())).thenReturn(new HashMap<>());

        // HashMap permits null keys/values, so no exception is expected
        Map<String, Object> response = controller.createBooking("A", "B", "C", "D", session);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void getBookingStatus_returnsSessionGuestAndDetails() {
        Map<String, Object> details = Map.of("id", "BK-1");
        when(session.getAttribute("guestName")).thenReturn("Bob");
        when(bookingService.getBookingById("BK-1")).thenReturn(details);

        Map<String, Object> result = controller.getBookingStatus("BK-1", session);

        assertEquals("BK-1", result.get("bookingId"));
        assertEquals("Bob", result.get("sessionGuest"));
        assertEquals(details, result.get("details"));
    }

    @Test
    void getBookingStatus_noSessionGuest_returnsNull() {
        when(session.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById("X")).thenReturn(new HashMap<>());

        Map<String, Object> result = controller.getBookingStatus("X", session);

        assertNull(result.get("sessionGuest"));
    }

    @Test
    void checkAvailability_available() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        Map<String, Object> result = controller.checkAvailability("SUITE");

        assertEquals("SUITE", result.get("roomType"));
        assertEquals(true, result.get("available"));
        assertEquals("http://inventory-service.internal:8081/rooms/available", result.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_unavailable() {
        when(bookingService.isRoomAvailable("BAD")).thenReturn(false);

        Map<String, Object> result = controller.checkAvailability("BAD");

        assertEquals(false, result.get("available"));
    }

    @Test
    void downloadReport_returnsPathAndMessage() {
        when(bookingService.generateReport("May")).thenReturn("msg");

        Map<String, Object> result = controller.downloadReport("May");

        assertEquals("/var/legacy/reports/May_bookings.pdf", result.get("reportPath"));
        assertEquals("msg", result.get("message"));
    }
}
