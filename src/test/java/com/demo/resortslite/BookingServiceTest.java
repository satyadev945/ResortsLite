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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for BookingService.
 * Covers createBooking, getBookingById, calculateRoomPrice,
 * isRoomAvailable, generateReport, and all helper methods.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingService, "paymentApi", "https://payment-svc/charge");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidInputs_returnsBookingMap() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

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
    void createBooking_bookingId_startsWithBKPrefix() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_confirmationCode_isSHA256Hash() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "STANDARD", "2024-05-01", "2024-05-02");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        // SHA-256 produces a 64-character hex string
        assertEquals(64, confirmCode.length(), "SHA-256 hash should be 64 hex characters");
        assertTrue(confirmCode.matches("[0-9a-f]+"), "Confirmation code should be hex");
    }

    @Test
    void createBooking_callsJdbcTemplateUpdate() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Bob", "VILLA", "2024-06-01", "2024-06-10");

        // Assert
        verify(jdbcTemplate, times(1)).update(
                eq("INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)"),
                anyString(), eq("Bob"), eq("VILLA"), eq("2024-06-01"), eq("2024-06-10")
        );
    }

    @Test
    void createBooking_withDifferentRoomTypes_returnsCorrectRoomType() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "VILLA", "2024-07-01", "2024-07-07");

        // Assert
        assertEquals("VILLA", result.get("roomType"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingById tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingById_withExistingId_returnsBookingData() {
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
    void getBookingById_withNonExistingId_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTFOUND")))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTFOUND");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"), "Should contain error key");
        assertTrue(result.get("error").toString().contains("BK-NOTFOUND"),
                "Error message should contain the booking ID");
    }

    @Test
    void getBookingById_usesParameterisedQuery() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), anyString())).thenReturn(new HashMap<>());

        // Act
        bookingService.getBookingById("BK-TEST");

        // Assert — verifies parameterised query is used (not string concatenation)
        verify(jdbcTemplate).queryForMap(eq("SELECT * FROM bookings WHERE id = ?"), eq("BK-TEST"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonNoLoyalty_returnsCorrectPrice() {
        // Arrange: STANDARD=120, NORMAL season (x1.0), no loyalty (x1.0), 3 nights
        // Expected: 120.0 * 3 = 360.00
        String result = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");
        assertEquals("360.00", result);
    }

    @Test
    void calculateRoomPrice_deluxeRoomPeakSeasonNoLoyalty_returnsCorrectPrice() {
        // Arrange: DELUXE=200, PEAK season (x1.5), no loyalty (x1.0), 2 nights
        // Expected: 200 * 1.5 * 2 = 600.00
        String result = bookingService.calculateRoomPrice("DELUXE", 2, "PEAK", "NONE");
        assertEquals("600.00", result);
    }

    @Test
    void calculateRoomPrice_suiteRoomOffSeasonGoldLoyalty_returnsCorrectPrice() {
        // Arrange: SUITE=350, OFF season (x0.8), GOLD loyalty (x0.9), 1 night
        // Expected: 350 * 0.8 * 0.9 * 1 = 252.00
        String result = bookingService.calculateRoomPrice("SUITE", 1, "OFF", "GOLD");
        assertEquals("252.00", result);
    }

    @Test
    void calculateRoomPrice_villaRoomPeakSeasonPlatinumLoyalty_returnsCorrectPrice() {
        // Arrange: VILLA=600, PEAK (x1.5), PLATINUM (x0.8), 1 night
        // Expected: 600 * 1.5 * 0.8 * 1 = 720.00
        String result = bookingService.calculateRoomPrice("VILLA", 1, "PEAK", "PLATINUM");
        assertEquals("720.00", result);
    }

    @Test
    void calculateRoomPrice_withDiamondLoyalty_appliesCorrectDiscount() {
        // Arrange: STANDARD=120, NORMAL (x1.0), DIAMOND (x0.7), 1 night
        // Expected: 120 * 0.7 * 1 = 84.00
        String result = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", result);
    }

    @Test
    void calculateRoomPrice_with7NightsStay_appliesLongStayDiscount() {
        // Arrange: STANDARD=120, NORMAL (x1.0), no loyalty (x1.0), 7 nights → 5% discount
        // Expected: 120 * 0.95 * 7 = 798.00
        String result = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", result);
    }

    @Test
    void calculateRoomPrice_with14NightsStay_appliesLongerStayDiscount() {
        // Arrange: STANDARD=120, NORMAL (x1.0), no loyalty (x1.0), 14 nights → 10% discount
        // Expected: 120 * 0.90 * 14 = 1512.00
        String result = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", result);
    }

    @Test
    void calculateRoomPrice_with15NightsStay_applies14PlusDiscount() {
        // Arrange: STANDARD=120, NORMAL (x1.0), no loyalty (x1.0), 15 nights → 10% discount
        // Expected: 120 * 0.90 * 15 = 1620.00
        String result = bookingService.calculateRoomPrice("STANDARD", 15, "NORMAL", "NONE");
        assertEquals("1620.00", result);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_usesDefaultBasePrice() {
        // Arrange: Unknown room type defaults to 120.0
        // Expected: 120 * 1 = 120.00
        String result = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", result);
    }

    @Test
    void calculateRoomPrice_returnsFormattedTwoDecimalString() {
        String result = bookingService.calculateRoomPrice("DELUXE", 3, "NORMAL", "NONE");
        // Should be formatted as "600.00"
        assertTrue(result.matches("\\d+\\.\\d{2}"), "Price should be formatted with 2 decimal places");
    }

    @ParameterizedTest
    @CsvSource({
            "STANDARD, 120.0",
            "DELUXE,   200.0",
            "SUITE,    350.0",
            "VILLA,    600.0"
    })
    void calculateRoomPrice_allRoomTypes_correctBasePrice(String roomType, double expectedBase) {
        // 1 night, NORMAL season, no loyalty → price = base * 1
        String result = bookingService.calculateRoomPrice(roomType, 1, "NORMAL", "NONE");
        assertEquals(String.format("%.2f", expectedBase), result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isRoomAvailable tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isRoomAvailable_withStandardRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_withDeluxeRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_withSuiteRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_withVillaRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_withInvalidRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_withNullRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(null));
    }

    @Test
    void isRoomAvailable_withEmptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "DELUXE", "SUITE", "VILLA"})
    void isRoomAvailable_allValidRoomTypes_returnTrue(String roomType) {
        assertTrue(bookingService.isRoomAvailable(roomType),
                roomType + " should be a valid room type");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateReport_withValidMonth_returnsReportMessage() {
        String result = bookingService.generateReport("March");
        assertNotNull(result);
        assertTrue(result.contains("March"), "Report message should contain the month");
    }

    @Test
    void generateReport_containsPaymentApiEndpoint() {
        String result = bookingService.generateReport("April");
        assertTrue(result.contains("https://payment-svc/charge"),
                "Report message should reference the payment API endpoint");
    }

    @Test
    void generateReport_withNumericMonth_returnsMessage() {
        String result = bookingService.generateReport("03");
        assertNotNull(result);
        assertTrue(result.contains("03"));
    }

    @Test
    void generateReport_messageContainsTriggeredText() {
        String result = bookingService.generateReport("January");
        assertTrue(result.contains("Report generation triggered for:"),
                "Message should contain expected prefix text");
    }
}
