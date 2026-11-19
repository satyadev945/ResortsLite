package com.demo.resortslite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        assertNotNull(bookingService);
    }

    @Test
    void createBooking_validInput_returnsBookingMap() {
        Map<String, Object> b = bookingService.createBooking("Alice", "SUITE", "2024-01-01", "2024-01-05");
        assertEquals("Alice", b.get("guestName"));
        assertEquals("SUITE", b.get("roomType"));
        assertEquals("2024-01-01", b.get("checkIn"));
        assertEquals("2024-01-05", b.get("checkOut"));
        assertEquals("db-prod.resorts-internal.com", b.get("dbHost"));
        assertTrue(((String) b.get("bookingId")).startsWith("BK-"));
        assertEquals(11, ((String) b.get("bookingId")).length());
    }

    @Test
    void createBooking_executesInsertSql() {
        bookingService.createBooking("Bob", "DELUXE", "2024-02-01", "2024-02-03");
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(cap.capture());
        assertTrue(cap.getValue().startsWith("INSERT INTO bookings"));
        assertTrue(cap.getValue().contains("'Bob'"));
        assertTrue(cap.getValue().contains("'DELUXE'"));
    }

    @Test
    void createBooking_confirmationCodeIsSha256Hex() {
        Map<String, Object> b = bookingService.createBooking("Carol", "VILLA", "a", "b");
        String code = (String) b.get("confirmationCode");
        assertNotNull(code);
        assertEquals(64, code.length());
        assertTrue(code.matches("[0-9a-f]+"));
    }

    @Test
    void createBooking_uniqueIds() {
        Map<String, Object> a = bookingService.createBooking("A", "SUITE", "x", "y");
        Map<String, Object> b = bookingService.createBooking("A", "SUITE", "x", "y");
        assertNotEquals(a.get("bookingId"), b.get("bookingId"));
    }

    @Test
    void getBookingById_found_returnsRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "BK-1");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(row);
        Map<String, Object> result = bookingService.getBookingById("BK-1");
        assertEquals("BK-1", result.get("id"));
    }

    @Test
    void getBookingById_exception_returnsError() {
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new RuntimeException("none"));
        Map<String, Object> result = bookingService.getBookingById("BK-X");
        assertEquals("Booking not found: BK-X", result.get("error"));
    }

    @Test
    void calculateRoomPrice_standardNoModifiers() {
        assertEquals("120.00", bookingService.calculateRoomPrice("STANDARD", 1, "REGULAR", "NONE"));
    }

    @Test
    void calculateRoomPrice_roomTypes() {
        assertEquals("200.00", bookingService.calculateRoomPrice("DELUXE", 1, "X", "X"));
        assertEquals("350.00", bookingService.calculateRoomPrice("SUITE", 1, "X", "X"));
        assertEquals("600.00", bookingService.calculateRoomPrice("VILLA", 1, "X", "X"));
        assertEquals("120.00", bookingService.calculateRoomPrice("UNKNOWN", 1, "X", "X"));
    }

    @Test
    void calculateRoomPrice_seasons() {
        assertEquals("180.00", bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "X"));
        assertEquals("96.00", bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "X"));
    }

    @Test
    void calculateRoomPrice_loyalty() {
        assertEquals("108.00", bookingService.calculateRoomPrice("STANDARD", 1, "X", "GOLD"));
        assertEquals("96.00", bookingService.calculateRoomPrice("STANDARD", 1, "X", "PLATINUM"));
        assertEquals("84.00", bookingService.calculateRoomPrice("STANDARD", 1, "X", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_nightsDiscount() {
        assertEquals("798.00", bookingService.calculateRoomPrice("STANDARD", 7, "X", "X"));
        assertEquals("1596.00", bookingService.calculateRoomPrice("STANDARD", 14, "X", "X"));
        assertEquals("720.00", bookingService.calculateRoomPrice("STANDARD", 6, "X", "X"));
    }

    @Test
    void calculateRoomPrice_zeroNights() {
        assertEquals("0.00", bookingService.calculateRoomPrice("SUITE", 0, "PEAK", "GOLD"));
    }

    @Test
    void calculateRoomPrice_nullRoomType_throws() {
        assertThrows(NullPointerException.class, () -> bookingService.calculateRoomPrice(null, 1, "X", "X"));
    }

    @Test
    void isRoomAvailable_validTypes_true() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
        assertTrue(bookingService.isRoomAvailable("SUITE"));
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_invalidTypes_false() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
        assertFalse(bookingService.isRoomAvailable(""));
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    @Test
    void generateReport_containsMonthAndApi() {
        String r = bookingService.generateReport("MARCH");
        assertTrue(r.contains("MARCH"));
        assertTrue(r.contains("http://10.0.1.45:9090/payments/charge"));
    }
}
