package com.jobai.api.interceptor;

import com.jobai.common.auth.AuthContext;
import com.jobai.infrastructure.entity.User;
import com.jobai.infrastructure.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

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
        User user = userMapper.selectById(userId);
        return user != null && user.getRole() != null ? user.getRole() : "user";
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }
}
