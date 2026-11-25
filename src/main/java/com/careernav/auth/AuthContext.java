package com.careernav.auth;

import com.careernav.common.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class AuthContext {
    private AuthContext() {}

    public static Optional<CurrentUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return Optional.empty();
        Object principal = authentication.getPrincipal();
        return principal instanceof CurrentUser user ? Optional.of(user) : Optional.empty();
    }

    public static Long userIdOrNull() { return currentUser().map(CurrentUser::id).orElse(null); }
    public static Long requireUserId() { return currentUser().map(CurrentUser::id).orElseThrow(() -> new BusinessException("请先登录")); }
    public static boolean isAdmin() { return currentUser().map(CurrentUser::admin).orElse(false); }
    public static String dataKind() { return currentUser().map(user -> "DEMO".equalsIgnoreCase(user.role()) ? "DEMO" : "REAL").orElse("REAL"); }
    public static boolean canAccess(Long ownerUserId) {
        Long current = userIdOrNull();
        return current == null || isAdmin() || (ownerUserId != null && ownerUserId.equals(current));
    }
}
