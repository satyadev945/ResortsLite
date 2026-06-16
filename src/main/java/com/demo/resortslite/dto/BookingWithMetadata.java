package com.demo.resortslite.dto;

import java.sql.Timestamp;

public class BookingWithMetadata {
    private String id;
    private String guest;
    private String room;
    private String checkin;
    private String checkout;
    private String createdBy;
    private Timestamp createdAt;
    private String accessLevel;
    private String rolePermissions;

    public BookingWithMetadata() {
    }

    public BookingWithMetadata(String id, String guest, String room, String checkin, String checkout,
                               String createdBy, Timestamp createdAt, String accessLevel) {
        this.id = id;
        this.guest = guest;
        this.room = room;
        this.checkin = checkin;
        this.checkout = checkout;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.accessLevel = accessLevel;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGuest() {
        return guest;
    }

    public void setGuest(String guest) {
        this.guest = guest;
    }

    public String getRoom() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    public String getCheckin() {
        return checkin;
    }

    public void setCheckin(String checkin) {
        this.checkin = checkin;
    }

    public String getCheckout() {
        return checkout;
    }

    public void setCheckout(String checkout) {
        this.checkout = checkout;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public String getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(String accessLevel) {
        this.accessLevel = accessLevel;
    }

    public String getRolePermissions() {
        return rolePermissions;
    }

    public void setRolePermissions(String rolePermissions) {
        this.rolePermissions = rolePermissions;
    }
}
