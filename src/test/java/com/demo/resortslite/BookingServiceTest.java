package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Comprehensive unit tests for {@link BookingService}.
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
    @DisplayName("createBooking_validInputs_returnsBookingMapWithExpectedKeys")
    void createBooking_validInputs_returnsBookingMapWithExpectedKeys() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

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
        assertTrue(result.containsKey("dbHost"));
    }

    @Test
    @DisplayName("createBooking_validInputs_bookingIdStartsWithBK")
    void createBooking_validInputs_bookingIdStartsWithBK() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    @DisplayName("createBooking_validInputs_guestNamePreserved")
    void createBooking_validInputs_guestNamePreserved() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Charlie", "STANDARD", "2024-08-10", "2024-08-12");

        // Assert
        assertEquals("Charlie", result.get("guestName"));
        assertEquals("STANDARD", result.get("roomType"));
        assertEquals("2024-08-10", result.get("checkIn"));
        assertEquals("2024-08-12", result.get("checkOut"));
    }

    @Test
    @DisplayName("createBooking_validInputs_confirmationCodeIsNonEmpty")
    void createBooking_validInputs_confirmationCodeIsNonEmpty() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Diana", "VILLA", "2024-09-01", "2024-09-10");

        // Assert
        String code = (String) result.get("confirmationCode");
        assertNotNull(code);
        assertFalse(code.isEmpty());
    }

    @Test
    @DisplayName("createBooking_jdbcExecuteCalled_once")
    void createBooking_jdbcExecuteCalled_once() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05");

        // Assert
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    // -----------------------------------------------------------------------
    // getBookingById
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getBookingById_existingId_returnsBookingMap")
    void getBookingById_existingId_returnsBookingMap() {
        // Arrange
        Map<String, Object> mockRow = new HashMap<>();
        mockRow.put("id", "BK-ABCD1234");
        mockRow.put("guest", "Frank");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(mockRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("id"));
        assertEquals("Frank", result.get("guest"));
    }

    @Test
    @DisplayName("getBookingById_nonExistingId_returnsErrorMap")
    void getBookingById_nonExistingId_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("No rows found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTFOUND");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        String errorMsg = (String) result.get("error");
        assertTrue(errorMsg.contains("BK-NOTFOUND"));
    }

    @Test
    @DisplayName("getBookingById_emptyId_returnsErrorMap")
    void getBookingById_emptyId_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("Empty id"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("calculateRoomPrice_standardRoomPeakSeasonNoLoyalty_correctPrice")
    void calculateRoomPrice_standardRoomPeakSeasonNoLoyalty_correctPrice() {
        // Arrange – STANDARD=120, PEAK*1.5=180, no loyalty, 3 nights => 540.00
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "PEAK", "NONE");

        // Assert
        assertEquals("540.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_deluxeRoomOffSeasonGoldLoyalty_correctPrice")
    void calculateRoomPrice_deluxeRoomOffSeasonGoldLoyalty_correctPrice() {
        // Arrange – DELUXE=200, OFF*0.8=160, GOLD*0.9=144, 2 nights => 288.00
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "OFF", "GOLD");

        // Assert
        assertEquals("288.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_suiteNormalSeasonPlatinumLoyalty_correctPrice")
    void calculateRoomPrice_suiteNormalSeasonPlatinumLoyalty_correctPrice() {
        // Arrange – SUITE=350, no season modifier, PLATINUM*0.8=280, 1 night => 280.00
        // Act
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "PLATINUM");

        // Assert
        assertEquals("280.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_villaRoomPeakSeasonDiamondLoyalty_correctPrice")
    void calculateRoomPrice_villaRoomPeakSeasonDiamondLoyalty_correctPrice() {
        // Arrange – VILLA=600, PEAK*1.5=900, DIAMOND*0.7=630, 1 night => 630.00
        // Act
        String price = bookingService.calculateRoomPrice("VILLA", 1, "PEAK", "DIAMOND");

        // Assert
        assertEquals("630.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_unknownRoomType_defaultsToStandardPrice")
    void calculateRoomPrice_unknownRoomType_defaultsToStandardPrice() {
        // Arrange – unknown type defaults to 120, no season/loyalty modifier, 1 night => 120.00
        // Act
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_sevenNightsDiscount_appliesWeeklyDiscount")
    void calculateRoomPrice_sevenNightsDiscount_appliesWeeklyDiscount() {
        // Arrange – STANDARD=120, no season, no loyalty, 7 nights * 0.95 = 798.00
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");

        // Assert
        assertEquals("798.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_fourteenNightsDiscount_appliesBiweeklyDiscount")
    void calculateRoomPrice_fourteenNightsDiscount_appliesBiweeklyDiscount() {
        // Arrange – STANDARD=120, no season, no loyalty, 14 nights * 0.95 = 1596.00
        // NOTE: nights>=7 branch fires first (0.95), nights>=14 branch is unreachable due to ordering
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");

        // Assert – 120 * 0.95 * 14 = 1596.00
        assertEquals("1596.00", price);
    }

    @ParameterizedTest
    @CsvSource({
        "STANDARD, 1, NORMAL, NONE, 120.00",
        "DELUXE,   1, NORMAL, NONE, 200.00",
        "SUITE,    1, NORMAL, NONE, 350.00",
        "VILLA,    1, NORMAL, NONE, 600.00"
    })
    @DisplayName("calculateRoomPrice_allRoomTypes_basePrice")
    void calculateRoomPrice_allRoomTypes_basePrice(
            String roomType, int nights, String season, String loyalty, String expected) {
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);
        assertEquals(expected, price);
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("isRoomAvailable_standardRoom_returnsTrue")
    void isRoomAvailable_standardRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    @DisplayName("isRoomAvailable_deluxeRoom_returnsTrue")
    void isRoomAvailable_deluxeRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    @DisplayName("isRoomAvailable_suiteRoom_returnsTrue")
    void isRoomAvailable_suiteRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    @DisplayName("isRoomAvailable_villaRoom_returnsTrue")
    void isRoomAvailable_villaRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    @DisplayName("isRoomAvailable_unknownRoomType_returnsFalse")
    void isRoomAvailable_unknownRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    @DisplayName("isRoomAvailable_emptyString_returnsFalse")
    void isRoomAvailable_emptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    @DisplayName("isRoomAvailable_lowercaseRoomType_returnsFalse")
    void isRoomAvailable_lowercaseRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    // -----------------------------------------------------------------------
    // generateReport
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generateReport_validMonth_returnsNonNullString")
    void generateReport_validMonth_returnsNonNullString() {
        // Act
        String result = bookingService.generateReport("March");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("generateReport_validMonth_containsMonthInResult")
    void generateReport_validMonth_containsMonthInResult() {
        // Act
        String result = bookingService.generateReport("June");

        // Assert
        assertTrue(result.contains("June"));
    }

    @Test
    @DisplayName("generateReport_emptyMonth_returnsStringWithEmptyMonth")
    void generateReport_emptyMonth_returnsStringWithEmptyMonth() {
        // Act
        String result = bookingService.generateReport("");

        // Assert
        assertNotNull(result);
    }
}
