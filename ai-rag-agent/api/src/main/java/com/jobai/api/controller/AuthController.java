package com.jobai.api.controller;

import com.jobai.api.dto.LoginRequest;
import com.jobai.api.dto.RegisterRequest;
import com.jobai.api.dto.UserVO;
import com.jobai.api.service.AuthService;
import com.jobai.common.ErrorCode;
import com.jobai.common.R;
import com.jobai.infrastructure.entity.User;
import com.jobai.infrastructure.mapper.UserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;

@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserMapper userMapper;

    @Operation(summary = "注册")
    @PostMapping("/register")
    public R<UserVO> register(@Valid @RequestBody RegisterRequest req) {
        User user = authService.register(req.getUsername(), req.getPassword());
        return R.ok(new UserVO(user.getId(), user.getUsername()));
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public R<UserVO> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        User user = authService.authenticate(req.getUsername(), req.getPassword());

        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(
                        user.getId().toString(), null, Collections.emptyList());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);

        HttpSession session = request.getSession(true);
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        return R.ok(new UserVO(user.getId(), user.getUsername()));
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return R.ok();
    }

    @Operation(summary = "当前用户信息")
    @GetMapping("/me")
    public R<UserVO> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return R.fail(ErrorCode.NOT_AUTHENTICATED.getCode(),
                    ErrorCode.NOT_AUTHENTICATED.getMessage());
        }
        Long userId = Long.valueOf(auth.getName());
        User user = userMapper.selectById(userId);
        String username = user != null ? user.getUsername() : auth.getName();
        return R.ok(new UserVO(userId, username));
    }
}
