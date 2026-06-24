package com.jobai.common.auth;

/**
 * ThreadLocal holder for the currently authenticated user.
 * Set by AuthInterceptor at request start, read by agent tools and services.
 */
public final class AuthContext {
    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_ROLE = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(Long userId, String role) {
        CURRENT_USER.set(userId);
        CURRENT_ROLE.set(role);
    }

    public static Long get() { return CURRENT_USER.get(); }
    public static String getRole() { return CURRENT_ROLE.get(); }

    public static boolean isAdmin() { return "admin".equals(CURRENT_ROLE.get()); }

    public static void clear() {
        CURRENT_USER.remove();
        CURRENT_ROLE.remove();
    }
}
