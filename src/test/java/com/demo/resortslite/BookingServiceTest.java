package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    private BookingService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        service = new BookingService();
        jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
    }

    @Test
    void createBooking_validInput_returnsBookingMap() {
        Map<String, Object> b = service.createBooking("Alice", "SUITE", "2024-01-01", "2024-01-05");
        assertTrue(((String) b.get("bookingId")).startsWith("BK-"));
        assertEquals("Alice", b.get("guestName"));
        assertEquals("SUITE", b.get("roomType"));
        assertEquals("2024-01-01", b.get("checkIn"));
        assertEquals("2024-01-05", b.get("checkOut"));
        assertEquals("db-prod.resorts-internal.com", b.get("dbHost"));
        assertEquals(64, ((String) b.get("confirmationCode")).length());
    }

    @Test
    void createBooking_executesInsertSql() {
        service.createBooking("Bob", "DELUXE", "a", "b");
        ArgumentCaptor<String> c = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(c.capture());
        assertTrue(c.getValue().startsWith("INSERT INTO bookings"));
        assertTrue(c.getValue().contains("Bob"));
    }

    @Test
    void createBooking_generatesUniqueIds() {
        String a = (String) service.createBooking("A", "SUITE", "x", "y").get("bookingId");
        String b = (String) service.createBooking("A", "SUITE", "x", "y").get("bookingId");
        assertNotEquals(a, b);
    }

    @Test
    void getBookingById_found_returnsRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "BK-1");
        when(jdbc.queryForMap(anyString())).thenReturn(row);
        assertEquals(row, service.getBookingById("BK-1"));
    }

    @Test
    void getBookingById_notFound_returnsError() {
        when(jdbc.queryForMap(anyString())).thenThrow(new RuntimeException("none"));
        Map<String, Object> r = service.getBookingById("BK-X");
        assertEquals("Booking not found: BK-X", r.get("error"));
    }

    @Test
    void calculateRoomPrice_standardNormal() {
        assertEquals("120.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_deluxe() {
        assertEquals("400.00", service.calculateRoomPrice("DELUXE", 2, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_suite() {
        assertEquals("350.00", service.calculateRoomPrice("SUITE", 1, "X", "X"));
    }

    @Test
    void calculateRoomPrice_villa() {
        assertEquals("600.00", service.calculateRoomPrice("VILLA", 1, "X", "X"));
    }

    @Test
    void calculateRoomPrice_unknownRoom_defaultsToStandard() {
        assertEquals("120.00", service.calculateRoomPrice("OTHER", 1, "X", "X"));
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
        assertEquals("108.00", service.calculateRoomPrice("STANDARD", 1, "X", "GOLD"));
    }

    @Test
    void calculateRoomPrice_platinumLoyalty() {
        assertEquals("96.00", service.calculateRoomPrice("STANDARD", 1, "X", "PLATINUM"));
    }

    @Test
    void calculateRoomPrice_diamondLoyalty() {
        assertEquals("84.00", service.calculateRoomPrice("STANDARD", 1, "X", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_sevenNightsDiscount() {
        assertEquals("798.00", service.calculateRoomPrice("STANDARD", 7, "X", "X"));
    }

    @Test
    void calculateRoomPrice_fourteenNights_stillSevenNightBranch() {
        assertEquals("1596.00", service.calculateRoomPrice("STANDARD", 14, "X", "X"));
    }

    @Test
    void calculateRoomPrice_zeroNights_isZero() {
        assertEquals("0.00", service.calculateRoomPrice("STANDARD", 0, "X", "X"));
    }

    @Test
    void calculateRoomPrice_nullRoomType_throwsNpe() {
        assertThrows(NullPointerException.class, () -> service.calculateRoomPrice(null, 1, "X", "X"));
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
    void generateReport_containsMonthAndEndpoint() {
        String r = service.generateReport("JAN");
        assertTrue(r.contains("JAN"));
        assertTrue(r.contains("payments/charge"));
    }

    @Test
    void hashConfirmation_isDeterministicSha256() throws Exception {
        String h1 = ReflectionTestUtils.invokeMethod(service, "hashConfirmation", "abc");
        String h2 = ReflectionTestUtils.invokeMethod(service, "hashConfirmation", "abc");
        assertEquals(h1, h2);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h1);
    }

    @Test
    void hashConfirmation_nullInput_returnsInputOnError() {
        String h = ReflectionTestUtils.invokeMethod(service, "hashConfirmation", (Object) null);
        assertNull(h);
    }
}
