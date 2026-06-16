package com.demo.resortslite.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void testGetAllBookingsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/bookings"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testGetAllBookingsAsAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/bookings"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    public void testGetAllBookingsAsUser() throws Exception {
        mockMvc.perform(get("/api/admin/bookings"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testGetBookingByIdAsAdmin() throws Exception {
        // This will return 404 or error since no bookings exist in test DB
        // but it tests that the endpoint is accessible with ADMIN role
        mockMvc.perform(get("/api/admin/bookings/BK-TEST123"))
                .andExpect(status().isOk());
    }

    @Test
    public void testGetBookingByIdUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/bookings/BK-TEST123"))
                .andExpect(status().isForbidden());
    }
}
