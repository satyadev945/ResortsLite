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

/**
 * Comprehensive unit tests for {@link BookingController}.
 * Covers createBooking, getBookingStatus, checkAvailability, and downloadReport endpoints.
 */
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

    // -----------------------------------------------------------------------
    // createBooking
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Alice");
        mockBooking.put("roomType", "SUITE");
        mockBooking.put("checkIn", "2024-06-01");
        mockBooking.put("checkOut", "2024-06-05");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_responseContainsBookingObject() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-TEST0001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        // Assert
        assertTrue(response.containsKey("booking"));
        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_storesLastBookingInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-SESSION01");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Carol", "STANDARD", "2024-08-01", "2024-08-03", session);

        // Assert
        assertNotNull(session.getAttribute("lastBooking"));
    }

    @Test
    void createBooking_storesGuestNameInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-SESSION02");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Dave", "VILLA", "2024-09-01", "2024-09-05", session);

        // Assert
        assertEquals("Dave", session.getAttribute("guestName"));
    }

    @Test
    void createBooking_callsBookingServiceCreateBooking() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-VERIFY01");
        when(bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-07"))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-07", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Eve", "SUITE", "2024-10-01", "2024-10-07");
    }

    @Test
    void createBooking_bookingStoredInCache() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-CACHE001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-04", session);

        // Assert — booking object in response should match the mock
        @SuppressWarnings("unchecked")
        Map<String, Object> bookingInResponse = (Map<String, Object>) response.get("booking");
        assertEquals("BK-CACHE001", bookingInResponse.get("bookingId"));
    }

    // -----------------------------------------------------------------------
    // getBookingStatus
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_returnsMapWithBookingId() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        details.put("id", "BK-STATUS01");
        when(bookingService.getBookingById("BK-STATUS01")).thenReturn(details);
        session.setAttribute("guestName", "Grace");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS01", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-STATUS01", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_returnsSessionGuestName() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(details);
        session.setAttribute("guestName", "Hank");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS02", session);

        // Assert
        assertEquals("Hank", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_sessionGuestIsNullWhenNotSet() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(details);
        // No guestName set in session

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS03", session);

        // Assert
        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_containsDetailsKey() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        details.put("guest", "Ivy");
        when(bookingService.getBookingById("BK-DETAIL01")).thenReturn(details);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-DETAIL01", session);

        // Assert
        assertTrue(result.containsKey("details"));
    }

    @Test
    void getBookingStatus_callsBookingServiceGetBookingById() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        when(bookingService.getBookingById("BK-VERIFY02")).thenReturn(details);

        // Act
        bookingController.getBookingStatus("BK-VERIFY02", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-VERIFY02");
    }

    // -----------------------------------------------------------------------
    // checkAvailability
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_availableRoom_returnsAvailableTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(result);
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_unavailableRoom_returnsAvailableFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_responseContainsRoomType() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("DELUXE", result.get("roomType"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(result.containsKey("inventoryEndpoint"));
        assertNotNull(result.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_callsBookingServiceIsRoomAvailable() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        bookingController.checkAvailability("VILLA");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    // -----------------------------------------------------------------------
    // downloadReport
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_returnsMapWithReportPath() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generation triggered for: March");

        // Act
        Map<String, Object> result = bookingController.downloadReport("March");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("reportPath"));
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Report generation triggered for: June");

        // Act
        Map<String, Object> result = bookingController.downloadReport("June");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.contains("June"), "Report path should contain the month");
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        when(bookingService.generateReport("December"))
                .thenReturn("Report generation triggered for: December");

        // Act
        Map<String, Object> result = bookingController.downloadReport("December");

        // Assert
        assertTrue(result.containsKey("message"));
        assertEquals("Report generation triggered for: December", result.get("message"));
    }

    @Test
    void downloadReport_callsBookingServiceGenerateReport() {
        // Arrange
        when(bookingService.generateReport("January"))
                .thenReturn("Report generation triggered for: January");

        // Act
        bookingController.downloadReport("January");

        // Assert
        verify(bookingService, times(1)).generateReport("January");
    }

    @Test
    void downloadReport_reportPathEndsWithPdf() {
        // Arrange
        when(bookingService.generateReport("April"))
                .thenReturn("Report generation triggered for: April");

        // Act
        Map<String, Object> result = bookingController.downloadReport("April");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.endsWith(".pdf"), "Report path should end with .pdf");
    }
}
