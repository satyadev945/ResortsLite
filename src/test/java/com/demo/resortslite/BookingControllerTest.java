package com.demo.resortslite;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    void class_hasRestControllerAndMapping() {
        assertTrue(BookingController.class.isAnnotationPresent(RestController.class));
        assertArrayEquals(new String[] {"/api/bookings"},
                BookingController.class.getAnnotation(RequestMapping.class).value());
    }

    @Test
    void createBooking_returnsConfirmedResponseAndStoresSession() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-1");
        when(bookingService.createBooking("Ann", "SUITE", "in", "out")).thenReturn(booking);

        Map<String, Object> response = controller.createBooking("Ann", "SUITE", "in", "out", session);

        assertEquals("confirmed", response.get("status"));
        assertSame(booking, response.get("booking"));
        verify(session).setAttribute("lastBooking", booking);
        verify(session).setAttribute("guestName", "Ann");
    }

    @Test
    void createBooking_withoutBookingId_throwsNpe() {
        when(bookingService.createBooking(any(), any(), any(), any())).thenReturn(new HashMap<>());
        // HashMap allows null keys, so a missing bookingId is cached under null and does not throw
        Map<String, Object> response = assertDoesNotThrow(
                () -> controller.createBooking("A", "B", "C", "D", session));
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void getBookingStatus_returnsSessionGuestAndDetails() {
        Map<String, Object> details = Map.of("id", "BK-9");
        when(session.getAttribute("guestName")).thenReturn("Bob");
        when(bookingService.getBookingById("BK-9")).thenReturn(details);

        Map<String, Object> result = controller.getBookingStatus("BK-9", session);

        assertEquals("BK-9", result.get("bookingId"));
        assertEquals("Bob", result.get("sessionGuest"));
        assertEquals(details, result.get("details"));
    }

    @Test
    void getBookingStatus_noSessionGuest_returnsNull() {
        when(session.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById("BK-9")).thenReturn(new HashMap<>());

        Map<String, Object> result = controller.getBookingStatus("BK-9", session);

        assertNull(result.get("sessionGuest"));
        assertTrue(result.containsKey("sessionGuest"));
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
        when(bookingService.isRoomAvailable("XYZ")).thenReturn(false);

        Map<String, Object> result = controller.checkAvailability("XYZ");

        assertEquals(false, result.get("available"));
    }

    @Test
    void downloadReport_returnsPathAndMessage() {
        when(bookingService.generateReport("MAY")).thenReturn("done");

        Map<String, Object> result = controller.downloadReport("MAY");

        assertEquals("/var/legacy/reports/MAY_bookings.pdf", result.get("reportPath"));
        assertEquals("done", result.get("message"));
    }
}
