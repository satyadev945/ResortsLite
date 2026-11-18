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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
        service = new BookingService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void createBooking_returnsPopulatedBookingAndInserts() {
        Map<String, Object> booking = service.createBooking("John", "SUITE", "2024-01-01", "2024-01-05");

        assertEquals("John", booking.get("guestName"));
        assertEquals("SUITE", booking.get("roomType"));
        assertEquals("2024-01-01", booking.get("checkIn"));
        assertEquals("2024-01-05", booking.get("checkOut"));
        assertEquals("db-prod.resorts-internal.com", booking.get("dbHost"));
        assertNotNull(UUID.fromString((String) booking.get("bookingId")));
        String code = (String) booking.get("confirmationCode");
        assertNotNull(code);
        assertEquals(32, code.length());
        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Test
    void createBooking_generatesUniqueIds() {
        Map<String, Object> a = service.createBooking("A", "DELUXE", "x", "y");
        Map<String, Object> b = service.createBooking("A", "DELUXE", "x", "y");
        assertNotEquals(a.get("bookingId"), b.get("bookingId"));
    }

    @Test
    void getBookingById_found_returnsRow() {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("guest", "Jane");
        when(jdbcTemplate.queryForMap(anyString(), eq(id))).thenReturn(row);

        Map<String, Object> result = service.getBookingById(id.toString());

        assertEquals("Jane", result.get("guest"));
    }

    @Test
    void getBookingById_invalidUuid_returnsError() {
        Map<String, Object> result = service.getBookingById("not-a-uuid");
        assertEquals("Booking not found: not-a-uuid", result.get("error"));
    }

    @Test
    void getBookingById_dbException_returnsError() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.queryForMap(anyString(), eq(id))).thenThrow(new RuntimeException("boom"));

        Map<String, Object> result = service.getBookingById(id.toString());

        assertTrue(result.containsKey("error"));
    }

    @Test
    void calculateRoomPrice_standardNoDiscount() {
        assertEquals("120.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_allRoomTypes() {
        assertEquals("200.00", service.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE"));
        assertEquals("350.00", service.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE"));
        assertEquals("600.00", service.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE"));
        assertEquals("120.00", service.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_seasons() {
        assertEquals("180.00", service.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE"));
        assertEquals("96.00", service.calculateRoomPrice("STANDARD", 1, "OFF", "NONE"));
    }

    @Test
    void calculateRoomPrice_loyalty() {
        assertEquals("108.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD"));
        assertEquals("96.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM"));
        assertEquals("84.00", service.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_longStayDiscount() {
        // 120 * 0.95 * 7 = 798.00
        assertEquals("798.00", service.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE"));
        assertEquals("1596.00", service.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_zeroNights() {
        assertEquals("0.00", service.calculateRoomPrice("STANDARD", 0, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_nullArgs_throwNpe() {
        assertThrows(NullPointerException.class, () -> service.calculateRoomPrice(null, 1, "PEAK", "GOLD"));
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
        assertFalse(service.isRoomAvailable("PENTHOUSE"));
        assertFalse(service.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_null_throwsNpe() {
        assertThrows(NullPointerException.class, () -> service.isRoomAvailable(null));
    }

    @Test
    void generateReport_containsMonthAndEndpoint() {
        String msg = service.generateReport("MARCH");
        assertTrue(msg.startsWith("Report generation triggered for: MARCH"));
        assertTrue(msg.contains("http://10.0.1.45:9090/payments/charge"));
    }

    @Test
    void md5Hash_privateMethod_knownVector() throws Exception {
        var m = BookingService.class.getDeclaredMethod("md5Hash", String.class);
        m.setAccessible(true);
        assertEquals("5d41402abc4b2a76b9719d911017c592", m.invoke(service, "hello"));
    }

    @Test
    void md5Hash_nullInput_returnsInput() throws Exception {
        var m = BookingService.class.getDeclaredMethod("md5Hash", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(service, new Object[]{null}));
    }
}
