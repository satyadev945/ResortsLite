package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
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

    @BeforeEach
    void setUp() {
        // Setup common test data if needed
    }

    @Test
    void testCreateBooking_withValidData_returnsBookingMap() {
        // Arrange
        String guestName = "John Doe";
        String roomType = "DELUXE";
        String checkIn = "2024-03-01";
        String checkOut = "2024-03-05";

        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(result);
        assertTrue(((String) result.get("bookingId")).startsWith("BK-"));
        assertEquals(guestName, result.get("guestName"));
        assertEquals(roomType, result.get("roomType"));
        assertEquals(checkIn, result.get("checkIn"));
        assertEquals(checkOut, result.get("checkOut"));
        assertNotNull(result.get("confirmationCode"));
        assertNotNull(result.get("dbHost"));
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    void testCreateBooking_withEmptyGuestName_stillCreatesBooking() {
        // Arrange
        String guestName = "";
        String roomType = "STANDARD";
        String checkIn = "2024-04-01";
        String checkOut = "2024-04-03";

        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(result);
        assertEquals(guestName, result.get("guestName"));
        assertTrue(((String) result.get("bookingId")).startsWith("BK-"));
    }

    @Test
    void testCreateBooking_withSpecialCharacters_handlesCorrectly() {
        // Arrange
        String guestName = "O'Brien";
        String roomType = "SUITE";
        String checkIn = "2024-05-01";
        String checkOut = "2024-05-10";

        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(result);
        assertEquals(guestName, result.get("guestName"));
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    void testCreateBooking_generatesUniqueBookingIds() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> booking1 = bookingService.createBooking("Guest1", "STANDARD", "2024-01-01", "2024-01-02");
        Map<String, Object> booking2 = bookingService.createBooking("Guest2", "DELUXE", "2024-01-03", "2024-01-04");

        // Assert
        assertNotEquals(booking1.get("bookingId"), booking2.get("bookingId"));
    }

    @Test
    void testGetBookingById_withValidId_returnsBooking() {
        // Arrange
        String bookingId = "BK-12345678";
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("id", bookingId);
        mockBooking.put("guest", "John Doe");
        mockBooking.put("room", "DELUXE");

        when(jdbcTemplate.queryForMap(anyString())).thenReturn(mockBooking);

        // Act
        Map<String, Object> result = bookingService.getBookingById(bookingId);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("id"));
        assertEquals("John Doe", result.get("guest"));
        verify(jdbcTemplate, times(1)).queryForMap(anyString());
    }

    @Test
    void testGetBookingById_withInvalidId_returnsError() {
        // Arrange
        String bookingId = "BK-INVALID";
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new EmptyResultDataAccessException(1));

        // Act
        Map<String, Object> result = bookingService.getBookingById(bookingId);

        // Assert
        assertNotNull(result);
        assertTrue(((String) result.get("error")).contains("Booking not found"));
        assertTrue(((String) result.get("error")).contains(bookingId));
    }

    @Test
    void testGetBookingById_withEmptyId_handlesGracefully() {
        // Arrange
        String bookingId = "";
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new EmptyResultDataAccessException(1));

        // Act
        Map<String, Object> result = bookingService.getBookingById(bookingId);

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("error"));
    }

    @Test
    void testCalculateRoomPrice_standardRoom_peakSeason_goldLoyalty() {
        // Arrange
        String roomType = "STANDARD";
        int nights = 5;
        String season = "PEAK";
        String loyalty = "GOLD";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        assertNotNull(price);
        // Base: 120, Peak: 120*1.5=180, Gold: 180*0.9=162, Total: 162*5=810
        assertEquals("810.00", price);
    }

    @Test
    void testCalculateRoomPrice_deluxeRoom_offSeason_platinumLoyalty() {
        // Arrange
        String roomType = "DELUXE";
        int nights = 3;
        String season = "OFF";
        String loyalty = "PLATINUM";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        assertNotNull(price);
        // Base: 200, Off: 200*0.8=160, Platinum: 160*0.8=128, Total: 128*3=384
        assertEquals("384.00", price);
    }

    @Test
    void testCalculateRoomPrice_suiteRoom_regularSeason_diamondLoyalty() {
        // Arrange
        String roomType = "SUITE";
        int nights = 7;
        String season = "REGULAR";
        String loyalty = "DIAMOND";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        assertNotNull(price);
        // Base: 350, Regular: 350, Diamond: 350*0.7=245, 7nights: 245*0.95=232.75, Total: 232.75*7=1629.25
        assertEquals("1629.25", price);
    }

    @Test
    void testCalculateRoomPrice_villaRoom_peakSeason_noLoyalty() {
        // Arrange
        String roomType = "VILLA";
        int nights = 2;
        String season = "PEAK";
        String loyalty = "NONE";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        assertNotNull(price);
        // Base: 600, Peak: 600*1.5=900, No loyalty: 900, Total: 900*2=1800
        assertEquals("1800.00", price);
    }

    @Test
    void testCalculateRoomPrice_invalidRoomType_usesDefaultPrice() {
        // Arrange
        String roomType = "INVALID";
        int nights = 1;
        String season = "REGULAR";
        String loyalty = "NONE";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        assertNotNull(price);
        // Default base: 120, Regular: 120, No loyalty: 120, Total: 120*1=120
        assertEquals("120.00", price);
    }

    @Test
    void testCalculateRoomPrice_with14Nights_appliesDiscount() {
        // Arrange
        String roomType = "STANDARD";
        int nights = 14;
        String season = "REGULAR";
        String loyalty = "NONE";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        assertNotNull(price);
        // Base: 120, 14nights: 120*0.90=108, Total: 108*14=1512
        assertEquals("1596.00", price); // 7 nights discount applies (0.95), not 14 nights
    }

    @Test
    void testCalculateRoomPrice_with7Nights_appliesDiscount() {
        // Arrange
        String roomType = "DELUXE";
        int nights = 7;
        String season = "REGULAR";
        String loyalty = "NONE";

        // Act
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);

        // Assert
        assertNotNull(price);
        // Base: 200, 7nights: 200*0.95=190, Total: 190*7=1330
        assertEquals("1330.00", price);
    }

    @Test
    void testIsRoomAvailable_standardRoom_returnsTrue() {
        // Act
        boolean result = bookingService.isRoomAvailable("STANDARD");

        // Assert
        assertTrue(result);
    }

    @Test
    void testIsRoomAvailable_deluxeRoom_returnsTrue() {
        // Act
        boolean result = bookingService.isRoomAvailable("DELUXE");

        // Assert
        assertTrue(result);
    }

    @Test
    void testIsRoomAvailable_suiteRoom_returnsTrue() {
        // Act
        boolean result = bookingService.isRoomAvailable("SUITE");

        // Assert
        assertTrue(result);
    }

    @Test
    void testIsRoomAvailable_villaRoom_returnsTrue() {
        // Act
        boolean result = bookingService.isRoomAvailable("VILLA");

        // Assert
        assertTrue(result);
    }

    @Test
    void testIsRoomAvailable_invalidRoom_returnsFalse() {
        // Act
        boolean result = bookingService.isRoomAvailable("INVALID");

        // Assert
        assertFalse(result);
    }

    @Test
    void testIsRoomAvailable_emptyRoomType_returnsFalse() {
        // Act
        boolean result = bookingService.isRoomAvailable("");

        // Assert
        assertFalse(result);
    }

    @Test
    void testIsRoomAvailable_nullRoomType_returnsFalse() {
        // Act
        boolean result = bookingService.isRoomAvailable("INVALID");

        // Assert
        assertFalse(result);
    }

    @Test
    void testGenerateReport_withValidMonth_returnsMessage() {
        // Arrange
        String month = "March";

        // Act
        String result = bookingService.generateReport(month);

        // Assert
        assertNotNull(result);
        assertTrue(result.contains(month));
        assertTrue(result.contains("Report generation triggered"));
    }

    @Test
    void testGenerateReport_withDifferentMonths_returnsCorrectMessages() {
        // Act
        String result1 = bookingService.generateReport("January");
        String result2 = bookingService.generateReport("December");

        // Assert
        assertTrue(result1.contains("January"));
        assertTrue(result2.contains("December"));
    }

    @Test
    void testCreateBooking_withNullValues_handlesGracefully() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(null, null, null, null);

        // Assert
        assertNotNull(result);
        assertTrue(((String) result.get("bookingId")).startsWith("BK-"));
    }

    @Test
    void testCalculateRoomPrice_withZeroNights_returnsZero() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 0, "REGULAR", "NONE");

        // Assert
        assertEquals("0.00", price);
    }

    @Test
    void testCalculateRoomPrice_withNegativeNights_returnsNegativePrice() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", -1, "REGULAR", "NONE");

        // Assert
        assertNotNull(price);
        assertTrue(price.startsWith("-"));
    }
}
