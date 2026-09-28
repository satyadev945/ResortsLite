package com.demo.resortslite.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Azure Active Directory Authentication Service
 * 
 * FIXED cr-java-0090: Centralized Azure AD authentication utilities
 * 
 * This service provides helper methods for working with Azure AD authenticated users:
 * - Extract user information from JWT tokens
 * - Get authenticated user's identity and claims
 * - Validate user authentication status
 * 
 * Azure AD JWT Token Claims:
 * - oid: Azure AD object ID (unique user identifier)
 * - preferred_username: User's email address
 * - name: User's display name
 * - roles: User's application roles
 * - groups: User's Azure AD group memberships
 * 
 * This replaces file-based authentication with centralized identity management.
 */
@Service
public class AzureAdAuthenticationService {

    /**
     * Get the currently authenticated user's information from Azure AD JWT token
     * 
     * @return Map containing user information (email, userId, name) or empty map if not authenticated
     */
    public Map<String, String> getAuthenticatedUserInfo() {
        Map<String, String> userInfo = new HashMap<>();
        
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            
            if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
                Jwt jwt = (Jwt) authentication.getPrincipal();
                
                // Extract user claims from Azure AD JWT token
                userInfo.put("userId", jwt.getClaimAsString("oid"));
                userInfo.put("email", jwt.getClaimAsString("preferred_username"));
                userInfo.put("name", jwt.getClaimAsString("name"));
                userInfo.put("tenantId", jwt.getClaimAsString("tid"));
            }
        } catch (Exception e) {
            // Log error but return empty map to avoid breaking application flow
            System.err.println("Error extracting user info from Azure AD token: " + e.getMessage());
        }
        
        return userInfo;
    }

    /**
     * Get the authenticated user's Azure AD object ID
     * 
     * @return User's Azure AD object ID or null if not authenticated
     */
    public String getAuthenticatedUserId() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            
            if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
                Jwt jwt = (Jwt) authentication.getPrincipal();
                return jwt.getClaimAsString("oid");
            }
        } catch (Exception e) {
            System.err.println("Error extracting user ID from Azure AD token: " + e.getMessage());
        }
        
        return null;
    }

    /**
     * Get the authenticated user's email address
     * 
     * @return User's email address or null if not authenticated
     */
    public String getAuthenticatedUserEmail() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            
            if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
                Jwt jwt = (Jwt) authentication.getPrincipal();
                return jwt.getClaimAsString("preferred_username");
            }
        } catch (Exception e) {
            System.err.println("Error extracting user email from Azure AD token: " + e.getMessage());
        }
        
        return null;
    }

    /**
     * Check if the current request is authenticated via Azure AD
     * 
     * @return true if user is authenticated, false otherwise
     */
    public boolean isAuthenticated() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication != null 
                && authentication.isAuthenticated() 
                && authentication.getPrincipal() instanceof Jwt;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the full JWT token for the authenticated user
     * 
     * @return JWT token or null if not authenticated
     */
    public Jwt getJwtToken() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            
            if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
                return (Jwt) authentication.getPrincipal();
            }
        } catch (Exception e) {
            System.err.println("Error extracting JWT token: " + e.getMessage());
        }
        
        return null;
    }
}
