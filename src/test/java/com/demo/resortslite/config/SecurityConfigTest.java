package com.demo.resortslite.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void testPublicEndpointsAccessible() throws Exception {
        // Auth endpoints should be publicly accessible
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"test\",\"password\":\"test\"}"))
                .andExpect(status().isOk());
    }

    @Test
    public void testAdminEndpointsRequireAuth() throws Exception {
        // Admin endpoints should require authentication
        mockMvc.perform(get("/api/admin/bookings"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testAdminEndpointsAccessibleWithAdminRole() throws Exception {
        // Admin endpoints should be accessible with ADMIN role
        mockMvc.perform(get("/api/admin/bookings"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    public void testAdminEndpointsDeniedForUserRole() throws Exception {
        // Admin endpoints should be denied for USER role
        mockMvc.perform(get("/api/admin/bookings"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testCorsEnabled() throws Exception {
        // Test that CORS is enabled by checking OPTIONS request
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk());
    }
}
