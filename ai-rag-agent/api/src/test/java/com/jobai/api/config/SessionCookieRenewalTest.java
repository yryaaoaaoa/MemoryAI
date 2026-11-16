package com.jobai.api.config;

import com.jobai.api.controller.AuthController;
import com.jobai.api.service.AuthService;
import com.jobai.infrastructure.entity.User;
import com.jobai.infrastructure.mapper.UserMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionCookieRenewalTest {
    AnnotationConfigWebApplicationContext context;
    MapSessionRepository sessions;
    MockMvc mvc;

    @BeforeEach void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfiguration.class);
        context.refresh();
        sessions = context.getBean(MapSessionRepository.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(SessionRepositoryFilter.class),
                        context.getBean("springSecurityFilterChain", FilterChainProxy.class)).build();
        User user = new User();
        user.setId(1L); user.setUsername("test"); user.setRole("user");
        when(context.getBean(AuthService.class).authenticate("test", "password")).thenReturn(user);
        when(context.getBean(UserMapper.class).selectById(1L)).thenReturn(user);
    }

    @AfterEach void close() { context.close(); }

    @Test void activityRenewsCookieAndRepositoryWithoutChangingId() throws Exception {
        MapSession session = authenticatedSession();
        Instant oldAccess = Instant.now().minusSeconds(20);
        session.setLastAccessedTime(oldAccess); sessions.save(session);
        MvcResult result = mvc.perform(get("/api/auth/me").cookie(cookie(session)).secure(true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(1)).andReturn();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).hasSize(1);
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("MEMORAISESSION=" + cookie(session).getValue(), "Max-Age=30", "Path=/",
                        "Domain=example.test", "HttpOnly", "Secure", "SameSite=Strict");
        assertThat(sessions.findById(session.getId()).getLastAccessedTime()).isAfter(oldAccess);
        assertThat(sessions.findById(session.getId()).getId()).isEqualTo(session.getId());
    }

    @Test void headersAreRenewedBeforeStreamingCommits() throws Exception {
        mvc.perform(get("/api/stream").cookie(cookie(authenticatedSession())))
                .andExpect(status().isOk()).andExpect(content().string("data: ok\n\n"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=30")));
    }

    @Test void anonymousPublicRequestDoesNotCreateSessionOrCookie() throws Exception {
        mvc.perform(get("/api/knowledge-bases/public"))
                .andExpect(status().isOk()).andExpect(content().string("anonymous"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test void expiredSessionCannotBeRenewedOrUsedToAccessProtectedResource() throws Exception {
        MapSession session = authenticatedSession();
        session.setLastAccessedTime(Instant.now().minusSeconds(31)); sessions.save(session);
        mvc.perform(get("/api/auth/me").cookie(cookie(session)).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(sessions.findById(session.getId())).isNull();
    }

    @Test void anonymousSessionIsNotRenewed() throws Exception {
        MapSession session = sessions.createSession(); sessions.save(session);
        mvc.perform(get("/api/knowledge-bases/public").cookie(cookie(session)))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test void loginRotatesIdWithoutDuplicateCookie() throws Exception {
        MapSession previous = authenticatedSession();
        MvcResult result = mvc.perform(post("/api/auth/login").cookie(cookie(previous))
                        .contentType("application/json").content("{\"username\":\"test\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).hasSize(1);
        Cookie next = result.getResponse().getCookie("MEMORAISESSION");
        assertThat(next).isNotNull();
        assertThat(next.getValue()).isNotEqualTo(cookie(previous).getValue());
        assertThat(sessions.findById(previous.getId())).isNull();
        mvc.perform(get("/api/auth/me").cookie(next)).andExpect(status().isOk());
    }

    @Test void logoutOnlyClearsCookieAndOldCookieCannotRestoreLogin() throws Exception {
        MapSession session = authenticatedSession();
        MvcResult result = mvc.perform(post("/api/auth/logout").cookie(cookie(session)))
                .andExpect(status().isOk()).andReturn();
        // Spring Session may expire on both response commit and filter cleanup; neither may renew it.
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isNotEmpty()
                .allSatisfy(value -> assertThat(value).contains("MEMORAISESSION=;", "Max-Age=0"));
        assertThat(sessions.findById(session.getId())).isNull();
        mvc.perform(get("/api/auth/me").cookie(cookie(session)).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
    }

    private MapSession authenticatedSession() {
        MapSession session = sessions.createSession();
        session.setMaxInactiveInterval(Duration.ofSeconds(30));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken("1", null, List.of())));
        sessions.save(session);
        return session;
    }

    private Cookie cookie(MapSession session) {
        return new Cookie("MEMORAISESSION", Base64.getEncoder().encodeToString(
                session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    @Configuration
    @EnableWebMvc
    @EnableSpringHttpSession
    @Import({SecurityConfig.class, AdminAuthorizationManager.class, AuthController.class, ProbeController.class})
    static class WebConfiguration {
        @Bean MapSessionRepository sessionRepository() { return new MapSessionRepository(new ConcurrentHashMap<>()); }
        @Bean UserMapper userMapper() { return mock(UserMapper.class); }
        @Bean AuthService authService() { return mock(AuthService.class); }
        @Bean DefaultCookieSerializer cookieSerializer() {
            DefaultCookieSerializer serializer = new DefaultCookieSerializer();
            serializer.setCookieName("MEMORAISESSION"); serializer.setCookiePath("/");
            serializer.setDomainName("example.test"); serializer.setUseHttpOnlyCookie(true);
            serializer.setUseSecureCookie(true); serializer.setSameSite("Strict");
            // A different configured lifetime proves renewal uses the actual session interval.
            serializer.setCookieMaxAge(1800);
            return serializer;
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/knowledge-bases/public") String publicResource(HttpServletRequest request) {
            return request.getSession(false) == null ? "anonymous" : "session";
        }
        @GetMapping("/api/stream") void stream(HttpServletResponse response) throws IOException {
            assertThat(response.containsHeader("Set-Cookie")).isTrue();
            response.setContentType("text/event-stream");
            response.getWriter().write("data: ok\n\n"); response.flushBuffer();
        }
    }
}
