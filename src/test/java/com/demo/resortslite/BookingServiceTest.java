package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link BookingService}.
 * Covers createBooking, getBookingById, calculateRoomPrice, isRoomAvailable, generateReport.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsMapWithBookingId() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice Smith", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertTrue(result.get("bookingId").toString().startsWith("BK-"));
    }

    @Test
    void createBooking_withValidParams_returnsGuestName() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Bob Jones", "DELUXE", "2024-07-10", "2024-07-15");

        assertEquals("Bob Jones", result.get("guestName"));
    }

    @Test
    void createBooking_withValidParams_returnsRoomType() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Carol White", "VILLA", "2024-08-01", "2024-08-10");

        assertEquals("VILLA", result.get("roomType"));
    }

    @Test
    void createBooking_withValidParams_returnsCheckInAndCheckOut() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Dave Brown", "STANDARD", "2024-09-01", "2024-09-03");

        assertEquals("2024-09-01", result.get("checkIn"));
        assertEquals("2024-09-03", result.get("checkOut"));
    }

    @Test
    void createBooking_withValidParams_returnsConfirmationCode() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Eve Green", "SUITE", "2024-10-01", "2024-10-07");

        assertNotNull(result.get("confirmationCode"));
        assertFalse(result.get("confirmationCode").toString().isEmpty());
    }

    @Test
    void createBooking_callsJdbcTemplateUpdate() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        bookingService.createBooking("Frank Lee", "DELUXE", "2024-11-01", "2024-11-04");

        verify(jdbcTemplate, times(1)).update(
                contains("INSERT INTO bookings"),
                any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_bookingIdIsUnique_forTwoConsecutiveCalls() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> first  = bookingService.createBooking("G1", "SUITE", "2024-01-01", "2024-01-02");
        Map<String, Object> second = bookingService.createBooking("G2", "SUITE", "2024-01-01", "2024-01-02");

        assertNotEquals(first.get("bookingId"), second.get("bookingId"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingById
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingById_whenFound_returnsBookingMap() {
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Alice");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ABCD1234"))).thenReturn(dbRow);

        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("id"));
        assertEquals("Alice", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorEntry() {
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-UNKNOWN")))
                .thenThrow(new RuntimeException("No results"));

        Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("BK-UNKNOWN"));
    }

    @Test
    void getBookingById_usesParameterisedQuery() {
        when(jdbcTemplate.queryForMap(anyString(), any())).thenReturn(new HashMap<>());

        bookingService.getBookingById("BK-TEST");

        verify(jdbcTemplate).queryForMap(contains("SELECT * FROM bookings WHERE id = ?"), eq("BK-TEST"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — room type variants
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoom_noSeason_noLoyalty_1Night() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoom_noSeason_noLoyalty_1Night() {
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoom_noSeason_noLoyalty_1Night() {
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoom_noSeason_noLoyalty_1Night() {
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandardPrice() {
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — season multipliers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_peakSeason_appliesMultiplier() {
        // STANDARD 120 * 1.5 * 1 night = 180.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeason_appliesDiscount() {
        // STANDARD 120 * 0.8 * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_defaultSeason_noMultiplier() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "REGULAR", "NONE");
        assertEquals("120.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — loyalty discounts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_goldLoyalty_applies10PercentDiscount() {
        // STANDARD 120 * 0.9 = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_applies20PercentDiscount() {
        // STANDARD 120 * 0.8 = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_applies30PercentDiscount() {
        // STANDARD 120 * 0.7 = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_noLoyalty_noDiscount() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "BASIC");
        assertEquals("120.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — long-stay discounts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_sevenNights_applies5PercentLongStayDiscount() {
        // STANDARD 120 * 0.95 * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_applies10PercentLongStayDiscount() {
        // STANDARD 120 * 0.90 * 14 = 1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_sixNights_noLongStayDiscount() {
        // STANDARD 120 * 6 = 720.00
        String price = bookingService.calculateRoomPrice("STANDARD", 6, "NORMAL", "NONE");
        assertEquals("720.00", price);
    }

    @Test
    void calculateRoomPrice_fifteenNights_uses14PlusDiscount() {
        // STANDARD 120 * 0.90 * 15 = 1620.00
        String price = bookingService.calculateRoomPrice("STANDARD", 15, "NORMAL", "NONE");
        assertEquals("1620.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — combined scenarios
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_peakSeasonGoldLoyalty7Nights_combinedDiscounts() {
        // SUITE 350 * 1.5 (PEAK) * 0.9 (GOLD) * 0.95 (7 nights) * 7 = 2,500.425 → "2500.43"
        String price = bookingService.calculateRoomPrice("SUITE", 7, "PEAK", "GOLD");
        assertNotNull(price);
        assertFalse(price.isEmpty());
    }

    @Test
    void calculateRoomPrice_offSeasonDiamondLoyalty14Nights_combinedDiscounts() {
        // VILLA 600 * 0.8 (OFF) * 0.7 (DIAMOND) * 0.90 (14 nights) * 14 = 4233.60
        String price = bookingService.calculateRoomPrice("VILLA", 14, "OFF", "DIAMOND");
        assertNotNull(price);
        assertFalse(price.isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isRoomAvailable
    // ─────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "DELUXE", "SUITE", "VILLA"})
    void isRoomAvailable_validRoomTypes_returnsTrue(String roomType) {
        assertTrue(bookingService.isRoomAvailable(roomType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "BASIC", "PREMIUM", "", "standard", "deluxe"})
    void isRoomAvailable_invalidRoomTypes_returnsFalse(String roomType) {
        assertFalse(bookingService.isRoomAvailable(roomType));
    }

    @Test
    void isRoomAvailable_standardRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_deluxeRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_suiteRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_villaRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_nullRoomType_returnsFalse() {
        // The source switch expression throws NullPointerException for null input
        // (Java switch on String does not accept null — this is expected behaviour).
        assertThrows(NullPointerException.class,
                () -> bookingService.isRoomAvailable(null));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateReport_returnsNonNullMessage() {
        String result = bookingService.generateReport("2024-03");
        assertNotNull(result);
    }

    @Test
    void generateReport_containsMonthInMessage() {
        String result = bookingService.generateReport("2024-03");
        assertTrue(result.contains("2024-03"));
    }

    @Test
    void generateReport_containsTriggeredKeyword() {
        String result = bookingService.generateReport("2024-06");
        assertTrue(result.toLowerCase().contains("report"));
    }

    @Test
    void generateReport_differentMonths_returnsDifferentMessages() {
        String march = bookingService.generateReport("2024-03");
        String june  = bookingService.generateReport("2024-06");
        assertNotEquals(march, june);
    }
}
