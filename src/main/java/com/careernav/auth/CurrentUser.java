package com.careernav.auth;

public record CurrentUser(Long id, String username, String displayName, String role) {
    public boolean admin() { return "ADMIN".equalsIgnoreCase(role); }
}
