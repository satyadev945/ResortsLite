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
    private BookingService service;

    @BeforeEach
    void setUp() {
        assertNotNull(service);
    }

    @Test
    void createBooking_returnsPopulatedMap() {
        Map<String, Object> b = service.createBooking("Alice", "SUITE", "2024-01-01", "2024-01-05");
        assertTrue(((String) b.get("bookingId")).startsWith("BK-"));
        assertEquals(11, ((String) b.get("bookingId")).length());
        assertEquals("Alice", b.get("guestName"));
        assertEquals("SUITE", b.get("roomType"));
        assertEquals("2024-01-01", b.get("checkIn"));
        assertEquals("2024-01-05", b.get("checkOut"));
        assertEquals("db-prod.resorts-internal.com", b.get("dbHost"));
        assertEquals(32, ((String) b.get("confirmationCode")).length());
    }

    @Test
    void createBooking_executesInsertSql() {
        service.createBooking("Bob", "DELUXE", "a", "b");
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(cap.capture());
        assertTrue(cap.getValue().startsWith("INSERT INTO bookings"));
        assertTrue(cap.getValue().contains("'Bob'"));
        assertTrue(cap.getValue().contains("'DELUXE'"));
    }

    @Test
    void createBooking_generatesUniqueIds() {
        Map<String, Object> a = service.createBooking("A", "SUITE", "x", "y");
        Map<String, Object> b = service.createBooking("A", "SUITE", "x", "y");
        assertNotEquals(a.get("bookingId"), b.get("bookingId"));
    }

    @Test
    void createBooking_confirmationCodeIsMd5Hex() {
        Map<String, Object> b = service.createBooking("A", "SUITE", "x", "y");
        assertTrue(((String) b.get("confirmationCode")).matches("[0-9a-f]{32}"));
    }

    @Test
    void createBooking_nullGuestStillWorks() {
        Map<String, Object> b = service.createBooking(null, "SUITE", "x", "y");
        assertNull(b.get("guestName"));
        assertNotNull(b.get("confirmationCode"));
    }

    @Test
    void getBookingById_found_returnsRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "BK-1");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(row);
        Map<String, Object> result = service.getBookingById("BK-1");
        assertEquals("BK-1", result.get("id"));
    }

    @Test
    void getBookingById_exception_returnsError() {
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new RuntimeException("nope"));
        Map<String, Object> result = service.getBookingById("BK-X");
        assertEquals("Booking not found: BK-X", result.get("error"));
    }

    @Test
    void calculateRoomPrice_standardDefault() {
        assertEquals("120.00", service.calculateRoomPrice("STANDARD", 1, "REG", "NONE"));
    }

    @Test
    void calculateRoomPrice_deluxe() {
        assertEquals("200.00", service.calculateRoomPrice("DELUXE", 1, "REG", "NONE"));
    }

    @Test
    void calculateRoomPrice_suitePeak() {
        assertEquals("1050.00", service.calculateRoomPrice("SUITE", 2, "PEAK", "NONE"));
    }

    @Test
    void calculateRoomPrice_deluxeOffGold() {
        assertEquals("144.00", service.calculateRoomPrice("DELUXE", 1, "OFF", "GOLD"));
    }

    @Test
    void calculateRoomPrice_villaPlatinum() {
        assertEquals("480.00", service.calculateRoomPrice("VILLA", 1, "REG", "PLATINUM"));
    }

    @Test
    void calculateRoomPrice_standardDiamond() {
        assertEquals("84.00", service.calculateRoomPrice("STANDARD", 1, "REG", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_unknownRoomUsesDefault() {
        assertEquals("120.00", service.calculateRoomPrice("UNKNOWN", 1, "REG", "NONE"));
    }

    @Test
    void calculateRoomPrice_sevenNightsDiscount() {
        assertEquals("798.00", service.calculateRoomPrice("STANDARD", 7, "REG", "NONE"));
    }

    @Test
    void calculateRoomPrice_fourteenNightsStillFivePercent() {
        assertEquals("1596.00", service.calculateRoomPrice("STANDARD", 14, "REG", "NONE"));
    }

    @Test
    void calculateRoomPrice_zeroNights() {
        assertEquals("0.00", service.calculateRoomPrice("STANDARD", 0, "REG", "NONE"));
    }

    @Test
    void calculateRoomPrice_nullRoomType_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> service.calculateRoomPrice(null, 1, "REG", "NONE"));
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
        assertFalse(service.isRoomAvailable("PENTHOUSE"));
        assertFalse(service.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_null_throwsNpe() {
        assertThrows(NullPointerException.class, () -> service.isRoomAvailable(null));
    }

    @Test
    void generateReport_containsMonthAndEndpoint() {
        String r = service.generateReport("MARCH");
        assertTrue(r.startsWith("Report generation triggered for: MARCH via "));
        assertTrue(r.contains("10.0.1.45"));
    }
}
