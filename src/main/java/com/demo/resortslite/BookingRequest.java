package com.demo.resortslite;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Data Transfer Object for booking creation requests.
 * Uses Bean Validation annotations for input validation.
 */
public class BookingRequest {

    @NotBlank(message = "Guest name is required")
    private String guestName;

    @NotBlank(message = "Room type is required")
    @Pattern(regexp = "STANDARD|DELUXE|SUITE|VILLA", message = "Invalid room type. Must be STANDARD, DELUXE, SUITE, or VILLA")
    private String roomType;

    @NotBlank(message = "Check-in date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Check-in date must be in format YYYY-MM-DD")
    private String checkIn;

    @NotBlank(message = "Check-out date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Check-out date must be in format YYYY-MM-DD")
    private String checkOut;

    // Constructors
    public BookingRequest() {
    }

    public BookingRequest(String guestName, String roomType, String checkIn, String checkOut) {
        this.guestName = guestName;
        this.roomType = roomType;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
    }

    // Getters and Setters
    public String getGuestName() {
        return guestName;
    }

    public void setGuestName(String guestName) {
        this.guestName = guestName;
    }

    public String getRoomType() {
        return roomType;
    }

    public void setRoomType(String roomType) {
        this.roomType = roomType;
    }

    public String getCheckIn() {
        return checkIn;
    }

    public void setCheckIn(String checkIn) {
        this.checkIn = checkIn;
    }

    public String getCheckOut() {
        return checkOut;
    }

    public void setCheckOut(String checkOut) {
        this.checkOut = checkOut;
    }

    @Override
    public String toString() {
        return "BookingRequest{" +
                "guestName='" + guestName + '\'' +
                ", roomType='" + roomType + '\'' +
                ", checkIn='" + checkIn + '\'' +
                ", checkOut='" + checkOut + '\'' +
                '}';
    }
}
