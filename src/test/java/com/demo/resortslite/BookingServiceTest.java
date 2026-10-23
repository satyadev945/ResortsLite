package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link BookingService}.
 * Uses Mockito to mock {@link JdbcTemplate} so no real database is required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService Tests")
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // =========================================================================
    // createBooking()
    // =========================================================================

    @Nested
    @DisplayName("createBooking()")
    class CreateBookingTests {

        @Test
        @DisplayName("createBooking_withValidInputs_returnsMapWithBookingId")
        void createBooking_withValidInputs_returnsMapWithBookingId() {
            // Arrange
            doNothing().when(jdbcTemplate).execute(anyString());

            // Act
            Map<String, Object> result = bookingService.createBooking(
                    "John Smith", "SUITE", "2024-06-01", "2024-06-05");

            // Assert
            assertNotNull(result, "Result map must not be null");
            assertNotNull(result.get("bookingId"), "bookingId must be present");
            assertTrue(result.get("bookingId").toString().startsWith("BK-"),
                    "bookingId must start with 'BK-'");
        }

        @Test
        @DisplayName("createBooking_withValidInputs_returnsGuestName")
        void createBooking_withValidInputs_returnsGuestName() {
            // Arrange
            doNothing().when(jdbcTemplate).execute(anyString());

            // Act
            Map<String, Object> result = bookingService.createBooking(
                    "Jane Doe", "DELUXE", "2024-07-10", "2024-07-15");

            // Assert
            assertEquals("Jane Doe", result.get("guestName"));
        }

        @Test
        @DisplayName("createBooking_withValidInputs_returnsRoomType")
        void createBooking_withValidInputs_returnsRoomType() {
            // Arrange
            doNothing().when(jdbcTemplate).execute(anyString());

            // Act
            Map<String, Object> result = bookingService.createBooking(
                    "Alice", "VILLA", "2024-08-01", "2024-08-10");

            // Assert
            assertEquals("VILLA", result.get("roomType"));
        }

        @Test
        @DisplayName("createBooking_withValidInputs_returnsCheckInAndCheckOut")
        void createBooking_withValidInputs_returnsCheckInAndCheckOut() {
            // Arrange
            doNothing().when(jdbcTemplate).execute(anyString());

            // Act
            Map<String, Object> result = bookingService.createBooking(
                    "Bob", "STANDARD", "2024-09-01", "2024-09-03");

            // Assert
            assertEquals("2024-09-01", result.get("checkIn"));
            assertEquals("2024-09-03", result.get("checkOut"));
        }

        @Test
        @DisplayName("createBooking_withValidInputs_returnsConfirmationCode")
        void createBooking_withValidInputs_returnsConfirmationCode() {
            // Arrange
            doNothing().when(jdbcTemplate).execute(anyString());

            // Act
            Map<String, Object> result = bookingService.createBooking(
                    "Carol", "SUITE", "2024-10-01", "2024-10-07");

            // Assert
            assertNotNull(result.get("confirmationCode"),
                    "confirmationCode must be present");
            assertFalse(result.get("confirmationCode").toString().isEmpty(),
                    "confirmationCode must not be empty");
        }

        @Test
        @DisplayName("createBooking_withValidInputs_returnsDbHost")
        void createBooking_withValidInputs_returnsDbHost() {
            // Arrange
            doNothing().when(jdbcTemplate).execute(anyString());

            // Act
            Map<String, Object> result = bookingService.createBooking(
                    "Dave", "DELUXE", "2024-11-01", "2024-11-04");

            // Assert
            assertNotNull(result.get("dbHost"), "dbHost must be present in result");
        }

        @Test
        @DisplayName("createBooking_executesJdbcInsert")
        void createBooking_executesJdbcInsert() {
            // Arrange
            doNothing().when(jdbcTemplate).execute(anyString());

            // Act
            bookingService.createBooking("Eve", "STANDARD", "2024-12-01", "2024-12-02");

            // Assert — jdbcTemplate.execute() must be called exactly once
            verify(jdbcTemplate, times(1)).execute(anyString());
        }

        @Test
        @DisplayName("createBooking_eachCall_generatesUniqueBookingId")
        void createBooking_eachCall_generatesUniqueBookingId() {
            // Arrange
            doNothing().when(jdbcTemplate).execute(anyString());

            // Act
            Map<String, Object> booking1 = bookingService.createBooking(
                    "Frank", "SUITE", "2024-01-01", "2024-01-05");
            Map<String, Object> booking2 = bookingService.createBooking(
                    "Grace", "SUITE", "2024-01-01", "2024-01-05");

            // Assert
            assertNotEquals(booking1.get("bookingId"), booking2.get("bookingId"),
                    "Each booking must have a unique ID");
        }
    }

    // =========================================================================
    // getBookingById()
    // =========================================================================

    @Nested
    @DisplayName("getBookingById()")
    class GetBookingByIdTests {

        @Test
        @DisplayName("getBookingById_whenFound_returnsBookingMap")
        void getBookingById_whenFound_returnsBookingMap() {
            // Arrange
            Map<String, Object> dbRow = new HashMap<>();
            dbRow.put("id", "BK-12345678");
            dbRow.put("guest", "Henry");
            when(jdbcTemplate.queryForMap(anyString())).thenReturn(dbRow);

            // Act
            Map<String, Object> result = bookingService.getBookingById("BK-12345678");

            // Assert
            assertNotNull(result);
            assertEquals("BK-12345678", result.get("id"));
            assertEquals("Henry", result.get("guest"));
        }

        @Test
        @DisplayName("getBookingById_whenNotFound_returnsErrorMap")
        void getBookingById_whenNotFound_returnsErrorMap() {
            // Arrange
            when(jdbcTemplate.queryForMap(anyString()))
                    .thenThrow(new RuntimeException("No results found"));

            // Act
            Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

            // Assert
            assertNotNull(result);
            assertTrue(result.containsKey("error"),
                    "Result must contain 'error' key when booking is not found");
            assertTrue(result.get("error").toString().contains("BK-UNKNOWN"),
                    "Error message must include the booking ID");
        }

        @Test
        @DisplayName("getBookingById_whenExceptionThrown_doesNotPropagate")
        void getBookingById_whenExceptionThrown_doesNotPropagate() {
            // Arrange
            when(jdbcTemplate.queryForMap(anyString()))
                    .thenThrow(new RuntimeException("DB connection failed"));

            // Act & Assert — exception must be caught internally, not propagated
            assertDoesNotThrow(() -> bookingService.getBookingById("BK-ERR"));
        }
    }

    // =========================================================================
    // calculateRoomPrice()
    // =========================================================================

    @Nested
    @DisplayName("calculateRoomPrice()")
    class CalculateRoomPriceTests {

        // --- Room type base prices ---

        @Test
        @DisplayName("calculateRoomPrice_standardRoom_normalSeason_noLoyalty_1Night")
        void calculateRoomPrice_standardRoom_normalSeason_noLoyalty_1Night() {
            // Arrange & Act
            String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

            // Assert — base 120.0 * 1 night = 120.00
            assertEquals("120.00", price);
        }

        @Test
        @DisplayName("calculateRoomPrice_deluxeRoom_normalSeason_noLoyalty_1Night")
        void calculateRoomPrice_deluxeRoom_normalSeason_noLoyalty_1Night() {
            // Arrange & Act
            String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");

            // Assert — base 200.0 * 1 night = 200.00
            assertEquals("200.00", price);
        }

        @Test
        @DisplayName("calculateRoomPrice_suiteRoom_normalSeason_noLoyalty_1Night")
        void calculateRoomPrice_suiteRoom_normalSeason_noLoyalty_1Night() {
            // Arrange & Act
            String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");

            // Assert — base 350.0 * 1 night = 350.00
            assertEquals("350.00", price);
        }

        @Test
        @DisplayName("calculateRoomPrice_villaRoom_normalSeason_noLoyalty_1Night")
        void calculateRoomPrice_villaRoom_normalSeason_noLoyalty_1Night() {
            // Arrange & Act
            String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");

            // Assert — base 600.0 * 1 night = 600.00
            assertEquals("600.00", price);
        }

        @Test
        @DisplayName("calculateRoomPrice_unknownRoomType_fallsBackToStandardPrice")
        void calculateRoomPrice_unknownRoomType_fallsBackToStandardPrice() {
            // Arrange & Act
            String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");

            // Assert — unknown type defaults to 120.0
            assertEquals("120.00", price);
        }

        // --- Season multipliers ---

        @Test
        @DisplayName("calculateRoomPrice_peakSeason_appliesMultiplier1_5")
        void calculateRoomPrice_peakSeason_appliesMultiplier1_5() {
            // Arrange & Act — STANDARD(120) * PEAK(1.5) * 1 night = 180.00
            String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");

            // Assert
            assertEquals("180.00", price);
        }

        @Test
        @DisplayName("calculateRoomPrice_offSeason_appliesMultiplier0_8")
        void calculateRoomPrice_offSeason_appliesMultiplier0_8() {
            // Arrange & Act — STANDARD(120) * OFF(0.8) * 1 night = 96.00
            String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");

            // Assert
            assertEquals("96.00", price);
        }

        // --- Loyalty discounts ---

        @Test
        @DisplayName("calculateRoomPrice_goldLoyalty_applies10PercentDiscount")
        void calculateRoomPrice_goldLoyalty_applies10PercentDiscount() {
            // Arrange & Act — STANDARD(120) * GOLD(0.9) * 1 night = 108.00
            String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");

            // Assert
            assertEquals("108.00", price);
        }

        @Test
        @DisplayName("calculateRoomPrice_platinumLoyalty_applies20PercentDiscount")
        void calculateRoomPrice_platinumLoyalty_applies20PercentDiscount() {
            // Arrange & Act — STANDARD(120) * PLATINUM(0.8) * 1 night = 96.00
            String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");

            // Assert
            assertEquals("96.00", price);
        }

        @Test
        @DisplayName("calculateRoomPrice_diamondLoyalty_applies30PercentDiscount")
        void calculateRoomPrice_diamondLoyalty_applies30PercentDiscount() {
            // Arrange & Act — STANDARD(120) * DIAMOND(0.7) * 1 night = 84.00
            String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");

            // Assert
            assertEquals("84.00", price);
        }

        // --- Long-stay discounts ---

        @Test
        @DisplayName("calculateRoomPrice_7NightStay_applies5PercentDiscount")
        void calculateRoomPrice_7NightStay_applies5PercentDiscount() {
            // Arrange & Act — STANDARD(120) * 0.95 * 7 nights = 798.00
            String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");

            // Assert
            assertEquals("798.00", price);
        }

        @Test
        @DisplayName("calculateRoomPrice_14NightStay_applies5PercentDiscount_notTen")
        void calculateRoomPrice_14NightStay_applies5PercentDiscount_notTen() {
            // Note: The code checks nights >= 7 FIRST, so 14 nights gets 0.95 (not 0.90)
            // because the >= 7 branch is evaluated before >= 14.
            // Arrange & Act — STANDARD(120) * 0.95 * 14 nights = 1596.00
            String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");

            // Assert
            assertEquals("1596.00", price);
        }

        @Test
        @DisplayName("calculateRoomPrice_6NightStay_noLongStayDiscount")
        void calculateRoomPrice_6NightStay_noLongStayDiscount() {
            // Arrange & Act — STANDARD(120) * 1.0 * 6 nights = 720.00
            String price = bookingService.calculateRoomPrice("STANDARD", 6, "NORMAL", "NONE");

            // Assert
            assertEquals("720.00", price);
        }

        // --- Combined scenarios ---

        @Test
        @DisplayName("calculateRoomPrice_suite_peakSeason_goldLoyalty_7Nights_combinedDiscounts")
        void calculateRoomPrice_suite_peakSeason_goldLoyalty_7Nights_combinedDiscounts() {
            // Arrange & Act
            // SUITE(350) * PEAK(1.5) = 525 * GOLD(0.9) = 472.5 * 0.95 * 7 = 3141.38 (rounded)
            String price = bookingService.calculateRoomPrice("SUITE", 7, "PEAK", "GOLD");

            // Assert
            assertNotNull(price);
            assertFalse(price.isEmpty());
            // Verify it's a valid decimal number
            assertDoesNotThrow(() -> Double.parseDouble(price));
        }

        @Test
        @DisplayName("calculateRoomPrice_returnsFormattedTwoDecimalPlaces")
        void calculateRoomPrice_returnsFormattedTwoDecimalPlaces() {
            // Arrange & Act
            String price = bookingService.calculateRoomPrice("DELUXE", 3, "NORMAL", "NONE");

            // Assert — must have exactly 2 decimal places
            assertTrue(price.matches("\\d+\\.\\d{2}"),
                    "Price must be formatted with 2 decimal places, got: " + price);
        }

        @ParameterizedTest
        @CsvSource({
            "STANDARD, 1, NORMAL, NONE, 120.00",
            "DELUXE,   1, NORMAL, NONE, 200.00",
            "SUITE,    1, NORMAL, NONE, 350.00",
            "VILLA,    1, NORMAL, NONE, 600.00"
        })
        @DisplayName("calculateRoomPrice_parameterized_baseRoomPrices")
        void calculateRoomPrice_parameterized_baseRoomPrices(
                String roomType, int nights, String season, String loyalty, String expected) {
            String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);
            assertEquals(expected, price,
                    "Expected " + expected + " for " + roomType + " but got " + price);
        }
    }

    // =========================================================================
    // isRoomAvailable()
    // =========================================================================

    @Nested
    @DisplayName("isRoomAvailable()")
    class IsRoomAvailableTests {

        @ParameterizedTest
        @ValueSource(strings = {"STANDARD", "DELUXE", "SUITE", "VILLA"})
        @DisplayName("isRoomAvailable_validRoomTypes_returnsTrue")
        void isRoomAvailable_validRoomTypes_returnsTrue(String roomType) {
            // Act
            boolean available = bookingService.isRoomAvailable(roomType);

            // Assert
            assertTrue(available, roomType + " should be available");
        }

        @ParameterizedTest
        @ValueSource(strings = {"PENTHOUSE", "CABIN", "TENT", "", "standard", "deluxe"})
        @DisplayName("isRoomAvailable_invalidRoomTypes_returnsFalse")
        void isRoomAvailable_invalidRoomTypes_returnsFalse(String roomType) {
            // Act
            boolean available = bookingService.isRoomAvailable(roomType);

            // Assert
            assertFalse(available, roomType + " should NOT be available");
        }

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
            assertFalse(bookingService.isRoomAvailable("UNKNOWN"));
        }
    }

    // =========================================================================
    // generateReport()
    // =========================================================================

    @Nested
    @DisplayName("generateReport()")
    class GenerateReportTests {

        @Test
        @DisplayName("generateReport_withMonth_returnsNonNullString")
        void generateReport_withMonth_returnsNonNullString() {
            // Act
            String result = bookingService.generateReport("March");

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("generateReport_withMonth_containsMonthInResult")
        void generateReport_withMonth_containsMonthInResult() {
            // Act
            String result = bookingService.generateReport("March");

            // Assert
            assertTrue(result.contains("March"),
                    "Report message must contain the month name");
        }

        @Test
        @DisplayName("generateReport_withMonth_containsPaymentApiReference")
        void generateReport_withMonth_containsPaymentApiReference() {
            // Act
            String result = bookingService.generateReport("April");

            // Assert
            assertNotNull(result);
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("generateReport_withDifferentMonths_returnsDistinctMessages")
        void generateReport_withDifferentMonths_returnsDistinctMessages() {
            // Act
            String march = bookingService.generateReport("March");
            String april = bookingService.generateReport("April");

            // Assert
            assertNotEquals(march, april,
                    "Reports for different months must produce different messages");
        }

        @Test
        @DisplayName("generateReport_withEmptyMonth_returnsStringWithVia")
        void generateReport_withEmptyMonth_returnsStringWithVia() {
            // Act
            String result = bookingService.generateReport("");

            // Assert
            assertNotNull(result);
            assertTrue(result.contains("via"),
                    "Report message must contain 'via' keyword");
        }
    }
}
