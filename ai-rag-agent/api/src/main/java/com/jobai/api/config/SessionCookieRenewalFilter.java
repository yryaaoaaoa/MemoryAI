package com.jobai.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Keeps the browser cookie alive for the same idle interval as the existing server session. */
final class SessionCookieRenewalFilter extends OncePerRequestFilter {
    private final CookieSerializer cookieSerializer;
    private final AuthenticationTrustResolverImpl trustResolver = new AuthenticationTrustResolverImpl();

    SessionCookieRenewalFilter(CookieSerializer cookieSerializer) {
        this.cookieSerializer = cookieSerializer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/api/auth/login") || path.equals("/api/auth/logout")
                || path.equals("/api/auth/register");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !trustResolver.isAnonymous(authentication)) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                CookieSerializer.CookieValue cookie = new CookieSerializer.CookieValue(request, response, session.getId());
                cookie.setCookieMaxAge(session.getMaxInactiveInterval());
                // Write before the handler: streaming responses can commit their headers immediately.
                cookieSerializer.writeCookieValue(cookie);
            }
        }
        chain.doFilter(request, response);
    }
}
