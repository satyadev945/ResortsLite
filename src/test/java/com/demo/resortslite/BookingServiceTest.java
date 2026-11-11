package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService service;

    @Test
    void createBooking_insertsAndReturnsBooking() {
        Map<String, Object> b = service.createBooking("John", "SUITE", "2024-01-01", "2024-01-05");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sql.capture());
        assertTrue(sql.getValue().startsWith("INSERT INTO bookings"));
        assertTrue(sql.getValue().contains("John"));

        assertTrue(((String) b.get("bookingId")).startsWith("BK-"));
        assertEquals(11, ((String) b.get("bookingId")).length());
        assertEquals("John", b.get("guestName"));
        assertEquals("SUITE", b.get("roomType"));
        assertEquals("2024-01-01", b.get("checkIn"));
        assertEquals("2024-01-05", b.get("checkOut"));
        assertEquals("db-prod.resorts-internal.com", b.get("dbHost"));
        assertEquals(64, ((String) b.get("confirmationCode")).length());
    }

    @Test
    void createBooking_generatesUniqueIds() {
        Map<String, Object> a = service.createBooking("A", "SUITE", "x", "y");
        Map<String, Object> b = service.createBooking("A", "SUITE", "x", "y");
        assertNotEquals(a.get("bookingId"), b.get("bookingId"));
    }

    @Test
    void getBookingById_found() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "BK-1");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(row);
        Map<String, Object> r = service.getBookingById("BK-1");
        assertEquals("BK-1", r.get("id"));
    }

    @Test
    void getBookingById_exception_returnsError() {
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new RuntimeException("none"));
        Map<String, Object> r = service.getBookingById("BK-9");
        assertEquals("Booking not found: BK-9", r.get("error"));
    }

    @Test
    void calculateRoomPrice_roomTypes() {
        assertEquals("120.00", service.calculateRoomPrice("STANDARD", 1, "REGULAR", "NONE"));
        assertEquals("200.00", service.calculateRoomPrice("DELUXE", 1, "REGULAR", "NONE"));
        assertEquals("350.00", service.calculateRoomPrice("SUITE", 1, "REGULAR", "NONE"));
        assertEquals("600.00", service.calculateRoomPrice("VILLA", 1, "REGULAR", "NONE"));
        assertEquals("120.00", service.calculateRoomPrice("OTHER", 1, "REGULAR", "NONE"));
    }

    @Test
    void calculateRoomPrice_seasons() {
        assertEquals("300.00", service.calculateRoomPrice("DELUXE", 1, "PEAK", "NONE"));
        assertEquals("160.00", service.calculateRoomPrice("DELUXE", 1, "OFF", "NONE"));
    }

    @Test
    void calculateRoomPrice_loyalty() {
        assertEquals("180.00", service.calculateRoomPrice("DELUXE", 1, "X", "GOLD"));
        assertEquals("160.00", service.calculateRoomPrice("DELUXE", 1, "X", "PLATINUM"));
        assertEquals("140.00", service.calculateRoomPrice("DELUXE", 1, "X", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_nightsDiscount() {
        assertEquals("400.00", service.calculateRoomPrice("DELUXE", 2, "X", "NONE"));
        assertEquals("1330.00", service.calculateRoomPrice("DELUXE", 7, "X", "NONE"));
        assertEquals("2660.00", service.calculateRoomPrice("DELUXE", 14, "X", "NONE"));
    }

    @Test
    void isRoomAvailable_validTypes() {
        assertTrue(service.isRoomAvailable("STANDARD"));
        assertTrue(service.isRoomAvailable("DELUXE"));
        assertTrue(service.isRoomAvailable("SUITE"));
        assertTrue(service.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_invalidType() {
        assertFalse(service.isRoomAvailable("PENTHOUSE"));
        assertFalse(service.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_null_throwsNpe() {
        assertThrows(NullPointerException.class, () -> service.isRoomAvailable(null));
    }

    @Test
    void generateReport_containsMonth() {
        String r = service.generateReport("MARCH");
        assertTrue(r.startsWith("Report generation triggered for: MARCH"));
        assertTrue(r.contains("http://10.0.1.45:9090/payments/charge"));
    }
}
