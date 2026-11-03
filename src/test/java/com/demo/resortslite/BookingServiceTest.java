package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingServiceTest {

    private BookingService bookingService;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService();
        jdbcTemplate = mock(JdbcTemplate.class);
        TestUtils.setField(bookingService, "jdbcTemplate", jdbcTemplate);
        TestUtils.setField(bookingService, "databaseHost", "db-host");
        TestUtils.setField(bookingService, "paymentApi", "https://payments.test/charge");
    }

    @Test
    void run_executesCreateTableStatement() {
        bookingService.run();

        verify(jdbcTemplate).execute(anyString());
    }

    @Test
    void createBooking_returnsBookingDetailsAndPersistsRecord() {
        Map<String, Object> booking = bookingService.createBooking("Alice", "SUITE", "2024-03-01", "2024-03-05");

        assertNotNull(booking.get("bookingId"));
        assertEquals("Alice", booking.get("guestName"));
        assertEquals("SUITE", booking.get("roomType"));
        assertEquals("db-host", booking.get("dbHost"));
        assertEquals(64, booking.get("confirmationCode").toString().length());
        verify(jdbcTemplate).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void getBookingById_returnsDatabaseResultWhenFound() {
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-123"))).thenReturn(Map.of("id", "BK-123", "guest", "Alice"));

        Map<String, Object> result = bookingService.getBookingById("BK-123");

        assertEquals("BK-123", result.get("id"));
        assertEquals("Alice", result.get("guest"));
    }

    @Test
    void getBookingById_returnsErrorWhenLookupFails() {
        doThrow(new RuntimeException("missing")).when(jdbcTemplate).queryForMap(anyString(), eq("BK-404"));

        Map<String, Object> result = bookingService.getBookingById("BK-404");

        assertEquals("Booking not found: BK-404", result.get("error"));
    }

    @Test
    void calculateRoomPrice_appliesPeakSeasonLoyaltyAndStayDiscounts() {
        String total = bookingService.calculateRoomPrice("DELUXE", 7, "PEAK", "GOLD");

        assertEquals("1795.50", total);
    }

    @Test
    void calculateRoomPrice_usesDefaultRoomPriceForUnknownType() {
        String total = bookingService.calculateRoomPrice("UNKNOWN", 2, "REGULAR", "NONE");

        assertEquals("240.00", total);
    }

    @Test
    void calculateRoomPrice_handlesNullRoomTypeAndOffSeasonDiamondDiscount() {
        String total = bookingService.calculateRoomPrice(null, 14, "OFF", "DIAMOND");

        assertEquals("846.72", total);
    }

    @Test
    void isRoomAvailable_returnsTrueForSupportedRoomType() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_returnsFalseForUnsupportedRoomType() {
        assertFalse(bookingService.isRoomAvailable("CABIN"));
    }

    @Test
    void generateReport_includesMonthAndPaymentApi() {
        String message = bookingService.generateReport("March");

        assertEquals("Report generation triggered for: March via https://payments.test/charge", message);
    }
}
