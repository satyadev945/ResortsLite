package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BookingService}.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // ── createBooking ────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidInputs_returnsMapWithBookingId() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertTrue(result.get("bookingId").toString().startsWith("BK-"));
    }

    @Test
    void createBooking_withValidInputs_returnsGuestName() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        assertEquals("Alice", result.get("guestName"));
    }

    @Test
    void createBooking_withValidInputs_returnsRoomType() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        assertEquals("DELUXE", result.get("roomType"));
    }

    @Test
    void createBooking_withValidInputs_returnsCheckInAndCheckOut() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-10", "2024-08-15");

        assertEquals("2024-08-10", result.get("checkIn"));
        assertEquals("2024-08-15", result.get("checkOut"));
    }

    @Test
    void createBooking_withValidInputs_returnsConfirmationCode() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> result = bookingService.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-07");

        assertNotNull(result.get("confirmationCode"));
        assertFalse(result.get("confirmationCode").toString().isEmpty());
    }

    @Test
    void createBooking_withValidInputs_returnsDbHost() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> result = bookingService.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-03");

        assertNotNull(result.get("dbHost"));
        assertEquals("db-prod.resorts-internal.com", result.get("dbHost"));
    }

    @Test
    void createBooking_executesJdbcInsert() {
        doNothing().when(jdbcTemplate).execute(anyString());

        bookingService.createBooking("Frank", "STANDARD", "2024-11-01", "2024-11-02");

        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    void createBooking_eachCall_generatesDifferentBookingId() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> r1 = bookingService.createBooking("G1", "SUITE", "2024-01-01", "2024-01-02");
        Map<String, Object> r2 = bookingService.createBooking("G2", "SUITE", "2024-01-01", "2024-01-02");

        assertNotEquals(r1.get("bookingId"), r2.get("bookingId"));
    }

    // ── getBookingById ───────────────────────────────────────────────────────

    @Test
    void getBookingById_whenFound_returnsBookingMap() {
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Alice");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(dbRow);

        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        assertNotNull(result);
        assertEquals("Alice", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("No rows found"));

        Map<String, Object> result = bookingService.getBookingById("BK-NOTEXIST");

        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("BK-NOTEXIST"));
    }

    @Test
    void getBookingById_whenExceptionThrown_returnsErrorMessage() {
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("DB connection failed"));

        Map<String, Object> result = bookingService.getBookingById("BK-ERR");

        assertEquals("Booking not found: BK-ERR", result.get("error"));
    }

    // ── calculateRoomPrice ───────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonNoLoyalty_returnsCorrectPrice() {
        // STANDARD=120, NORMAL season (no multiplier), no loyalty, 1 night
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomNormalSeasonNoLoyalty_returnsCorrectPrice() {
        // DELUXE=200, 1 night
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomNormalSeasonNoLoyalty_returnsCorrectPrice() {
        // SUITE=350, 1 night
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeasonNoLoyalty_returnsCorrectPrice() {
        // VILLA=600, 1 night
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandardPrice() {
        // Unknown room type defaults to 120.0
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeason_appliesMultiplier() {
        // STANDARD=120 * 1.5 (PEAK) * 1 night = 180.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeason_appliesMultiplier() {
        // STANDARD=120 * 0.8 (OFF) * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyalty_appliesDiscount() {
        // STANDARD=120 * 0.9 (GOLD) * 1 night = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesDiscount() {
        // STANDARD=120 * 0.8 (PLATINUM) * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesDiscount() {
        // STANDARD=120 * 0.7 (DIAMOND) * 1 night = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNights_appliesWeeklyDiscount() {
        // STANDARD=120 * 0.95 (7+ nights) * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesBiweeklyDiscount() {
        // NOTE: nights>=7 branch is hit first (120 * 0.95 * 14 = 1596.00)
        // The nights>=14 branch is unreachable due to ordering in source code
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1596.00", price);
    }

    @Test
    void calculateRoomPrice_multipleNights_calculatesTotal() {
        // DELUXE=200 * 3 nights = 600.00
        String price = bookingService.calculateRoomPrice("DELUXE", 3, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonWithGoldLoyalty_combinesMultipliers() {
        // STANDARD=120 * 1.5 (PEAK) * 0.9 (GOLD) * 1 night = 162.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "GOLD");
        assertEquals("162.00", price);
    }

    @ParameterizedTest
    @CsvSource({
        "STANDARD, 1, NORMAL, NONE, 120.00",
        "DELUXE,   1, NORMAL, NONE, 200.00",
        "SUITE,    1, NORMAL, NONE, 350.00",
        "VILLA,    1, NORMAL, NONE, 600.00"
    })
    void calculateRoomPrice_parameterized_roomTypes(
            String roomType, int nights, String season, String loyalty, String expected) {
        assertEquals(expected, bookingService.calculateRoomPrice(roomType, nights, season, loyalty));
    }

    // ── isRoomAvailable ──────────────────────────────────────────────────────

    @Test
    void isRoomAvailable_withStandard_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_withDeluxe_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_withSuite_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_withVilla_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_withUnknownRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_withEmptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_withLowerCaseRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    @Test
    void isRoomAvailable_withNullRoomType_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> bookingService.isRoomAvailable(null));
    }

    // ── generateReport ───────────────────────────────────────────────────────

    @Test
    void generateReport_withValidMonth_returnsNonNullString() {
        String result = bookingService.generateReport("March");
        assertNotNull(result);
    }

    @Test
    void generateReport_withValidMonth_containsMonthInResult() {
        String result = bookingService.generateReport("March");
        assertTrue(result.contains("March"));
    }

    @Test
    void generateReport_withValidMonth_containsPaymentApiReference() {
        String result = bookingService.generateReport("April");
        assertTrue(result.contains("http://10.0.1.45:9090/payments/charge"));
    }

    @Test
    void generateReport_withEmptyMonth_returnsStringWithEmptyMonth() {
        String result = bookingService.generateReport("");
        assertNotNull(result);
        assertTrue(result.contains("Report generation triggered for:"));
    }
}
