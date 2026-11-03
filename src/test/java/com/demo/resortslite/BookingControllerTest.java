package com.demo.resortslite;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingControllerTest {

    private BookingController bookingController;
    private BookingService bookingService;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        bookingController = new BookingController();
        bookingService = mock(BookingService.class);
        session = mock(HttpSession.class);
        TestUtils.setField(bookingController, "bookingService", bookingService);
        TestUtils.setField(bookingController, "reportsBasePath", "reports-base");
        TestUtils.setField(bookingController, "inventoryEndpoint", "https://inventory.test/available");
    }

    @Test
    void createBooking_returnsConfirmedResponseAndStoresSessionAttributes() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-100");
        booking.put("guestName", "Alice");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString())).thenReturn(booking);

        Map<String, Object> response = bookingController.createBooking("Alice", "SUITE", "2024-03-01", "2024-03-05", session);

        assertEquals("confirmed", response.get("status"));
        assertEquals(booking, response.get("booking"));
        verify(session).setAttribute("lastBooking", booking);
        verify(session).setAttribute("guestName", "Alice");
    }

    @Test
    void getBookingStatus_returnsSessionGuestAndBookingDetails() {
        when(session.getAttribute("guestName")).thenReturn("Alice");
        when(bookingService.getBookingById("BK-100")).thenReturn(Map.of("id", "BK-100"));

        Map<String, Object> response = bookingController.getBookingStatus("BK-100", session);

        assertEquals("BK-100", response.get("bookingId"));
        assertEquals("Alice", response.get("sessionGuest"));
        assertEquals(Map.of("id", "BK-100"), response.get("details"));
    }

    @Test
    void checkAvailability_returnsInventoryEndpointAndAvailability() {
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        assertEquals("DELUXE", response.get("roomType"));
        assertEquals("https://inventory.test/available", response.get("inventoryEndpoint"));
        assertEquals(true, response.get("available"));
    }

    @Test
    void downloadReport_returnsReportPathAndServiceMessage() {
        when(bookingService.generateReport("march")).thenReturn("generated");

        Map<String, Object> response = bookingController.downloadReport("march");

        assertEquals("reports-base/march_bookings.pdf", response.get("reportPath"));
        assertEquals("generated", response.get("message"));
    }
}
