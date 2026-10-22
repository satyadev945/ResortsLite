package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for BookingService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService Tests")
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingService, "paymentApi",
                "https://payment-svc.internal:9090/charge");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createBooking_validInputs_returnsBookingMap")
    void createBooking_validInputs_returnsBookingMap() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(result, "Result should not be null");
        assertNotNull(result.get("bookingId"), "bookingId should be present");
        assertEquals("John Smith", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-03-01", result.get("checkIn"));
        assertEquals("2024-03-05", result.get("checkOut"));
        assertNotNull(result.get("confirmationCode"), "confirmationCode should be present");
    }

    @Test
    @DisplayName("createBooking_bookingIdStartsWithBK")
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    @DisplayName("createBooking_confirmationCodeIsNonEmpty")
    void createBooking_confirmationCodeIsNonEmpty() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "STANDARD", "2024-05-01", "2024-05-02");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        assertFalse(confirmCode.isEmpty(), "Confirmation code should not be empty");
    }

    @Test
    @DisplayName("createBooking_jdbcTemplateUpdateCalledOnce")
    void createBooking_jdbcTemplateUpdateCalledOnce() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        bookingService.createBooking("Bob", "VILLA", "2024-06-01", "2024-06-07");

        // Assert
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createBooking_differentGuestsProduceDifferentBookingIds")
    void createBooking_differentGuestsProduceDifferentBookingIds() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result1 = bookingService.createBooking(
                "Guest1", "STANDARD", "2024-01-01", "2024-01-02");
        Map<String, Object> result2 = bookingService.createBooking(
                "Guest2", "DELUXE", "2024-01-01", "2024-01-02");

        // Assert
        assertNotEquals(result1.get("bookingId"), result2.get("bookingId"),
                "Each booking should have a unique ID");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingById tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getBookingById_existingBooking_returnsBookingData")
    void getBookingById_existingBooking_returnsBookingData() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-12345678");
        dbRow.put("guest", "John Smith");
        dbRow.put("room", "SUITE");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-12345678"))).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("id"));
        assertEquals("John Smith", result.get("guest"));
    }

    @Test
    @DisplayName("getBookingById_nonExistingBooking_returnsErrorMap")
    void getBookingById_nonExistingBooking_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTFOUND")))
                .thenThrow(new RuntimeException("No results found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTFOUND");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"), "Result should contain 'error' key");
        String errorMsg = (String) result.get("error");
        assertTrue(errorMsg.contains("BK-NOTFOUND"), "Error message should contain the booking ID");
    }

    @Test
    @DisplayName("getBookingById_jdbcTemplateQueryCalledWithCorrectId")
    void getBookingById_jdbcTemplateQueryCalledWithCorrectId() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCDEF12");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ABCDEF12"))).thenReturn(dbRow);

        // Act
        bookingService.getBookingById("BK-ABCDEF12");

        // Assert
        verify(jdbcTemplate, times(1)).queryForMap(anyString(), eq("BK-ABCDEF12"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("calculateRoomPrice_standardRoomPeakSeason_correctPrice")
    void calculateRoomPrice_standardRoomPeakSeason_correctPrice() {
        // Arrange: STANDARD=120, PEAK=1.5x, no loyalty, 1 night → 120*1.5*1.0*1.0*1 = 180.00
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");

        // Assert
        assertEquals("180.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_deluxeRoomOffSeason_correctPrice")
    void calculateRoomPrice_deluxeRoomOffSeason_correctPrice() {
        // Arrange: DELUXE=200, OFF=0.8x, no loyalty, 1 night → 200*0.8*1.0*1.0*1 = 160.00
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "OFF", "NONE");

        // Assert
        assertEquals("160.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_suiteRoomStandardSeason_correctPrice")
    void calculateRoomPrice_suiteRoomStandardSeason_correctPrice() {
        // Arrange: SUITE=350, standard=1.0x, no loyalty, 1 night → 350*1.0*1.0*1.0*1 = 350.00
        // Act
        String price = bookingService.calculateRoomPrice("SUITE", 1, "STANDARD", "NONE");

        // Assert
        assertEquals("350.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_villaRoomStandardSeason_correctPrice")
    void calculateRoomPrice_villaRoomStandardSeason_correctPrice() {
        // Arrange: VILLA=600, standard=1.0x, no loyalty, 1 night → 600.00
        // Act
        String price = bookingService.calculateRoomPrice("VILLA", 1, "STANDARD", "NONE");

        // Assert
        assertEquals("600.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_unknownRoomType_usesDefaultPrice")
    void calculateRoomPrice_unknownRoomType_usesDefaultPrice() {
        // Arrange: default=120, standard=1.0x, no loyalty, 1 night → 120.00
        // Act
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "STANDARD", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_goldLoyalty_appliesDiscount")
    void calculateRoomPrice_goldLoyalty_appliesDiscount() {
        // Arrange: STANDARD=120, standard=1.0x, GOLD=0.9x, 1 night → 120*0.9 = 108.00
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "STANDARD", "GOLD");

        // Assert
        assertEquals("108.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_platinumLoyalty_appliesDiscount")
    void calculateRoomPrice_platinumLoyalty_appliesDiscount() {
        // Arrange: STANDARD=120, standard=1.0x, PLATINUM=0.8x, 1 night → 120*0.8 = 96.00
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "STANDARD", "PLATINUM");

        // Assert
        assertEquals("96.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_diamondLoyalty_appliesDiscount")
    void calculateRoomPrice_diamondLoyalty_appliesDiscount() {
        // Arrange: STANDARD=120, standard=1.0x, DIAMOND=0.7x, 1 night → 120*0.7 = 84.00
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "STANDARD", "DIAMOND");

        // Assert
        assertEquals("84.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_sevenNights_appliesWeeklyDiscount")
    void calculateRoomPrice_sevenNights_appliesWeeklyDiscount() {
        // Arrange: STANDARD=120, standard=1.0x, no loyalty, 7 nights, stayDiscount=0.95
        // → 120 * 1.0 * 1.0 * 0.95 * 7 = 798.00
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "STANDARD", "NONE");

        // Assert
        assertEquals("798.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_fourteenNights_appliesBiweeklyDiscount")
    void calculateRoomPrice_fourteenNights_appliesBiweeklyDiscount() {
        // Arrange: STANDARD=120, standard=1.0x, no loyalty, 14 nights, stayDiscount=0.90
        // → 120 * 1.0 * 1.0 * 0.90 * 14 = 1512.00
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "STANDARD", "NONE");

        // Assert
        assertEquals("1512.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_sixNights_noStayDiscount")
    void calculateRoomPrice_sixNights_noStayDiscount() {
        // Arrange: STANDARD=120, standard=1.0x, no loyalty, 6 nights, stayDiscount=1.0
        // → 120 * 1.0 * 1.0 * 1.0 * 6 = 720.00
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 6, "STANDARD", "NONE");

        // Assert
        assertEquals("720.00", price);
    }

    @Test
    @DisplayName("calculateRoomPrice_combinedDiscounts_correctTotal")
    void calculateRoomPrice_combinedDiscounts_correctTotal() {
        // Arrange: SUITE=350, PEAK=1.5x, DIAMOND=0.7x, 14 nights, stayDiscount=0.90
        // → 350 * 1.5 * 0.7 * 0.90 * 14 = 4630.50
        // Act
        String price = bookingService.calculateRoomPrice("SUITE", 14, "PEAK", "DIAMOND");

        // Assert
        assertEquals("4630.50", price);
    }

    @ParameterizedTest
    @CsvSource({
        "STANDARD, 1, STANDARD, NONE, 120.00",
        "DELUXE,   1, STANDARD, NONE, 200.00",
        "SUITE,    1, STANDARD, NONE, 350.00",
        "VILLA,    1, STANDARD, NONE, 600.00"
    })
    @DisplayName("calculateRoomPrice_baseRoomPrices_parameterized")
    void calculateRoomPrice_baseRoomPrices_parameterized(
            String roomType, int nights, String season, String loyalty, String expected) {
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);
        assertEquals(expected.trim(), price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isRoomAvailable tests
    // ─────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "DELUXE", "SUITE", "VILLA"})
    @DisplayName("isRoomAvailable_validRoomTypes_returnsTrue")
    void isRoomAvailable_validRoomTypes_returnsTrue(String roomType) {
        assertTrue(bookingService.isRoomAvailable(roomType),
                roomType + " should be available");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "PENTHOUSE", "", "standard", "deluxe"})
    @DisplayName("isRoomAvailable_invalidRoomTypes_returnsFalse")
    void isRoomAvailable_invalidRoomTypes_returnsFalse(String roomType) {
        assertFalse(bookingService.isRoomAvailable(roomType),
                roomType + " should not be available");
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
    @DisplayName("isRoomAvailable_unknownRoom_returnsFalse")
    void isRoomAvailable_unknownRoom_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateReport_validMonth_returnsStatusMessage")
    void generateReport_validMonth_returnsStatusMessage() {
        // Act
        String result = bookingService.generateReport("2024-03");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("2024-03"),
                "Report message should contain the month");
    }

    @Test
    @DisplayName("generateReport_containsPaymentApiEndpoint")
    void generateReport_containsPaymentApiEndpoint() {
        // Act
        String result = bookingService.generateReport("2024-06");

        // Assert
        assertTrue(result.contains("https://payment-svc.internal:9090/charge"),
                "Report message should reference the payment API endpoint");
    }

    @Test
    @DisplayName("generateReport_differentMonths_differentMessages")
    void generateReport_differentMonths_differentMessages() {
        // Act
        String result1 = bookingService.generateReport("2024-01");
        String result2 = bookingService.generateReport("2024-12");

        // Assert
        assertNotEquals(result1, result2, "Different months should produce different messages");
    }

    @Test
    @DisplayName("generateReport_triggeredMessage_containsKeyword")
    void generateReport_triggeredMessage_containsKeyword() {
        // Act
        String result = bookingService.generateReport("2024-07");

        // Assert
        assertTrue(result.toLowerCase().contains("report"),
                "Result should mention 'report'");
    }
}
