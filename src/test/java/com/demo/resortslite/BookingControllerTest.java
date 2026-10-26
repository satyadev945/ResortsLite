package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("createBooking_validParams_returnsConfirmedStatus")
    void createBooking_validParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-12345678");
        mockBooking.put("guestName", "Alice");
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
    @DisplayName("createBooking_validParams_responseContainsBooking")
    void createBooking_validParams_responseContainsBooking() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCDEF12");
        mockBooking.put("guestName", "Bob");
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
    @DisplayName("createBooking_validParams_sessionAttributesSet")
    void createBooking_validParams_sessionAttributesSet() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-SESSION01");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking(
                "Carol", "STANDARD", "2024-08-01", "2024-08-03", session);

        // Assert – session attributes are set (cr-java-0065 behaviour)
        assertNotNull(session.getAttribute("lastBooking"));
        assertEquals("Carol", session.getAttribute("guestName"));
    }

    @Test
    @DisplayName("createBooking_validParams_bookingServiceCalledOnce")
    void createBooking_validParams_bookingServiceCalledOnce() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ONCE0001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-07", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Dave", "VILLA", "2024-09-01", "2024-09-07");
    }

    @Test
    @DisplayName("createBooking_bookingCachedByBookingId")
    void createBooking_bookingCachedByBookingId() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-CACHE001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-05", session);

        // Assert – booking is returned in response (cache is internal static map)
        assertNotNull(response.get("booking"));
    }

    // -----------------------------------------------------------------------
    // getBookingStatus
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getBookingStatus_existingBookingId_returnsResultWithBookingId")
    void getBookingStatus_existingBookingId_returnsResultWithBookingId() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-STATUS01");
        when(bookingService.getBookingById("BK-STATUS01")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS01", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-STATUS01", result.get("bookingId"));
    }

    @Test
    @DisplayName("getBookingStatus_existingBookingId_containsDetailsKey")
    void getBookingStatus_existingBookingId_containsDetailsKey() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-DETAIL01");
        when(bookingService.getBookingById("BK-DETAIL01")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-DETAIL01", session);

        // Assert
        assertTrue(result.containsKey("details"));
    }

    @Test
    @DisplayName("getBookingStatus_sessionHasGuestName_sessionGuestReturned")
    void getBookingStatus_sessionHasGuestName_sessionGuestReturned() {
        // Arrange
        session.setAttribute("guestName", "Frank");
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-GUEST01", session);

        // Assert
        assertEquals("Frank", result.get("sessionGuest"));
    }

    @Test
    @DisplayName("getBookingStatus_noSessionGuest_sessionGuestIsNull")
    void getBookingStatus_noSessionGuest_sessionGuestIsNull() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-NOGUEST", session);

        // Assert
        assertNull(result.get("sessionGuest"));
    }

    @Test
    @DisplayName("getBookingStatus_bookingServiceCalledWithCorrectId")
    void getBookingStatus_bookingServiceCalledWithCorrectId() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById("BK-VERIFY01")).thenReturn(mockDetails);

        // Act
        bookingController.getBookingStatus("BK-VERIFY01", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-VERIFY01");
    }

    // -----------------------------------------------------------------------
    // checkAvailability
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("checkAvailability_standardRoom_returnsRoomTypeInResponse")
    void checkAvailability_standardRoom_returnsRoomTypeInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertNotNull(response);
        assertEquals("STANDARD", response.get("roomType"));
    }

    @Test
    @DisplayName("checkAvailability_availableRoom_returnsTrue")
    void checkAvailability_availableRoom_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals(true, response.get("available"));
    }

    @Test
    @DisplayName("checkAvailability_unavailableRoom_returnsFalse")
    void checkAvailability_unavailableRoom_returnsFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, response.get("available"));
    }

    @Test
    @DisplayName("checkAvailability_containsInventoryEndpoint")
    void checkAvailability_containsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertTrue(response.containsKey("inventoryEndpoint"));
        assertNotNull(response.get("inventoryEndpoint"));
    }

    @Test
    @DisplayName("checkAvailability_inventoryEndpointContainsInventoryService")
    void checkAvailability_inventoryEndpointContainsInventoryService() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("VILLA");

        // Assert
        String endpoint = (String) response.get("inventoryEndpoint");
        assertTrue(endpoint.contains("inventory-service"),
                "Endpoint should reference inventory-service");
    }

    // -----------------------------------------------------------------------
    // downloadReport
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("downloadReport_validMonth_returnsReportPathInResponse")
    void downloadReport_validMonth_returnsReportPathInResponse() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        // Act
        Map<String, Object> response = bookingController.downloadReport("March");

        // Assert
        assertNotNull(response);
        assertTrue(response.containsKey("reportPath"));
    }

    @Test
    @DisplayName("downloadReport_validMonth_reportPathContainsMonth")
    void downloadReport_validMonth_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("April")).thenReturn("Report generated for April");

        // Act
        Map<String, Object> response = bookingController.downloadReport("April");

        // Assert
        String path = (String) response.get("reportPath");
        assertTrue(path.contains("April"), "Report path should contain the month");
    }

    @Test
    @DisplayName("downloadReport_validMonth_containsMessageKey")
    void downloadReport_validMonth_containsMessageKey() {
        // Arrange
        when(bookingService.generateReport("May")).thenReturn("Report generated for May");

        // Act
        Map<String, Object> response = bookingController.downloadReport("May");

        // Assert
        assertTrue(response.containsKey("message"));
        assertEquals("Report generated for May", response.get("message"));
    }

    @Test
    @DisplayName("downloadReport_reportPathEndsWithPdf")
    void downloadReport_reportPathEndsWithPdf() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("ok");

        // Act
        Map<String, Object> response = bookingController.downloadReport("June");

        // Assert
        String path = (String) response.get("reportPath");
        assertTrue(path.endsWith(".pdf"), "Report path should end with .pdf");
    }

    @Test
    @DisplayName("downloadReport_generateReportCalledOnce")
    void downloadReport_generateReportCalledOnce() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("ok");

        // Act
        bookingController.downloadReport("July");

        // Assert
        verify(bookingService, times(1)).generateReport("July");
    }
}
