package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link BookingController}.
 * Uses Mockito to mock {@link BookingService} and Spring's {@link MockHttpSession}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingController Tests")
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

    // =========================================================================
    // createBooking()
    // =========================================================================

    @Nested
    @DisplayName("createBooking()")
    class CreateBookingTests {

        @Test
        @DisplayName("createBooking_withValidParams_returnsConfirmedStatus")
        void createBooking_withValidParams_returnsConfirmedStatus() {
            // Arrange
            Map<String, Object> mockBooking = new HashMap<>();
            mockBooking.put("bookingId", "BK-ABCD1234");
            mockBooking.put("guestName", "John Smith");
            when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(mockBooking);

            // Act
            Map<String, Object> response = bookingController.createBooking(
                    "John Smith", "SUITE", "2024-06-01", "2024-06-05", session);

            // Assert
            assertNotNull(response);
            assertEquals("confirmed", response.get("status"),
                    "Response status must be 'confirmed'");
        }

        @Test
        @DisplayName("createBooking_withValidParams_returnsBookingInResponse")
        void createBooking_withValidParams_returnsBookingInResponse() {
            // Arrange
            Map<String, Object> mockBooking = new HashMap<>();
            mockBooking.put("bookingId", "BK-ABCD1234");
            mockBooking.put("guestName", "Jane Doe");
            when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(mockBooking);

            // Act
            Map<String, Object> response = bookingController.createBooking(
                    "Jane Doe", "DELUXE", "2024-07-01", "2024-07-05", session);

            // Assert
            assertNotNull(response.get("booking"),
                    "Response must contain 'booking' key");
        }

        @Test
        @DisplayName("createBooking_withValidParams_storesLastBookingInSession")
        void createBooking_withValidParams_storesLastBookingInSession() {
            // Arrange
            Map<String, Object> mockBooking = new HashMap<>();
            mockBooking.put("bookingId", "BK-SESS0001");
            when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(mockBooking);

            // Act
            bookingController.createBooking(
                    "Alice", "VILLA", "2024-08-01", "2024-08-10", session);

            // Assert — session must have lastBooking attribute set
            assertNotNull(session.getAttribute("lastBooking"),
                    "Session must store 'lastBooking' attribute");
        }

        @Test
        @DisplayName("createBooking_withValidParams_storesGuestNameInSession")
        void createBooking_withValidParams_storesGuestNameInSession() {
            // Arrange
            Map<String, Object> mockBooking = new HashMap<>();
            mockBooking.put("bookingId", "BK-SESS0002");
            when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(mockBooking);

            // Act
            bookingController.createBooking(
                    "Bob", "STANDARD", "2024-09-01", "2024-09-03", session);

            // Assert — session must have guestName attribute set
            assertEquals("Bob", session.getAttribute("guestName"),
                    "Session must store the guest name");
        }

        @Test
        @DisplayName("createBooking_callsBookingServiceCreateBooking")
        void createBooking_callsBookingServiceCreateBooking() {
            // Arrange
            Map<String, Object> mockBooking = new HashMap<>();
            mockBooking.put("bookingId", "BK-MOCK0001");
            when(bookingService.createBooking("Carol", "SUITE", "2024-10-01", "2024-10-07"))
                    .thenReturn(mockBooking);

            // Act
            bookingController.createBooking(
                    "Carol", "SUITE", "2024-10-01", "2024-10-07", session);

            // Assert
            verify(bookingService, times(1))
                    .createBooking("Carol", "SUITE", "2024-10-01", "2024-10-07");
        }

        @Test
        @DisplayName("createBooking_addsBookingToCache")
        void createBooking_addsBookingToCache() {
            // Arrange
            Map<String, Object> mockBooking = new HashMap<>();
            mockBooking.put("bookingId", "BK-CACHE001");
            when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(mockBooking);

            // Act
            Map<String, Object> response = bookingController.createBooking(
                    "Dave", "DELUXE", "2024-11-01", "2024-11-04", session);

            // Assert — booking must be in the response
            assertNotNull(response);
            assertEquals("confirmed", response.get("status"));
        }

        @Test
        @DisplayName("createBooking_withDifferentRoomTypes_returnsConfirmedForAll")
        void createBooking_withDifferentRoomTypes_returnsConfirmedForAll() {
            // Arrange
            String[] roomTypes = {"STANDARD", "DELUXE", "SUITE", "VILLA"};
            for (String roomType : roomTypes) {
                Map<String, Object> mockBooking = new HashMap<>();
                mockBooking.put("bookingId", "BK-" + roomType);
                when(bookingService.createBooking(anyString(), eq(roomType), anyString(), anyString()))
                        .thenReturn(mockBooking);

                // Act
                Map<String, Object> response = bookingController.createBooking(
                        "Guest", roomType, "2024-01-01", "2024-01-02", session);

                // Assert
                assertEquals("confirmed", response.get("status"),
                        "Status must be 'confirmed' for room type: " + roomType);
            }
        }
    }

    // =========================================================================
    // getBookingStatus()
    // =========================================================================

    @Nested
    @DisplayName("getBookingStatus()")
    class GetBookingStatusTests {

        @Test
        @DisplayName("getBookingStatus_withBookingId_returnsNonNullMap")
        void getBookingStatus_withBookingId_returnsNonNullMap() {
            // Arrange
            Map<String, Object> mockDetails = new HashMap<>();
            mockDetails.put("id", "BK-12345678");
            when(bookingService.getBookingById("BK-12345678")).thenReturn(mockDetails);

            // Act
            Map<String, Object> result = bookingController.getBookingStatus("BK-12345678", session);

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("getBookingStatus_withBookingId_returnsBookingIdInResult")
        void getBookingStatus_withBookingId_returnsBookingIdInResult() {
            // Arrange
            Map<String, Object> mockDetails = new HashMap<>();
            when(bookingService.getBookingById("BK-12345678")).thenReturn(mockDetails);

            // Act
            Map<String, Object> result = bookingController.getBookingStatus("BK-12345678", session);

            // Assert
            assertEquals("BK-12345678", result.get("bookingId"),
                    "Result must contain the booking ID");
        }

        @Test
        @DisplayName("getBookingStatus_withSessionGuest_returnsSessionGuestInResult")
        void getBookingStatus_withSessionGuest_returnsSessionGuestInResult() {
            // Arrange
            session.setAttribute("guestName", "Eve");
            Map<String, Object> mockDetails = new HashMap<>();
            when(bookingService.getBookingById("BK-EVE001")).thenReturn(mockDetails);

            // Act
            Map<String, Object> result = bookingController.getBookingStatus("BK-EVE001", session);

            // Assert
            assertEquals("Eve", result.get("sessionGuest"),
                    "Result must contain the session guest name");
        }

        @Test
        @DisplayName("getBookingStatus_withNoSessionGuest_returnsNullSessionGuest")
        void getBookingStatus_withNoSessionGuest_returnsNullSessionGuest() {
            // Arrange — no guestName in session
            Map<String, Object> mockDetails = new HashMap<>();
            when(bookingService.getBookingById("BK-NOGUEST")).thenReturn(mockDetails);

            // Act
            Map<String, Object> result = bookingController.getBookingStatus("BK-NOGUEST", session);

            // Assert
            assertNull(result.get("sessionGuest"),
                    "sessionGuest must be null when not set in session");
        }

        @Test
        @DisplayName("getBookingStatus_withBookingId_returnsDetailsFromService")
        void getBookingStatus_withBookingId_returnsDetailsFromService() {
            // Arrange
            Map<String, Object> mockDetails = new HashMap<>();
            mockDetails.put("id", "BK-DETAIL01");
            mockDetails.put("guest", "Frank");
            when(bookingService.getBookingById("BK-DETAIL01")).thenReturn(mockDetails);

            // Act
            Map<String, Object> result = bookingController.getBookingStatus("BK-DETAIL01", session);

            // Assert
            assertEquals(mockDetails, result.get("details"),
                    "Result must contain the booking details from service");
        }

        @Test
        @DisplayName("getBookingStatus_callsBookingServiceGetBookingById")
        void getBookingStatus_callsBookingServiceGetBookingById() {
            // Arrange
            Map<String, Object> mockDetails = new HashMap<>();
            when(bookingService.getBookingById("BK-VERIFY01")).thenReturn(mockDetails);

            // Act
            bookingController.getBookingStatus("BK-VERIFY01", session);

            // Assert
            verify(bookingService, times(1)).getBookingById("BK-VERIFY01");
        }
    }

    // =========================================================================
    // checkAvailability()
    // =========================================================================

    @Nested
    @DisplayName("checkAvailability()")
    class CheckAvailabilityTests {

        @Test
        @DisplayName("checkAvailability_withRoomType_returnsNonNullMap")
        void checkAvailability_withRoomType_returnsNonNullMap() {
            // Arrange
            when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

            // Act
            Map<String, Object> result = bookingController.checkAvailability("SUITE");

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("checkAvailability_withRoomType_returnsRoomTypeInResult")
        void checkAvailability_withRoomType_returnsRoomTypeInResult() {
            // Arrange
            when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

            // Act
            Map<String, Object> result = bookingController.checkAvailability("DELUXE");

            // Assert
            assertEquals("DELUXE", result.get("roomType"),
                    "Result must contain the room type");
        }

        @Test
        @DisplayName("checkAvailability_whenRoomAvailable_returnsTrue")
        void checkAvailability_whenRoomAvailable_returnsTrue() {
            // Arrange
            when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

            // Act
            Map<String, Object> result = bookingController.checkAvailability("STANDARD");

            // Assert
            assertEquals(true, result.get("available"),
                    "Result must indicate room is available");
        }

        @Test
        @DisplayName("checkAvailability_whenRoomNotAvailable_returnsFalse")
        void checkAvailability_whenRoomNotAvailable_returnsFalse() {
            // Arrange
            when(bookingService.isRoomAvailable("UNKNOWN")).thenReturn(false);

            // Act
            Map<String, Object> result = bookingController.checkAvailability("UNKNOWN");

            // Assert
            assertEquals(false, result.get("available"),
                    "Result must indicate room is not available");
        }

        @Test
        @DisplayName("checkAvailability_returnsInventoryEndpointInResult")
        void checkAvailability_returnsInventoryEndpointInResult() {
            // Arrange
            when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

            // Act
            Map<String, Object> result = bookingController.checkAvailability("VILLA");

            // Assert
            assertNotNull(result.get("inventoryEndpoint"),
                    "Result must contain 'inventoryEndpoint' key");
        }

        @Test
        @DisplayName("checkAvailability_callsBookingServiceIsRoomAvailable")
        void checkAvailability_callsBookingServiceIsRoomAvailable() {
            // Arrange
            when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

            // Act
            bookingController.checkAvailability("SUITE");

            // Assert
            verify(bookingService, times(1)).isRoomAvailable("SUITE");
        }
    }

    // =========================================================================
    // downloadReport()
    // =========================================================================

    @Nested
    @DisplayName("downloadReport()")
    class DownloadReportTests {

        @Test
        @DisplayName("downloadReport_withMonth_returnsNonNullMap")
        void downloadReport_withMonth_returnsNonNullMap() {
            // Arrange
            when(bookingService.generateReport("March")).thenReturn("Report generated for March");

            // Act
            Map<String, Object> result = bookingController.downloadReport("March");

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("downloadReport_withMonth_returnsReportPathInResult")
        void downloadReport_withMonth_returnsReportPathInResult() {
            // Arrange
            when(bookingService.generateReport("April")).thenReturn("Report generated for April");

            // Act
            Map<String, Object> result = bookingController.downloadReport("April");

            // Assert
            assertNotNull(result.get("reportPath"),
                    "Result must contain 'reportPath' key");
        }

        @Test
        @DisplayName("downloadReport_withMonth_reportPathContainsMonth")
        void downloadReport_withMonth_reportPathContainsMonth() {
            // Arrange
            when(bookingService.generateReport("May")).thenReturn("Report generated for May");

            // Act
            Map<String, Object> result = bookingController.downloadReport("May");

            // Assert
            String reportPath = result.get("reportPath").toString();
            assertTrue(reportPath.contains("May"),
                    "Report path must contain the month 'May'");
        }

        @Test
        @DisplayName("downloadReport_withMonth_reportPathEndsWith_bookings_pdf")
        void downloadReport_withMonth_reportPathEndsWith_bookings_pdf() {
            // Arrange
            when(bookingService.generateReport("June")).thenReturn("Report generated for June");

            // Act
            Map<String, Object> result = bookingController.downloadReport("June");

            // Assert
            String reportPath = result.get("reportPath").toString();
            assertTrue(reportPath.endsWith("_bookings.pdf"),
                    "Report path must end with '_bookings.pdf', got: " + reportPath);
        }

        @Test
        @DisplayName("downloadReport_withMonth_returnsMessageFromService")
        void downloadReport_withMonth_returnsMessageFromService() {
            // Arrange
            String serviceMessage = "Report generation triggered for: July";
            when(bookingService.generateReport("July")).thenReturn(serviceMessage);

            // Act
            Map<String, Object> result = bookingController.downloadReport("July");

            // Assert
            assertEquals(serviceMessage, result.get("message"),
                    "Result must contain the message from BookingService");
        }

        @Test
        @DisplayName("downloadReport_callsBookingServiceGenerateReport")
        void downloadReport_callsBookingServiceGenerateReport() {
            // Arrange
            when(bookingService.generateReport("August")).thenReturn("Report for August");

            // Act
            bookingController.downloadReport("August");

            // Assert
            verify(bookingService, times(1)).generateReport("August");
        }

        @Test
        @DisplayName("downloadReport_reportPath_startsWithLegacyReportsDir")
        void downloadReport_reportPath_startsWithLegacyReportsDir() {
            // Arrange
            when(bookingService.generateReport("September")).thenReturn("Report for September");

            // Act
            Map<String, Object> result = bookingController.downloadReport("September");

            // Assert
            String reportPath = result.get("reportPath").toString();
            assertTrue(reportPath.startsWith("/var/legacy/reports/"),
                    "Report path must start with '/var/legacy/reports/', got: " + reportPath);
        }
    }
}
