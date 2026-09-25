package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    private BookingService bookingService;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService();
        jdbcTemplate = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(bookingService, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(bookingService, "dbHost", "localhost");
        ReflectionTestUtils.setField(bookingService, "paymentApi", "https://payment-svc.internal/charge");
    }

    // --- createBooking tests ---

    @Test
    void createBooking_withValidParameters_returnsBookingMap() {
        // Arrange
        String guestName = "John Smith";
        String roomType = "SUITE";
        String checkIn = "2024-03-01";
        String checkOut = "2024-03-05";

        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(result);
        assertEquals(guestName, result.get("guestName"));
        assertEquals(roomType, result.get("roomType"));
        assertEquals(checkIn, result.get("checkIn"));
        assertEquals(checkOut, result.get("checkOut"));
        assertNotNull(result.get("bookingId"));
        assertTrue(((String) result.get("bookingId")).startsWith("BK-"));
        assertNotNull(result.get("confirmationCode"));
        assertEquals("localhost", result.get("dbHost"));

        verify(jdbcTemplate).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_withDifferentRoomType_returnsBookingMap() {
        // Arrange
        String guestName = "Jane Doe";
        String roomType = "DELUXE";
        String checkIn = "2024-06-10";
        String checkOut = "2024-06-15";

        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(result);
        assertEquals("DELUXE", result.get("roomType"));
        assertEquals("Jane Doe", result.get("guestName"));
    }

    @Test
    void createBooking_withStandardRoomType_returnsBookingMap() {
        // Arrange
        String guestName = "Bob Wilson";
        String roomType = "STANDARD";
        String checkIn = "2024-01-01";
        String checkOut = "2024-01-03";

        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(result);
        assertEquals("STANDARD", result.get("roomType"));
    }

    @Test
    void createBooking_withVillaRoomType_returnsBookingMap() {
        // Arrange
        String guestName = "Alice Brown";
        String roomType = "VILLA";
        String checkIn = "2024-07-20";
        String checkOut = "2024-07-27";

        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(result);
        assertEquals("VILLA", result.get("roomType"));
    }

    @Test
    void createBooking_confirmationCodeIsSha256Hash() {
        // Arrange
        String guestName = "Test User";
        String roomType = "STANDARD";
        String checkIn = "2024-01-01";
        String checkOut = "2024-01-02";

        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        // SHA-256 hash is 64 hex characters
        assertEquals(64, confirmCode.length());
    }

    // --- getBookingById tests ---

    @Test
    void getBookingById_withValidBookingId_returnsBookingMap() {
        // Arrange
        String bookingId = "BK-12345678";
        when(jdbcTemplate.queryForMap(anyString(), eq(bookingId)))
                .thenReturn(Map.of("id", bookingId, "guest", "John Smith"));

        // Act
        Map<String, Object> result = bookingService.getBookingById(bookingId);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("id"));
        assertEquals("John Smith", result.get("guest"));
    }

    @Test
    void getBookingById_withNonExistentBookingId_returnsErrorMap() {
        // Arrange
        String bookingId = "BK-NONEXISTENT";
        when(jdbcTemplate.queryForMap(anyString(), eq(bookingId)))
                .thenThrow(new RuntimeException("Not found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById(bookingId);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains("Booking not found"));
    }

    // --- calculateRoomPrice tests ---

    @Test
    void calculateRoomPrice_withStandardRoomNormalSeasonNoLoyalty_returnsCorrectPrice() {
        // Arrange
        String roomType = "STANDARD";
        int nights = 3;
        String season = "NORMAL";
        String loyalty = "NONE";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        // 120.0 * 1.0 * 1.0 * 3 = 360.00
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_withDeluxeRoomPeakSeason_returnsCorrectPrice() {
        // Arrange
        String roomType = "DELUXE";
        int nights = 5;
        String season = "PEAK";
        String loyalty = "NONE";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        // 200.0 * 1.5 * 1.0 * 5 = 1500.00
        assertEquals("1500.00", price);
    }

    @Test
    void calculateRoomPrice_withSuiteRoomOffSeasonGoldLoyalty_returnsCorrectPrice() {
        // Arrange
        String roomType = "SUITE";
        int nights = 4;
        String season = "OFF";
        String loyalty = "GOLD";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        // 350.0 * 0.8 * 0.9 * 4 = 1008.00
        assertEquals("1008.00", price);
    }

    @Test
    void calculateRoomPrice_withVillaRoomPeakSeasonDiamondLoyalty_returnsCorrectPrice() {
        // Arrange
        String roomType = "VILLA";
        int nights = 7;
        String season = "PEAK";
        String loyalty = "DIAMOND";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        // 600.0 * 1.5 * 0.7 = 630.0 base, then 7 nights >= 7 so * 0.95 = 598.5, * 7 = 4189.50
        assertEquals("4189.50", price);
    }

    @Test
    void calculateRoomPrice_withLongStay14NightsAppliesDiscount() {
        // Arrange
        String roomType = "STANDARD";
        int nights = 14;
        String season = "NORMAL";
        String loyalty = "NONE";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        // 120.0 * 1.0 * 1.0 = 120.0, * 0.90 (14 nights) = 108.0, * 14 = 1512.00
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_withLongStay7NightsAppliesDiscount() {
        // Arrange
        String roomType = "STANDARD";
        int nights = 7;
        String season = "NORMAL";
        String loyalty = "NONE";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        // 120.0 * 1.0 * 1.0 = 120.0, * 0.95 (7 nights) = 114.0, * 7 = 798.00
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_withInvalidRoomTypeDefaultsToStandard() {
        // Arrange
        String roomType = "INVALID";
        int nights = 3;
        String season = "NORMAL";
        String loyalty = "NONE";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        // Defaults to STANDARD: 120.0 * 1.0 * 1.0 * 3 = 360.00
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_withInvalidSeasonDefaultsToNormal() {
        // Arrange
        String roomType = "STANDARD";
        int nights = 3;
        String season = "INVALID";
        String loyalty = "NONE";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        // 120.0 * 1.0 * 1.0 * 3 = 360.00
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_withInvalidLoyaltyDefaultsToNone() {
        // Arrange
        String roomType = "STANDARD";
        int nights = 3;
        String season = "NORMAL";
        String loyalty = "INVALID";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        // 120.0 * 1.0 * 1.0 * 3 = 360.00
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_withOneNightReturnsBasePrice() {
        // Arrange
        String roomType = "STANDARD";
        int nights = 1;
        String season = "NORMAL";
        String loyalty = "NONE";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        // 120.0 * 1.0 * 1.0 * 1 = 120.00
        assertEquals("120.00", price);
    }

    // --- isRoomAvailable tests ---

    @Test
    void isRoomAvailable_withValidRoomTypeReturnsTrue() {
        // Arrange
        String roomType = "STANDARD";

        // Act
        boolean result = bookingService.isRoomAvailable(roomType);

        // Assert
        assertTrue(result);
    }

    @Test
    void isRoomAvailable_withDeluxeRoomTypeReturnsTrue() {
        // Arrange
        String roomType = "DELUXE";

        // Act
        boolean result = bookingService.isRoomAvailable(roomType);

        // Assert
        assertTrue(result);
    }

    @Test
    void isRoomAvailable_withSuiteRoomTypeReturnsTrue() {
        // Arrange
        String roomType = "SUITE";

        // Act
        boolean result = bookingService.isRoomAvailable(roomType);

        // Assert
        assertTrue(result);
    }

    @Test
    void isRoomAvailable_withVillaRoomTypeReturnsTrue() {
        // Arrange
        String roomType = "VILLA";

        // Act
        boolean result = bookingService.isRoomAvailable(roomType);

        // Assert
        assertTrue(result);
    }

    @Test
    void isRoomAvailable_withInvalidRoomTypeReturnsFalse() {
        // Arrange
        String roomType = "INVALID";

        // Act
        boolean result = bookingService.isRoomAvailable(roomType);

        // Assert
        assertFalse(result);
    }

    @Test
    void isRoomAvailable_withEmptyStringReturnsFalse() {
        // Arrange
        String roomType = "";

        // Act
        boolean result = bookingService.isRoomAvailable(roomType);

        // Assert
        assertFalse(result);
    }

    // --- generateReport tests ---

    @Test
    void generateReport_withValidMonth_returnsReportString() {
        // Arrange
        String month = "03";

        // Act
        String result = bookingService.generateReport(month);

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("03"));
        assertTrue(result.contains("https://payment-svc.internal/charge"));
    }

    @Test
    void generateReport_withDifferentMonth_returnsReportString() {
        // Arrange
        String month = "12";

        // Act
        String result = bookingService.generateReport(month);

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("12"));
    }
}
