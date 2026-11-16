package com.jobai.api.config;

import com.jobai.infrastructure.entity.User;
import com.jobai.infrastructure.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** Check the current database role, including sessions created before role support. */
@Component
@RequiredArgsConstructor
public class AdminAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {
    private final UserMapper userMapper;

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication,
                                       RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated()) return new AuthorizationDecision(false);
        try {
            User user = userMapper.selectById(Long.valueOf(auth.getName()));
            return new AuthorizationDecision(user != null && "admin".equals(user.getRole()));
        } catch (NumberFormatException e) {
            return new AuthorizationDecision(false);
        }
    }
}
