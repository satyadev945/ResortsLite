package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
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
        jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void createBooking_returnsBookingWithExpectedFields() {
        Map<String, Object> b = service.createBooking("Alice", "SUITE", "2024-01-01", "2024-01-05");

        assertNotNull(b);
        assertTrue(((String) b.get("bookingId")).startsWith("BK-"));
        assertEquals(11, ((String) b.get("bookingId")).length());
        assertEquals("Alice", b.get("guestName"));
        assertEquals("SUITE", b.get("roomType"));
        assertEquals("2024-01-01", b.get("checkIn"));
        assertEquals("2024-01-05", b.get("checkOut"));
        assertEquals("db-prod.resorts-internal.com", b.get("dbHost"));
        assertEquals(64, ((String) b.get("confirmationCode")).length());
    }

    @Test
    void createBooking_insertsIntoDatabase() {
        Map<String, Object> b = service.createBooking("Bob", "DELUXE", "a", "b");

        verify(jdbcTemplate).update(anyString(), eq(b.get("bookingId")), eq("Bob"), eq("DELUXE"), eq("a"), eq("b"));
    }

    @Test
    void createBooking_generatesUniqueIds() {
        Map<String, Object> b1 = service.createBooking("A", "SUITE", "x", "y");
        Map<String, Object> b2 = service.createBooking("A", "SUITE", "x", "y");
        assertNotEquals(b1.get("bookingId"), b2.get("bookingId"));
    }

    @Test
    void getBookingById_found_returnsRow() {
        Map<String, Object> row = Map.of("id", "BK-1");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(row);

        Map<String, Object> result = service.getBookingById("BK-1");

        assertEquals(row, result);
    }

    @Test
    void getBookingById_exception_returnsError() {
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new RuntimeException("none"));

        Map<String, Object> result = service.getBookingById("BK-X");

        assertEquals("Booking not found: BK-X", result.get("error"));
    }

    @Test
    void calculateRoomPrice_standardNoDiscount() {
        assertEquals("240.00", service.calculateRoomPrice("STANDARD", 2, "REGULAR", "NONE"));
    }

    @Test
    void calculateRoomPrice_deluxe() {
        assertEquals("200.00", service.calculateRoomPrice("DELUXE", 1, "REGULAR", "NONE"));
    }

    @Test
    void calculateRoomPrice_suite() {
        assertEquals("350.00", service.calculateRoomPrice("SUITE", 1, "REGULAR", "NONE"));
    }

    @Test
    void calculateRoomPrice_villa() {
        assertEquals("600.00", service.calculateRoomPrice("VILLA", 1, "REGULAR", "NONE"));
    }

    @Test
    void calculateRoomPrice_unknownRoomDefaultsToStandard() {
        assertEquals("120.00", service.calculateRoomPrice("OTHER", 1, "REGULAR", "NONE"));
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
        assertEquals("108.00", service.calculateRoomPrice("STANDARD", 1, "REGULAR", "GOLD"));
    }

    @Test
    void calculateRoomPrice_platinumLoyalty() {
        assertEquals("96.00", service.calculateRoomPrice("STANDARD", 1, "REGULAR", "PLATINUM"));
    }

    @Test
    void calculateRoomPrice_diamondLoyalty() {
        assertEquals("84.00", service.calculateRoomPrice("STANDARD", 1, "REGULAR", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_longStayDiscount() {
        // 120 * 0.95 * 7 = 798.00
        assertEquals("798.00", service.calculateRoomPrice("STANDARD", 7, "REGULAR", "NONE"));
    }

    @Test
    void calculateRoomPrice_veryLongStayUsesFirstBranch() {
        // nights >= 7 branch is matched first: 120 * 0.95 * 14 = 1596.00
        assertEquals("1596.00", service.calculateRoomPrice("STANDARD", 14, "REGULAR", "NONE"));
    }

    @Test
    void calculateRoomPrice_zeroNights() {
        assertEquals("0.00", service.calculateRoomPrice("STANDARD", 0, "REGULAR", "NONE"));
    }

    @Test
    void calculateRoomPrice_nullArgument_throws() {
        assertThrows(NullPointerException.class,
                () -> service.calculateRoomPrice(null, 1, "PEAK", "GOLD"));
    }

    @Test
    void isRoomAvailable_validTypes_returnTrue() {
        assertTrue(service.isRoomAvailable("STANDARD"));
        assertTrue(service.isRoomAvailable("DELUXE"));
        assertTrue(service.isRoomAvailable("SUITE"));
        assertTrue(service.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_invalidType_returnsFalse() {
        assertFalse(service.isRoomAvailable("PENTHOUSE"));
        assertFalse(service.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_null_throws() {
        assertThrows(NullPointerException.class, () -> service.isRoomAvailable(null));
    }

    @Test
    void generateReport_containsMonth() {
        String msg = service.generateReport("March");
        assertTrue(msg.contains("March"));
        assertTrue(msg.startsWith("Report generation triggered for: "));
    }

    @Test
    void sha256Hash_isDeterministicAndHex() {
        String h1 = ReflectionTestUtils.invokeMethod(service, "sha256Hash", "abc");
        String h2 = ReflectionTestUtils.invokeMethod(service, "sha256Hash", "abc");
        assertEquals(h1, h2);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h1);
    }
}
