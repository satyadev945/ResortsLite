package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
    }

    @Test
    void testCreateBooking_withValidData_returnsConfirmedBooking() {
        // Arrange
        String guestName = "John Doe";
        String roomType = "DELUXE";
        String checkIn = "2024-03-01";
        String checkOut = "2024-03-05";

        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-12345678");
        mockBooking.put("guestName", guestName);
        mockBooking.put("roomType", roomType);

        when(bookingService.createBooking(guestName, roomType, checkIn, checkOut))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                guestName, roomType, checkIn, checkOut, session);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
        assertNotNull(response.get("booking"));
        assertEquals("BK-12345678", ((Map<?, ?>) response.get("booking")).get("bookingId"));
        verify(bookingService, times(1)).createBooking(guestName, roomType, checkIn, checkOut);
    }

    @Test
    void testCreateBooking_withEmptyGuestName_stillProcesses() {
        // Arrange
        String guestName = "";
        String roomType = "STANDARD";
        String checkIn = "2024-04-01";
        String checkOut = "2024-04-03";

        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-87654321");
        mockBooking.put("guestName", guestName);

        when(bookingService.createBooking(guestName, roomType, checkIn, checkOut))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                guestName, roomType, checkIn, checkOut, session);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
        verify(bookingService, times(1)).createBooking(guestName, roomType, checkIn, checkOut);
    }

    @Test
    void testCreateBooking_storesDataInSession() {
        // Arrange
        String guestName = "Jane Smith";
        String roomType = "SUITE";
        String checkIn = "2024-05-01";
        String checkOut = "2024-05-10";

        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-11111111");
        mockBooking.put("guestName", guestName);

        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking(guestName, roomType, checkIn, checkOut, session);

        // Assert
        assertEquals(mockBooking, session.getAttribute("lastBooking"));
        assertEquals(guestName, session.getAttribute("guestName"));
    }

    @Test
    void testGetBookingStatus_withValidBookingId_returnsStatus() {
        // Arrange
        String bookingId = "BK-99999999";
        String guestName = "Alice Johnson";
        session.setAttribute("guestName", guestName);

        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("bookingId", bookingId);
        mockDetails.put("status", "confirmed");

        when(bookingService.getBookingById(bookingId)).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus(bookingId, session);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("bookingId"));
        assertEquals(guestName, result.get("sessionGuest"));
        assertNotNull(result.get("details"));
        verify(bookingService, times(1)).getBookingById(bookingId);
    }

    @Test
    void testGetBookingStatus_withNoSessionData_returnsNullGuest() {
        // Arrange
        String bookingId = "BK-55555555";
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("bookingId", bookingId);

        when(bookingService.getBookingById(bookingId)).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus(bookingId, session);

        // Assert
        assertNotNull(result);
        assertNull(result.get("sessionGuest"));
        assertEquals(bookingId, result.get("bookingId"));
    }

    @Test
    void testCheckAvailability_withStandardRoom_returnsAvailability() {
        // Arrange
        String roomType = "STANDARD";
        when(bookingService.isRoomAvailable(roomType)).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability(roomType);

        // Assert
        assertNotNull(response);
        assertEquals(roomType, response.get("roomType"));
        assertTrue((Boolean) response.get("available"));
        assertNotNull(response.get("inventoryEndpoint"));
        verify(bookingService, times(1)).isRoomAvailable(roomType);
    }

    @Test
    void testCheckAvailability_withDeluxeRoom_returnsAvailability() {
        // Arrange
        String roomType = "DELUXE";
        when(bookingService.isRoomAvailable(roomType)).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability(roomType);

        // Assert
        assertNotNull(response);
        assertEquals(roomType, response.get("roomType"));
        assertFalse((Boolean) response.get("available"));
    }

    @Test
    void testCheckAvailability_withInvalidRoom_returnsUnavailable() {
        // Arrange
        String roomType = "INVALID";
        when(bookingService.isRoomAvailable(roomType)).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability(roomType);

        // Assert
        assertNotNull(response);
        assertEquals(roomType, response.get("roomType"));
        assertFalse((Boolean) response.get("available"));
    }

    @Test
    void testDownloadReport_withValidMonth_returnsReportPath() {
        // Arrange
        String month = "March";
        String expectedMessage = "Report generated successfully";
        when(bookingService.generateReport(month)).thenReturn(expectedMessage);

        // Act
        Map<String, Object> response = bookingController.downloadReport(month);

        // Assert
        assertNotNull(response);
        assertTrue(((String) response.get("reportPath")).contains(month));
        assertEquals(expectedMessage, response.get("message"));
        verify(bookingService, times(1)).generateReport(month);
    }

    @Test
    void testDownloadReport_withDifferentMonths_generatesCorrectPaths() {
        // Arrange
        String month1 = "January";
        String month2 = "December";
        when(bookingService.generateReport(anyString())).thenReturn("Generated");

        // Act
        Map<String, Object> response1 = bookingController.downloadReport(month1);
        Map<String, Object> response2 = bookingController.downloadReport(month2);

        // Assert
        assertTrue(((String) response1.get("reportPath")).contains(month1));
        assertTrue(((String) response2.get("reportPath")).contains(month2));
        verify(bookingService, times(2)).generateReport(anyString());
    }

    @Test
    void testCreateBooking_withNullValues_handlesGracefully() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-NULL001");

        when(bookingService.createBooking(isNull(), isNull(), isNull(), isNull()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                null, null, null, null, session);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void testGetBookingStatus_withEmptyBookingId_callsService() {
        // Arrange
        String bookingId = "";
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("error", "Booking not found");

        when(bookingService.getBookingById(bookingId)).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus(bookingId, session);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("bookingId"));
        verify(bookingService, times(1)).getBookingById(bookingId);
    }

    @Test
    void testCheckAvailability_withEmptyRoomType_returnsResponse() {
        // Arrange
        String roomType = "";
        when(bookingService.isRoomAvailable(roomType)).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability(roomType);

        // Assert
        assertNotNull(response);
        assertEquals(roomType, response.get("roomType"));
        assertFalse((Boolean) response.get("available"));
    }
}
