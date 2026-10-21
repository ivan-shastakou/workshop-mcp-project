package com.leverx.roombooking.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Authenticated user, taken from the JWT issued by auth-service.
 */
public record CurrentUser(Long id, String name, String email) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(Long.valueOf(jwt.getSubject()), jwt.getClaimAsString("name"), jwt.getClaimAsString("email"));
    }
}
