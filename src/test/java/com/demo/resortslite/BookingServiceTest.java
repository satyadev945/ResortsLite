package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @SuppressWarnings("deprecation")
    private void stubUuid(UUID uuid) {
        when(jdbcTemplate.queryForObject(anyString(), any(Object[].class), any(int[].class), eq(UUID.class)))
                .thenReturn(uuid);
    }

    // ---- createBooking ----

    @Test
    void createBooking_returnsPopulatedBooking() {
        stubUuid(UUID.fromString("abcdef12-0000-0000-0000-000000000000"));

        Map<String, Object> b = service.createBooking("Alice", "SUITE", "2024-01-01", "2024-01-05");

        assertEquals("BK-ABCDEF12", b.get("bookingId"));
        assertEquals("Alice", b.get("guestName"));
        assertEquals("SUITE", b.get("roomType"));
        assertEquals("2024-01-01", b.get("checkIn"));
        assertEquals("2024-01-05", b.get("checkOut"));
        assertEquals("db-prod.resorts-internal.com", b.get("dbHost"));
        assertNotNull(b.get("confirmationCode"));
    }

    @Test
    void createBooking_executesInsertSql() {
        stubUuid(UUID.fromString("12345678-0000-0000-0000-000000000000"));

        service.createBooking("Bob", "DELUXE", "2024-02-01", "2024-02-03");

        verify(jdbcTemplate).execute("INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ("
                + "'BK-12345678', 'Bob', 'DELUXE', '2024-02-01', '2024-02-03')");
    }

    @Test
    void createBooking_confirmationCodeIsMd5HexOfIdAndGuest() throws Exception {
        stubUuid(UUID.fromString("12345678-0000-0000-0000-000000000000"));

        Map<String, Object> b = service.createBooking("Bob", "DELUXE", "a", "b");

        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest("BK-12345678Bob".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            sb.append(String.format("%02x", x));
        }
        assertEquals(sb.toString(), b.get("confirmationCode"));
        assertEquals(32, ((String) b.get("confirmationCode")).length());
    }

    @Test
    void createBooking_whenUuidNull_throwsNullPointerException() {
        stubUuid(null);
        assertThrows(NullPointerException.class,
                () -> service.createBooking("Eve", "VILLA", "a", "b"));
    }

    // ---- getBookingById ----

    @Test
    void getBookingById_returnsRowWhenFound() {
        Map<String, Object> row = Map.of("id", "BK-1", "guest", "Zed");
        when(jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = 'BK-1'")).thenReturn(row);

        Map<String, Object> result = service.getBookingById("BK-1");

        assertEquals(row, result);
    }

    @Test
    void getBookingById_returnsErrorWhenException() {
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new RuntimeException("none"));

        Map<String, Object> result = service.getBookingById("BK-X");

        assertEquals("Booking not found: BK-X", result.get("error"));
    }

    // ---- calculateRoomPrice ----

    @Test
    void calculateRoomPrice_standardBase() {
        assertEquals("240.00", service.calculateRoomPrice("STANDARD", 2, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_deluxeBase() {
        assertEquals("200.00", service.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_suiteBase() {
        assertEquals("350.00", service.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_villaBase() {
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
    void calculateRoomPrice_sevenNightsGetsFivePercentDiscount() {
        assertEquals("1330.00", service.calculateRoomPrice("DELUXE", 7, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_sixNightsNoDiscount() {
        assertEquals("1200.00", service.calculateRoomPrice("DELUXE", 6, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_fourteenNightsStillFivePercent() {
        // the >= 14 branch is unreachable because >= 7 is evaluated first
        assertEquals("2660.00", service.calculateRoomPrice("DELUXE", 14, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_zeroNights() {
        assertEquals("0.00", service.calculateRoomPrice("SUITE", 0, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_nullRoomType_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> service.calculateRoomPrice(null, 1, "NORMAL", "NONE"));
    }

    // ---- isRoomAvailable ----

    @Test
    void isRoomAvailable_validTypes() {
        assertTrue(service.isRoomAvailable("STANDARD"));
        assertTrue(service.isRoomAvailable("DELUXE"));
        assertTrue(service.isRoomAvailable("SUITE"));
        assertTrue(service.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_invalidTypes() {
        assertFalse(service.isRoomAvailable("PENTHOUSE"));
        assertFalse(service.isRoomAvailable(""));
        assertFalse(service.isRoomAvailable("standard"));
    }

    @Test
    void isRoomAvailable_null_throwsNpe() {
        assertThrows(NullPointerException.class, () -> service.isRoomAvailable(null));
    }

    // ---- generateReport ----

    @Test
    void generateReport_containsMonthAndApi() {
        String r = service.generateReport("MARCH");
        assertEquals("Report generation triggered for: MARCH via http://10.0.1.45:9090/payments/charge", r);
    }

    @Test
    void generateReport_nullMonth() {
        assertTrue(service.generateReport(null).startsWith("Report generation triggered for: null"));
    }
}
