package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService service;

    @BeforeEach
    void setUp() {
        assertNotNull(service);
    }

    @Test
    void createBooking_insertsRowAndReturnsBooking() {
        Map<String, Object> b = service.createBooking("John", "SUITE", "2024-03-01", "2024-03-05");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sql.capture());
        assertTrue(sql.getValue().startsWith("INSERT INTO bookings"));
        assertTrue(sql.getValue().contains("John"));

        String id = (String) b.get("bookingId");
        assertTrue(id.startsWith("BK-"));
        assertEquals(11, id.length());
        assertEquals("John", b.get("guestName"));
        assertEquals("SUITE", b.get("roomType"));
        assertEquals("2024-03-01", b.get("checkIn"));
        assertEquals("2024-03-05", b.get("checkOut"));
        assertEquals("db-prod.resorts-internal.com", b.get("dbHost"));
        assertEquals(64, ((String) b.get("confirmationCode")).length());
    }

    @Test
    void createBooking_generatesUniqueIds() {
        Map<String, Object> a = service.createBooking("A", "VILLA", "x", "y");
        Map<String, Object> b = service.createBooking("A", "VILLA", "x", "y");
        assertNotEquals(a.get("bookingId"), b.get("bookingId"));
    }

    @Test
    void getBookingById_returnsRow() {
        Map<String, Object> row = Map.of("id", "BK-1");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(row);
        assertEquals(row, service.getBookingById("BK-1"));
    }

    @Test
    void getBookingById_onException_returnsError() {
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new RuntimeException("boom"));
        Map<String, Object> r = service.getBookingById("BK-9");
        assertEquals("Booking not found: BK-9", r.get("error"));
    }

    @Test
    void getBookingByUuid_returnsRow() {
        UUID u = UUID.randomUUID();
        Map<String, Object> row = Map.of("booking_uuid", u);
        when(jdbcTemplate.queryForMap(anyString(), eq(u))).thenReturn(row);
        assertEquals(row, service.getBookingByUuid(u));
    }

    @Test
    void getBookingByUuid_onException_returnsError() {
        UUID u = UUID.randomUUID();
        when(jdbcTemplate.queryForMap(anyString(), eq(u))).thenThrow(new RuntimeException("x"));
        assertEquals("Booking not found: " + u, service.getBookingByUuid(u).get("error"));
    }

    @Test
    void calculateRoomPrice_standardBase() {
        assertEquals("120.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_deluxeBase() {
        assertEquals("400.00", service.calculateRoomPrice("DELUXE", 2, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_suiteBase() {
        assertEquals("350.00", service.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_villaBase() {
        assertEquals("600.00", service.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_unknownRoomDefaultsToStandard() {
        assertEquals("120.00", service.calculateRoomPrice("OTHER", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_peakSeason() {
        assertEquals("180.00", service.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE"));
    }

    @Test
    void calculateRoomPrice_offSeason() {
        assertEquals("96.00", service.calculateRoomPrice("STANDARD", 1, "OFF", "NONE"));
    }

    @Test
    void calculateRoomPrice_goldLoyalty() {
        assertEquals("108.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD"));
    }

    @Test
    void calculateRoomPrice_platinumLoyalty() {
        assertEquals("96.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM"));
    }

    @Test
    void calculateRoomPrice_diamondLoyalty() {
        assertEquals("84.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_weekLongDiscount() {
        // 120 * 0.95 * 7 = 798.00
        assertEquals("798.00", service.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_zeroNights() {
        assertEquals("0.00", service.calculateRoomPrice("STANDARD", 0, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_nullRoomType_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> service.calculateRoomPrice(null, 1, "NORMAL", "NONE"));
    }

    @Test
    void isRoomAvailable_validTypes() {
        assertTrue(service.isRoomAvailable("STANDARD"));
        assertTrue(service.isRoomAvailable("DELUXE"));
        assertTrue(service.isRoomAvailable("SUITE"));
        assertTrue(service.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_invalidType() {
        assertFalse(service.isRoomAvailable("CABIN"));
        assertFalse(service.isRoomAvailable(""));
    }

    @Test
    void generateReport_includesMonthAndEndpoint() {
        String r = service.generateReport("MAR");
        assertTrue(r.contains("MAR"));
        assertTrue(r.contains("http://10.0.1.45:9090/payments/charge"));
    }
}
