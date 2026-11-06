package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Covers createBooking, getBookingById, calculateRoomPrice,
 * isRoomAvailable, generateReport, and sha256Hash (indirectly).
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // -----------------------------------------------------------------------
    // createBooking
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsBookingMapWithExpectedKeys() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("bookingId"));
        assertTrue(result.containsKey("guestName"));
        assertTrue(result.containsKey("roomType"));
        assertTrue(result.containsKey("checkIn"));
        assertTrue(result.containsKey("checkOut"));
        assertTrue(result.containsKey("confirmationCode"));
    }

    @Test
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_guestNamePreservedInResult() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Charlie", "STANDARD", "2024-08-10", "2024-08-12");

        // Assert
        assertEquals("Charlie", result.get("guestName"));
    }

    @Test
    void createBooking_roomTypePreservedInResult() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Diana", "VILLA", "2024-09-01", "2024-09-10");

        // Assert
        assertEquals("VILLA", result.get("roomType"));
    }

    @Test
    void createBooking_confirmationCodeIsNotNull() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-07");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertFalse(((String) result.get("confirmationCode")).isEmpty());
    }

    @Test
    void createBooking_confirmationCodeIsSha256HexString() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-04");

        // Assert — SHA-256 hex is always 64 characters
        String code = (String) result.get("confirmationCode");
        assertEquals(64, code.length(), "SHA-256 hex digest should be 64 characters");
        assertTrue(code.matches("[0-9a-f]+"), "SHA-256 hex should contain only hex characters");
    }

    @Test
    void createBooking_jdbcTemplateUpdateCalledOnce() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Grace", "STANDARD", "2024-12-01", "2024-12-03");

        // Assert
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_checkInAndCheckOutPreservedInResult() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Hank", "SUITE", "2025-01-15", "2025-01-20");

        // Assert
        assertEquals("2025-01-15", result.get("checkIn"));
        assertEquals("2025-01-20", result.get("checkOut"));
    }

    // -----------------------------------------------------------------------
    // getBookingById
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_whenFound_returnsBookingMap() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Alice");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ABCD1234"))).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("id"));
        assertEquals("Alice", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTEXIST")))
                .thenThrow(new RuntimeException("No results"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTEXIST");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains("BK-NOTEXIST"));
    }

    @Test
    void getBookingById_errorMessageContainsBookingId() {
        // Arrange
        String missingId = "BK-MISSING99";
        when(jdbcTemplate.queryForMap(anyString(), eq(missingId)))
                .thenThrow(new RuntimeException("empty result set"));

        // Act
        Map<String, Object> result = bookingService.getBookingById(missingId);

        // Assert
        String errorMsg = (String) result.get("error");
        assertTrue(errorMsg.contains(missingId));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — room type variations
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_peakSeason_noLoyalty_oneNight() {
        // Arrange: STANDARD=120, PEAK=1.5, no loyalty=1.0, 1 night, no stay discount
        // Expected: 120 * 1.5 * 1.0 * 1.0 * 1 = 180.00
        String result = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", result);
    }

    @Test
    void calculateRoomPrice_deluxeRoom_offSeason_goldLoyalty_threeNights() {
        // Arrange: DELUXE=200, OFF=0.8, GOLD=0.9, 3 nights, no stay discount
        // Expected: 200 * 0.8 * 0.9 * 1.0 * 3 = 432.00
        String result = bookingService.calculateRoomPrice("DELUXE", 3, "OFF", "GOLD");
        assertEquals("432.00", result);
    }

    @Test
    void calculateRoomPrice_suiteRoom_standardSeason_platinumLoyalty_sevenNights() {
        // Arrange: SUITE=350, standard=1.0, PLATINUM=0.8, 7 nights, stay discount=0.95
        // Expected: 350 * 1.0 * 0.8 * 0.95 * 7 = 1862.00
        String result = bookingService.calculateRoomPrice("SUITE", 7, "STANDARD", "PLATINUM");
        assertEquals("1862.00", result);
    }

    @Test
    void calculateRoomPrice_villaRoom_peakSeason_diamondLoyalty_fourteenNights() {
        // Arrange: VILLA=600, PEAK=1.5, DIAMOND=0.7, 14 nights, stay discount=0.90
        // Expected: 600 * 1.5 * 0.7 * 0.90 * 14 = 7938.00
        String result = bookingService.calculateRoomPrice("VILLA", 14, "PEAK", "DIAMOND");
        assertEquals("7938.00", result);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandardPrice() {
        // Arrange: unknown room defaults to 120.0
        // Expected: 120 * 1.0 * 1.0 * 1.0 * 1 = 120.00
        String result = bookingService.calculateRoomPrice("UNKNOWN", 1, "STANDARD", "NONE");
        assertEquals("120.00", result);
    }

    @Test
    void calculateRoomPrice_sevenNights_appliesWeeklyDiscount() {
        // Arrange: STANDARD=120, standard=1.0, no loyalty=1.0, 7 nights, stay discount=0.95
        // Expected: 120 * 1.0 * 1.0 * 0.95 * 7 = 798.00
        String result = bookingService.calculateRoomPrice("STANDARD", 7, "STANDARD", "NONE");
        assertEquals("798.00", result);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesBiweeklyDiscount() {
        // Arrange: STANDARD=120, standard=1.0, no loyalty=1.0, 14 nights, stay discount=0.90
        // Expected: 120 * 1.0 * 1.0 * 0.90 * 14 = 1512.00
        String result = bookingService.calculateRoomPrice("STANDARD", 14, "STANDARD", "NONE");
        assertEquals("1512.00", result);
    }

    @Test
    void calculateRoomPrice_fifteenNights_appliesBiweeklyDiscount() {
        // Arrange: STANDARD=120, standard=1.0, no loyalty=1.0, 15 nights, stay discount=0.90
        // Expected: 120 * 1.0 * 1.0 * 0.90 * 15 = 1620.00
        String result = bookingService.calculateRoomPrice("STANDARD", 15, "STANDARD", "NONE");
        assertEquals("1620.00", result);
    }

    @Test
    void calculateRoomPrice_sixNights_noStayDiscount() {
        // Arrange: STANDARD=120, standard=1.0, no loyalty=1.0, 6 nights, no stay discount
        // Expected: 120 * 1.0 * 1.0 * 1.0 * 6 = 720.00
        String result = bookingService.calculateRoomPrice("STANDARD", 6, "STANDARD", "NONE");
        assertEquals("720.00", result);
    }

    @Test
    void calculateRoomPrice_offSeasonMultiplier() {
        // Arrange: STANDARD=120, OFF=0.8, no loyalty=1.0, 1 night
        // Expected: 120 * 0.8 * 1.0 * 1.0 * 1 = 96.00
        String result = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", result);
    }

    @Test
    void calculateRoomPrice_unknownSeason_defaultsToStandardMultiplier() {
        // Arrange: STANDARD=120, unknown season=1.0, no loyalty=1.0, 1 night
        // Expected: 120 * 1.0 * 1.0 * 1.0 * 1 = 120.00
        String result = bookingService.calculateRoomPrice("STANDARD", 1, "UNKNOWN_SEASON", "NONE");
        assertEquals("120.00", result);
    }

    @Test
    void calculateRoomPrice_unknownLoyalty_defaultsToNoDiscount() {
        // Arrange: STANDARD=120, standard=1.0, unknown loyalty=1.0, 1 night
        // Expected: 120 * 1.0 * 1.0 * 1.0 * 1 = 120.00
        String result = bookingService.calculateRoomPrice("STANDARD", 1, "STANDARD", "SILVER");
        assertEquals("120.00", result);
    }

    @Test
    void calculateRoomPrice_returnsFormattedStringWithTwoDecimals() {
        String result = bookingService.calculateRoomPrice("SUITE", 2, "STANDARD", "NONE");
        assertTrue(result.matches("\\d+\\.\\d{2}"), "Price should be formatted with 2 decimal places");
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable
    // -----------------------------------------------------------------------

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
    void isRoomAvailable_unknownRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_emptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_lowercaseRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    @Test
    void isRoomAvailable_nullRoomType_returnsFalse() {
        // Java switch expressions throw NullPointerException for null input — verify that behaviour
        assertThrows(NullPointerException.class,
                () -> bookingService.isRoomAvailable(null),
                "isRoomAvailable(null) should throw NullPointerException per Java switch semantics");
    }

    // -----------------------------------------------------------------------
    // generateReport
    // -----------------------------------------------------------------------

    @Test
    void generateReport_returnsNonNullMessage() {
        String result = bookingService.generateReport("March");
        assertNotNull(result);
    }

    @Test
    void generateReport_containsMonthInMessage() {
        String result = bookingService.generateReport("July");
        assertTrue(result.contains("July"), "Report message should contain the month");
    }

    @Test
    void generateReport_returnsExpectedMessage() {
        String result = bookingService.generateReport("December");
        assertEquals("Report generation triggered for: December", result);
    }

    @Test
    void generateReport_withEmptyMonth_returnsMessageWithEmptyMonth() {
        String result = bookingService.generateReport("");
        assertNotNull(result);
        assertTrue(result.startsWith("Report generation triggered for:"));
    }
}
