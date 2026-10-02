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

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // ─── createBooking ────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidInputs_returnsMapWithBookingId() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertTrue(result.get("bookingId").toString().startsWith("BK-"));
    }

    @Test
    void createBooking_withValidInputs_returnsGuestName() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        assertEquals("Bob", result.get("guestName"));
    }

    @Test
    void createBooking_withValidInputs_returnsRoomType() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Carol", "VILLA", "2024-08-01", "2024-08-10");

        // Assert
        assertEquals("VILLA", result.get("roomType"));
    }

    @Test
    void createBooking_withValidInputs_returnsCheckInAndCheckOut() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Dave", "STANDARD", "2024-09-01", "2024-09-04");

        // Assert
        assertEquals("2024-09-01", result.get("checkIn"));
        assertEquals("2024-09-04", result.get("checkOut"));
    }

    @Test
    void createBooking_withValidInputs_returnsConfirmationCode() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertFalse(result.get("confirmationCode").toString().isEmpty());
    }

    @Test
    void createBooking_confirmationCode_isSha256HexString() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Frank", "DELUXE", "2024-11-01", "2024-11-03");

        // Assert — SHA-256 hex is 64 chars
        String code = result.get("confirmationCode").toString();
        assertEquals(64, code.length());
        assertTrue(code.matches("[0-9a-f]+"));
    }

    @Test
    void createBooking_doesNotExposeDbHostInResponse() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Grace", "STANDARD", "2024-12-01", "2024-12-02");

        // Assert — infrastructure details must not leak
        assertFalse(result.containsKey("dbHost"));
    }

    @Test
    void createBooking_callsJdbcTemplateUpdate() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Henry", "SUITE", "2024-01-01", "2024-01-07");

        // Assert
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any(), any());
    }

    // ─── getBookingById ───────────────────────────────────────────────────────

    @Test
    void getBookingById_whenFound_returnsBookingMap() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-12345678");
        dbRow.put("guest", "Alice");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-12345678"))).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("Alice", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenThrow(new RuntimeException("No rows found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("BK-UNKNOWN"));
    }

    @Test
    void getBookingById_usesParameterisedQuery() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-SAFE"))).thenReturn(new HashMap<>());

        // Act
        bookingService.getBookingById("BK-SAFE");

        // Assert — parameterised call, not string concatenation
        verify(jdbcTemplate).queryForMap(anyString(), eq("BK-SAFE"));
    }

    // ─── calculateRoomPrice ───────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonNoLoyalty_returnsCorrectPrice() {
        // Arrange: STANDARD=120, NORMAL season (x1.0), no loyalty (x1.0), 3 nights
        // Act
        String result = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");

        // Assert: 120 * 3 = 360.00
        assertEquals("360.00", result);
    }

    @Test
    void calculateRoomPrice_deluxeRoomPeakSeasonNoLoyalty_returnsCorrectPrice() {
        // Arrange: DELUXE=200, PEAK (x1.5), no loyalty, 2 nights
        // Act
        String result = bookingService.calculateRoomPrice("DELUXE", 2, "PEAK", "NONE");

        // Assert: 200 * 1.5 * 2 = 600.00
        assertEquals("600.00", result);
    }

    @Test
    void calculateRoomPrice_suiteOffSeasonGoldLoyalty_returnsCorrectPrice() {
        // Arrange: SUITE=350, OFF (x0.8), GOLD (x0.9), 1 night
        // Act
        String result = bookingService.calculateRoomPrice("SUITE", 1, "OFF", "GOLD");

        // Assert: 350 * 0.8 * 0.9 * 1 = 252.00
        assertEquals("252.00", result);
    }

    @Test
    void calculateRoomPrice_villaRoomPlatinumLoyalty_returnsCorrectPrice() {
        // Arrange: VILLA=600, NORMAL, PLATINUM (x0.8), 1 night
        // Act
        String result = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "PLATINUM");

        // Assert: 600 * 0.8 = 480.00
        assertEquals("480.00", result);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesMaxDiscount() {
        // Arrange: STANDARD=120, NORMAL, DIAMOND (x0.7), 1 night
        // Act
        String result = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");

        // Assert: 120 * 0.7 = 84.00
        assertEquals("84.00", result);
    }

    @Test
    void calculateRoomPrice_sevenNightsStay_appliesLengthDiscount() {
        // Arrange: STANDARD=120, NORMAL, NONE, 7 nights → 5% discount
        // Act
        String result = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");

        // Assert: 120 * 0.95 * 7 = 798.00
        assertEquals("798.00", result);
    }

    @Test
    void calculateRoomPrice_fourteenNightsStay_appliesMaxLengthDiscount() {
        // Arrange: STANDARD=120, NORMAL, NONE, 14 nights → 10% discount
        // Act
        String result = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");

        // Assert: 120 * 0.90 * 14 = 1512.00
        assertEquals("1512.00", result);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_usesDefaultBasePrice() {
        // Arrange: unknown room type defaults to 120.0
        // Act
        String result = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");

        // Assert: 120 * 1 = 120.00
        assertEquals("120.00", result);
    }

    @Test
    void calculateRoomPrice_offSeason_reducesPrice() {
        // Arrange: STANDARD=120, OFF (x0.8), NONE, 1 night
        // Act
        String result = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");

        // Assert: 120 * 0.8 = 96.00
        assertEquals("96.00", result);
    }

    @Test
    void calculateRoomPrice_returnsFormattedTwoDecimalString() {
        // Act
        String result = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");

        // Assert: format is "200.00"
        assertTrue(result.matches("\\d+\\.\\d{2}"));
    }

    // ─── isRoomAvailable ──────────────────────────────────────────────────────

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
    void isRoomAvailable_nullRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(null));
    }

    @Test
    void isRoomAvailable_lowercaseRoomType_returnsFalse() {
        // Room type matching is case-sensitive
        assertFalse(bookingService.isRoomAvailable("suite"));
    }

    // ─── generateReport ───────────────────────────────────────────────────────

    @Test
    void generateReport_withMonth_returnsNonNullString() {
        // Act
        String result = bookingService.generateReport("March");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateReport_withMonth_containsMonthInResult() {
        // Act
        String result = bookingService.generateReport("April");

        // Assert
        assertTrue(result.contains("April"));
    }

    @Test
    void generateReport_withMonth_containsPaymentApiEndpoint() {
        // Act — paymentApi defaults to https://payment-svc.internal:9090/charge
        String result = bookingService.generateReport("May");

        // Assert
        assertTrue(result.contains("via"));
    }
}
