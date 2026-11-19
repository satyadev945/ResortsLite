package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void createBooking_validInput_returnsBookingMap() {
        Map<String, Object> b = service.createBooking("John", "SUITE", "2024-01-01", "2024-01-05");
        assertNotNull(b);
        assertTrue(((String) b.get("bookingId")).startsWith("BK-"));
        assertEquals("John", b.get("guestName"));
        assertEquals("SUITE", b.get("roomType"));
        assertEquals("2024-01-01", b.get("checkIn"));
        assertEquals("2024-01-05", b.get("checkOut"));
        assertEquals(64, ((String) b.get("confirmationCode")).length());
        assertNotNull(b.get("dbHost"));
    }

    @Test
    void createBooking_executesInsertSql() {
        service.createBooking("Jane", "DELUXE", "a", "b");
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(cap.capture());
        assertTrue(cap.getValue().startsWith("INSERT INTO bookings"));
        assertTrue(cap.getValue().contains("Jane"));
    }

    @Test
    void createBooking_generatesUniqueIds() {
        Map<String, Object> a = service.createBooking("x", "SUITE", "a", "b");
        Map<String, Object> b = service.createBooking("x", "SUITE", "a", "b");
        assertNotEquals(a.get("bookingId"), b.get("bookingId"));
    }

    @Test
    void getBookingById_found_returnsRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "BK-1");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(row);
        assertEquals("BK-1", service.getBookingById("BK-1").get("id"));
    }

    @Test
    void getBookingById_exception_returnsError() {
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new RuntimeException("none"));
        Map<String, Object> r = service.getBookingById("BK-2");
        assertEquals("Booking not found: BK-2", r.get("error"));
    }

    @Test
    void calculateRoomPrice_standardNoDiscount() {
        assertEquals(String.format("%.2f", 120.0), service.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_allRoomTypes() {
        assertEquals(String.format("%.2f", 200.0), service.calculateRoomPrice("DELUXE", 1, "X", "X"));
        assertEquals(String.format("%.2f", 350.0), service.calculateRoomPrice("SUITE", 1, "X", "X"));
        assertEquals(String.format("%.2f", 600.0), service.calculateRoomPrice("VILLA", 1, "X", "X"));
        assertEquals(String.format("%.2f", 120.0), service.calculateRoomPrice("OTHER", 1, "X", "X"));
    }

    @Test
    void calculateRoomPrice_seasons() {
        assertEquals(String.format("%.2f", 180.0), service.calculateRoomPrice("STANDARD", 1, "PEAK", "X"));
        assertEquals(String.format("%.2f", 96.0), service.calculateRoomPrice("STANDARD", 1, "OFF", "X"));
    }

    @Test
    void calculateRoomPrice_loyalty() {
        assertEquals(String.format("%.2f", 108.0), service.calculateRoomPrice("STANDARD", 1, "X", "GOLD"));
        assertEquals(String.format("%.2f", 96.0), service.calculateRoomPrice("STANDARD", 1, "X", "PLATINUM"));
        assertEquals(String.format("%.2f", 84.0), service.calculateRoomPrice("STANDARD", 1, "X", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_longStayDiscount() {
        assertEquals(String.format("%.2f", 798.0), service.calculateRoomPrice("STANDARD", 7, "X", "X"));
        assertEquals(String.format("%.2f", 1596.0), service.calculateRoomPrice("STANDARD", 14, "X", "X"));
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
    void generateReport_containsMonth() {
        String r = service.generateReport("MARCH");
        assertTrue(r.contains("MARCH"));
        assertTrue(r.startsWith("Report generation triggered for: "));
    }
}
