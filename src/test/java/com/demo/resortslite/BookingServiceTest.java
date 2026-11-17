package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService service;

    @BeforeEach
    void setUp() {
        // mocks injected by Mockito extension
    }

    @Test
    void createBooking_returnsPopulatedMapAndExecutesSql() {
        Map<String, Object> b = service.createBooking("Alice", "SUITE", "2024-01-01", "2024-01-05");

        assertTrue(((String) b.get("bookingId")).startsWith("BK-"));
        assertEquals(11, ((String) b.get("bookingId")).length());
        assertNotNull(b.get("bookingUuid"));
        assertEquals("Alice", b.get("guestName"));
        assertEquals("SUITE", b.get("roomType"));
        assertEquals("2024-01-01", b.get("checkIn"));
        assertEquals("2024-01-05", b.get("checkOut"));
        assertEquals("db-prod.resorts-internal.com", b.get("dbHost"));
        assertEquals(64, ((String) b.get("confirmationCode")).length());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(captor.capture());
        assertTrue(captor.getValue().contains("INSERT INTO bookings"));
        assertTrue(captor.getValue().contains("Alice"));
    }

    @Test
    void createBooking_generatesUniqueIds() {
        Map<String, Object> a = service.createBooking("A", "SUITE", "x", "y");
        Map<String, Object> b = service.createBooking("A", "SUITE", "x", "y");
        assertNotEquals(a.get("bookingId"), b.get("bookingId"));
    }

    @Test
    void parseUuid_validString_returnsUuid() {
        UUID u = UUID.randomUUID();
        assertEquals(u, service.parseUuid(u.toString()));
    }

    @Test
    void parseUuid_invalidString_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.parseUuid("not-a-uuid"));
    }

    @Test
    void getBookingByUuid_delegatesToJdbcTemplate() {
        UUID u = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("id", "BK-1");
        when(jdbcTemplate.queryForMap(anyString(), eq(u.toString()))).thenReturn(row);

        assertEquals(row, service.getBookingByUuid(u));
    }

    @Test
    void getBookingById_found_returnsRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "BK-1");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(row);

        Map<String, Object> result = service.getBookingById("BK-1");
        assertEquals("BK-1", result.get("id"));
    }

    @Test
    void getBookingById_exception_returnsErrorMap() {
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new RuntimeException("boom"));

        Map<String, Object> result = service.getBookingById("BK-9");
        assertEquals("Booking not found: BK-9", result.get("error"));
    }

    @Test
    void calculateRoomPrice_standardNormalNoLoyalty() {
        assertEquals("240.00", service.calculateRoomPrice("STANDARD", 2, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_deluxe() {
        assertEquals("400.00", service.calculateRoomPrice("DELUXE", 2, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_suite() {
        assertEquals("700.00", service.calculateRoomPrice("SUITE", 2, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_villa() {
        assertEquals("1200.00", service.calculateRoomPrice("VILLA", 2, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_unknownRoomDefaultsToStandard() {
        assertEquals("240.00", service.calculateRoomPrice("OTHER", 2, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_peakSeason() {
        assertEquals("360.00", service.calculateRoomPrice("STANDARD", 2, "PEAK", "NONE"));
    }

    @Test
    void calculateRoomPrice_offSeason() {
        assertEquals("192.00", service.calculateRoomPrice("STANDARD", 2, "OFF", "NONE"));
    }

    @Test
    void calculateRoomPrice_goldLoyalty() {
        assertEquals("216.00", service.calculateRoomPrice("STANDARD", 2, "NORMAL", "GOLD"));
    }

    @Test
    void calculateRoomPrice_platinumLoyalty() {
        assertEquals("192.00", service.calculateRoomPrice("STANDARD", 2, "NORMAL", "PLATINUM"));
    }

    @Test
    void calculateRoomPrice_diamondLoyalty() {
        assertEquals("168.00", service.calculateRoomPrice("STANDARD", 2, "NORMAL", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_sevenNightsDiscount() {
        assertEquals("798.00", service.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_fourteenNightsStillSevenNightDiscount() {
        // the >= 14 branch is unreachable because >= 7 matches first
        assertEquals("1596.00", service.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_zeroNights() {
        assertEquals("0.00", service.calculateRoomPrice("STANDARD", 0, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_nullRoomType_throwsNpe() {
        assertThrows(NullPointerException.class, () -> service.calculateRoomPrice(null, 1, "PEAK", "GOLD"));
    }

    @Test
    void isRoomAvailable_validTypes_true() {
        assertTrue(service.isRoomAvailable("STANDARD"));
        assertTrue(service.isRoomAvailable("DELUXE"));
        assertTrue(service.isRoomAvailable("SUITE"));
        assertTrue(service.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_invalidType_false() {
        assertFalse(service.isRoomAvailable("CABIN"));
        assertFalse(service.isRoomAvailable(""));
    }

    @Test
    void generateReport_containsMonthAndEndpoint() {
        String r = service.generateReport("MAR");
        assertTrue(r.startsWith("Report generation triggered for: MAR via "));
        assertTrue(r.contains("http://10.0.1.45:9090/payments/charge"));
    }
}
