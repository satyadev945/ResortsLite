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
    void createBooking_storesSessionAndReturnsConfirmed() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "id-1");
        when(bookingService.createBooking("Ann", "SUITE", "a", "b")).thenReturn(booking);

        Map<String, Object> response = controller.createBooking("Ann", "SUITE", "a", "b", session);

        assertEquals("confirmed", response.get("status"));
        assertSame(booking, response.get("booking"));
        verify(session).setAttribute("lastBooking", booking);
        verify(session).setAttribute("guestName", "Ann");
    }

    @Test
    void getBookingStatus_returnsSessionGuestAndDetails() {
        Map<String, Object> details = new HashMap<>();
        details.put("guest", "Bob");
        when(session.getAttribute("guestName")).thenReturn("Bob");
        when(bookingService.getBookingById("id-2")).thenReturn(details);

        Map<String, Object> result = controller.getBookingStatus("id-2", session);

        assertEquals("id-2", result.get("bookingId"));
        assertEquals("Bob", result.get("sessionGuest"));
        assertSame(details, result.get("details"));
    }

    @Test
    void getBookingStatus_noSessionGuest_returnsNull() {
        when(session.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById("id-3")).thenReturn(new HashMap<>());

        Map<String, Object> result = controller.getBookingStatus("id-3", session);

        assertNull(result.get("sessionGuest"));
        assertTrue(result.containsKey("sessionGuest"));
    }

    @Test
    void checkAvailability_available() {
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        Map<String, Object> result = controller.checkAvailability("VILLA");

        assertEquals("VILLA", result.get("roomType"));
        assertEquals(true, result.get("available"));
        assertEquals("http://inventory-service.internal:8081/rooms/available", result.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_unavailable() {
        when(bookingService.isRoomAvailable("X")).thenReturn(false);

        Map<String, Object> result = controller.checkAvailability("X");

        assertEquals(false, result.get("available"));
    }

    @Test
    void downloadReport_buildsPathAndMessage() {
        when(bookingService.generateReport("MAR")).thenReturn("done");

        Map<String, Object> result = controller.downloadReport("MAR");

        assertEquals("/var/legacy/reports/MAR_bookings.pdf", result.get("reportPath"));
        assertEquals("done", result.get("message"));
    }
}
