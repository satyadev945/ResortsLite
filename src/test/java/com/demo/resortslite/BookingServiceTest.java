package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
        Map<String, Object> result = bookingService.createBooking("Alice", "SUITE", "2024-01-01", "2024-01-05");

        assertNotNull(result);
        assertTrue(((String) result.get("bookingId")).startsWith("BK-"));
        assertEquals("Alice", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-01-01", result.get("checkIn"));
        assertEquals("2024-01-05", result.get("checkOut"));
        assertEquals("db-prod.resorts-internal.com", result.get("dbHost"));
        assertEquals(64, ((String) result.get("confirmationCode")).length());
    }

    @Test
    void createBooking_insertsRowViaJdbc() {
        Map<String, Object> result = bookingService.createBooking("Bob", "DELUXE", "a", "b");

        verify(jdbcTemplate).update(anyString(), eq(result.get("bookingId")), eq("Bob"), eq("DELUXE"), eq("a"), eq("b"));
    }

    @Test
    void createBooking_generatesUniqueIds() {
        Map<String, Object> first = bookingService.createBooking("A", "SUITE", "x", "y");
        Map<String, Object> second = bookingService.createBooking("A", "SUITE", "x", "y");

        assertNotEquals(first.get("bookingId"), second.get("bookingId"));
    }

    @Test
    void getBookingById_found_returnsRow() {
        when(jdbcTemplate.queryForMap(anyString(), any(Object.class))).thenReturn(Map.of("id", "BK-1"));

        Map<String, Object> result = bookingService.getBookingById("BK-1");

        assertEquals("BK-1", result.get("id"));
    }

    @Test
    void getBookingById_exception_returnsError() {
        when(jdbcTemplate.queryForMap(anyString(), any(Object.class))).thenThrow(new RuntimeException("none"));

        Map<String, Object> result = bookingService.getBookingById("BK-X");

        assertEquals("Booking not found: BK-X", result.get("error"));
    }

    @Test
    void calculateRoomPrice_standardNoModifiers() {
        assertEquals("120.00", bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_deluxe() {
        assertEquals("400.00", bookingService.calculateRoomPrice("DELUXE", 2, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_suite() {
        assertEquals("350.00", bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_villa() {
        assertEquals("600.00", bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_unknownRoomDefaultsToStandard() {
        assertEquals("120.00", bookingService.calculateRoomPrice("OTHER", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_peakSeason() {
        assertEquals("180.00", bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE"));
    }

    @Test
    void calculateRoomPrice_offSeason() {
        assertEquals("96.00", bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE"));
    }

    @Test
    void calculateRoomPrice_goldLoyalty() {
        assertEquals("108.00", bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD"));
    }

    @Test
    void calculateRoomPrice_platinumLoyalty() {
        assertEquals("96.00", bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM"));
    }

    @Test
    void calculateRoomPrice_diamondLoyalty() {
        assertEquals("84.00", bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_longStayDiscount() {
        assertEquals("798.00", bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_veryLongStayStillGetsWeekDiscount() {
        assertEquals("1596.00", bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_zeroNights() {
        assertEquals("0.00", bookingService.calculateRoomPrice("SUITE", 0, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_nullRoomType_throwsNpe() {
        assertThrows(NullPointerException.class, () -> bookingService.calculateRoomPrice(null, 1, "PEAK", "GOLD"));
    }

    @Test
    void isRoomAvailable_validTypes_true() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
        assertTrue(bookingService.isRoomAvailable("SUITE"));
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_invalidType_false() {
        assertFalse(bookingService.isRoomAvailable("CABIN"));
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_null_throwsNpe() {
        assertThrows(NullPointerException.class, () -> bookingService.isRoomAvailable(null));
    }

    @Test
    void generateReport_containsMonthAndApi() {
        String msg = bookingService.generateReport("MARCH");

        assertTrue(msg.contains("MARCH"));
        assertTrue(msg.contains("http://10.0.1.45:9090/payments/charge"));
    }
}
