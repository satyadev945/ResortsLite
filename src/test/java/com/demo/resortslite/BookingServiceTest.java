package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    private BookingService service;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        service = new BookingService();
        jdbcTemplate = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void createBooking_validInput_returnsBookingMap() {
        Map<String, Object> booking = service.createBooking("John", "SUITE", "2024-01-01", "2024-01-05");

        assertNotNull(booking);
        assertEquals("John", booking.get("guestName"));
        assertEquals("SUITE", booking.get("roomType"));
        assertEquals("2024-01-01", booking.get("checkIn"));
        assertEquals("2024-01-05", booking.get("checkOut"));
        assertEquals("db-prod.resorts-internal.com", booking.get("dbHost"));
        assertTrue(((String) booking.get("bookingId")).startsWith("BK-"));
        assertEquals(11, ((String) booking.get("bookingId")).length());
        assertEquals(64, ((String) booking.get("confirmationCode")).length());
    }

    @Test
    void createBooking_executesInsertSql() {
        service.createBooking("Jane", "DELUXE", "a", "b");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(captor.capture());
        assertTrue(captor.getValue().startsWith("INSERT INTO bookings"));
        assertTrue(captor.getValue().contains("Jane"));
        assertTrue(captor.getValue().contains("DELUXE"));
    }

    @Test
    void createBooking_generatesUniqueIds() {
        Map<String, Object> a = service.createBooking("A", "SUITE", "x", "y");
        Map<String, Object> b = service.createBooking("A", "SUITE", "x", "y");
        assertNotEquals(a.get("bookingId"), b.get("bookingId"));
    }

    @Test
    void getBookingById_found_returnsRow() {
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(Map.of("id", "BK-1"));

        Map<String, Object> result = service.getBookingById("BK-1");

        assertEquals("BK-1", result.get("id"));
    }

    @Test
    void getBookingById_exception_returnsError() {
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new RuntimeException("none"));

        Map<String, Object> result = service.getBookingById("BK-X");

        assertEquals("Booking not found: BK-X", result.get("error"));
    }

    @Test
    void calculateRoomPrice_standardNoDiscount() {
        assertEquals("120.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_deluxe() {
        assertEquals("200.00", service.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_suite() {
        assertEquals("350.00", service.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_villa() {
        assertEquals("600.00", service.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_unknownRoomDefaultsToStandard() {
        assertEquals("120.00", service.calculateRoomPrice("OTHER", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_peakSeason() {
        assertEquals("180.00", service.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE"));
    }

    @Test
    void calculateRoomPrice_offSeason() {
        assertEquals("96.00", service.calculateRoomPrice("STANDARD", 1, "OFF", "NONE"));
    }

    @Test
    void calculateRoomPrice_goldLoyalty() {
        assertEquals("108.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD"));
    }

    @Test
    void calculateRoomPrice_platinumLoyalty() {
        assertEquals("96.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM"));
    }

    @Test
    void calculateRoomPrice_diamondLoyalty() {
        assertEquals("84.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_sevenNightsDiscount() {
        assertEquals("798.00", service.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_fourteenNightsDiscount() {
        assertEquals("1512.00", service.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_zeroNights() {
        assertEquals("0.00", service.calculateRoomPrice("STANDARD", 0, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_nullRoomType_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> service.calculateRoomPrice(null, 1, "NORMAL", "NONE"));
    }

    @Test
    void isRoomAvailable_validTypes_true() {
        assertTrue(service.isRoomAvailable("STANDARD"));
        assertTrue(service.isRoomAvailable("DELUXE"));
        assertTrue(service.isRoomAvailable("SUITE"));
        assertTrue(service.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_invalidType_false() {
        assertFalse(service.isRoomAvailable("CABIN"));
        assertFalse(service.isRoomAvailable(""));
    }

    @Test
    void generateReport_containsMonthAndApi() {
        String msg = service.generateReport("MARCH");
        assertTrue(msg.contains("MARCH"));
        assertTrue(msg.contains("10.0.1.45"));
    }

    @Test
    void md5Hash_isDeterministicSha256() {
        String h1 = ReflectionTestUtils.invokeMethod(service, "md5Hash", "abc");
        String h2 = ReflectionTestUtils.invokeMethod(service, "md5Hash", "abc");
        assertEquals(h1, h2);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h1);
    }
}
