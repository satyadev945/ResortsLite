package com.demo.resortslite.controller;

import com.demo.resortslite.BookingService;
import com.demo.resortslite.dto.LoginRequest;
import com.demo.resortslite.dto.LoginResponse;
import com.demo.resortslite.dto.UserInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private BookingService bookingService;

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        String username = loginRequest.getUsername();
        String password = loginRequest.getPassword();

        // Validate credentials and get role information
        Map<String, Object> authResult = bookingService.authenticateUserWithRole(username, password);
        boolean authenticated = (Boolean) authResult.get("authenticated");

        if (!authenticated) {
            return new LoginResponse(false, "Invalid username or password", null, null);
        }

        String role = (String) authResult.get("role");

        // Create Spring Security authentication token with ROLE_ prefix
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                username,
                password,
                Arrays.asList(new SimpleGrantedAuthority("ROLE_" + role))
        );

        // Set authentication in security context
        SecurityContextHolder.getContext().setAuthentication(authToken);

        // Create session
        HttpSession session = request.getSession(true);
        session.setAttribute("username", username);
        session.setAttribute("role", role);

        return new LoginResponse(true, "Login successful", username, role);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Clear security context
            SecurityContextHolder.clearContext();

            // Invalidate session
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }

            response.put("success", true);
            response.put("message", "Logout successful");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Logout failed: " + e.getMessage());
        }

        return response;
    }

    @GetMapping("/me")
    public UserInfo getCurrentUser(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated() ||
            "anonymousUser".equals(authentication.getPrincipal())) {
            return new UserInfo(null, null, false);
        }

        String username = authentication.getName();
        String role = authentication.getAuthorities().stream()
                .findFirst()
                .map(auth -> auth.getAuthority().replace("ROLE_", ""))
                .orElse("USER");

        return new UserInfo(username, role, true);
    }
}
