package com.jobai.api.interceptor;

import com.jobai.common.auth.AuthContext;
import com.jobai.infrastructure.entity.User;
import com.jobai.infrastructure.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String SESSION_ROLE_PREFIX = "user_role_";

    private final UserMapper userMapper;

    public AuthInterceptor(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            try {
                Long userId = Long.valueOf(auth.getName());
                String role = loadRole(request, userId);
                AuthContext.set(userId, role);
                request.setAttribute("currentUserId", userId);
            } catch (NumberFormatException e) {
                log.warn("Invalid userId in authentication principal: {}", auth.getName());
            }
        }
        return true;
    }

    private String loadRole(HttpServletRequest request, Long userId) {
        HttpSession session = request.getSession();
        String cacheKey = SESSION_ROLE_PREFIX + userId;
        String role = (String) session.getAttribute(cacheKey);
        if (role != null) return role;

        User user = userMapper.selectById(userId);
        role = user != null && user.getRole() != null ? user.getRole() : "user";
        session.setAttribute(cacheKey, role);
        return role;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }
}
