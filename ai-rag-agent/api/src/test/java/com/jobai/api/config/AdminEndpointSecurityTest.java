package com.jobai.api.config;

import com.jobai.api.controller.DevOcrPreviewController;
import com.jobai.api.controller.EsManageController;
import com.jobai.api.controller.SettingsController;
import com.jobai.api.interceptor.AuthInterceptor;
import com.jobai.common.JobAiProperties.RetrievalProperties;
import com.jobai.common.auth.AuthContext;
import com.jobai.infrastructure.entity.User;
import com.jobai.infrastructure.mapper.UserMapper;
import com.jobai.knowledge.config.RetrievalConfigService;
import com.jobai.knowledge.parser.DocumentParser;
import com.jobai.knowledge.parser.MdPostProcessor;
import com.jobai.knowledge.parser.PaddleOcrService;
import com.jobai.rag.admin.EsAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The production filter chain and role lookup run before real MVC controllers; only I/O services are mocked. */
class AdminEndpointSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private UserMapper users;
    private EsAdminService es;
    private RetrievalConfigService retrieval;
    private PaddleOcrService ocr;
    private DocumentParser documentParser;

    @BeforeEach
    void setUp() { startContext("prod"); }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        AuthContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void ordinaryUserCannotReadDeleteEsOrChangeGlobalSettings() throws Exception {
        databaseRole(7L, "user");
        MockHttpSession session = session(7L, "ROLE_USER");
        mvc.perform(get("/api/admin/es/indices").session(session)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/es/indices/knowledge_chunks").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/retrieval").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/settings/retrieval").session(session)).andExpect(status().isForbidden());
        verifyNoInteractions(es, retrieval);
    }

    @Test
    void databaseAdminCanUseManagementEndpointsEvenWithLegacySessionAuthorities() throws Exception {
        databaseRole(8L, "admin");
        // The database role is authoritative; old sessions need not contain ROLE_ADMIN.
        MockHttpSession session = session(8L, "ROLE_USER");
        when(es.listIndices()).thenReturn(List.of());
        mvc.perform(get("/api/admin/es/indices").session(session)).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/es/indices/knowledge_chunks").session(session))
                .andExpect(status().isOk());
        mvc.perform(get("/api/settings/retrieval").session(session)).andExpect(status().isOk());
        mvc.perform(put("/api/settings/retrieval").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/settings/retrieval").session(session)).andExpect(status().isOk());
        verify(es).listIndices();
        verify(es).deleteIndex("knowledge_chunks");
        verify(retrieval).getActiveConfig();
        verify(retrieval).saveConfig(any(RetrievalProperties.class));
        verify(retrieval).resetConfig();
        assertNull(AuthContext.get(), "Request identity must be cleared after MVC completion");
    }

    @Test
    void revokedAdminIsDeniedOnNextRequestUsingTheSameSession() throws Exception {
        User user = databaseRole(8L, "admin");
        MockHttpSession session = session(8L, "ROLE_ADMIN");
        when(es.listIndices()).thenReturn(List.of());
        mvc.perform(get("/api/admin/es/indices").session(session)).andExpect(status().isOk());
        clearInvocations(es, retrieval, users);
        user.setRole("user");
        mvc.perform(delete("/api/admin/es/indices/knowledge_chunks").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/retrieval").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verify(users, times(2)).selectById(8L);
        verifyNoInteractions(es, retrieval);
    }

    @Test
    void deletedAccountCannotReuseAnAdminSession() throws Exception {
        when(users.selectById(8L)).thenReturn(null);
        mvc.perform(delete("/api/admin/es/indices/knowledge_chunks").session(session(8L, "ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(es, retrieval);
    }

    @Test
    void anonymousManagementAndPreviewRequestsRequireLogin() throws Exception {
        mvc.perform(get("/api/admin/es/indices")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/es/indices/knowledge_chunks")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/settings/retrieval").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/settings/retrieval")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/test/ocr-preview").file(markdown()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(es, retrieval, ocr, documentParser);
    }

    @Test
    void productionDoesNotRegisterTheOcrPreviewRoute() throws Exception {
        assertTrue(context.getBeansOfType(DevOcrPreviewController.class).isEmpty());
        databaseRole(8L, "admin");
        mvc.perform(multipart("/api/test/ocr-preview").file(markdown())
                        .session(session(8L, "ROLE_ADMIN")))
                .andExpect(status().isNotFound());
        verifyNoInteractions(ocr, documentParser);
    }

    @Test
    void developmentAdminCanPreviewExplicitlyUploadedImage() throws Exception {
        startContext("dev");
        assertEquals(1, context.getBeansOfType(DevOcrPreviewController.class).size());
        databaseRole(8L, "admin");
        byte[] bytes = {1, 2, 3};
        when(ocr.ocrImage(aryEq(bytes), eq("scan.png"))).thenReturn("recognized attachment");
        mvc.perform(multipart("/api/test/ocr-preview").file(markdown())
                        .file(new MockMultipartFile("images", "scan.png", "image/png", bytes))
                        .session(session(8L, "ROLE_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sectionCount").value(1))
                .andExpect(jsonPath("$.data.sections[0].text", containsString("recognized attachment")));
        verify(ocr).ocrImage(aryEq(bytes), eq("scan.png"));
        verifyNoMoreInteractions(ocr);
        verifyNoInteractions(documentParser);
    }

    @Test
    void developmentPreviewStillRejectsOrdinaryAccountsBeforeProcessingImages() throws Exception {
        startContext("dev");
        databaseRole(7L, "user");
        mvc.perform(multipart("/api/test/ocr-preview").file(markdown())
                        .file(new MockMultipartFile("images", "scan.png", "image/png", new byte[]{1}))
                        .session(session(7L, "ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ocr, documentParser);
    }

    private void startContext(String profile) {
        if (context != null) context.close();
        context = new AnnotationConfigWebApplicationContext();
        // This suite exercises enabled management endpoints; disabled deployments have separate tests.
        org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context, "jobai.rag.enabled=true");
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles(profile);
        context.register(TestWebConfiguration.class);
        context.refresh();
        users = context.getBean(UserMapper.class);
        es = context.getBean(EsAdminService.class);
        retrieval = context.getBean(RetrievalConfigService.class);
        ocr = context.getBean(PaddleOcrService.class);
        documentParser = context.getBean(DocumentParser.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", FilterChainProxy.class))
                .build();
    }

    private User databaseRole(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        when(users.selectById(id)).thenReturn(user);
        return user;
    }

    private MockHttpSession session(long userId, String authority) {
        MockHttpSession session = new MockHttpSession(context.getServletContext());
        var authentication = new UsernamePasswordAuthenticationToken(Long.toString(userId), null,
                List.of(new SimpleGrantedAuthority(authority)));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        return session;
    }

    private MockMultipartFile markdown() {
        return new MockMultipartFile("file", "notes.md", "text/markdown",
                "# Notes\n![scan](scan.png)".getBytes(StandardCharsets.UTF_8));
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, AdminAuthorizationManager.class, EsManageController.class,
            SettingsController.class, DevOcrPreviewController.class})
    static class TestWebConfiguration implements WebMvcConfigurer {
        @Bean org.springframework.session.web.http.CookieSerializer cookieSerializer() {
            return new org.springframework.session.web.http.DefaultCookieSerializer();
        }
        @Bean UserMapper userMapper() { return mock(UserMapper.class); }
        @Bean EsAdminService esAdminService() { return mock(EsAdminService.class); }
        @Bean RetrievalConfigService retrievalConfigService() { return mock(RetrievalConfigService.class); }
        @Bean RetrievalProperties retrievalProperties() { return new RetrievalProperties(); }
        @Bean DocumentParser documentParser() { return mock(DocumentParser.class); }
        @Bean PaddleOcrService paddleOcrService() { return mock(PaddleOcrService.class); }
        @Bean MdPostProcessor mdPostProcessor() { return new MdPostProcessor(paddleOcrService()); }
        @Bean AuthInterceptor authInterceptor() { return new AuthInterceptor(userMapper()); }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(authInterceptor()).addPathPatterns("/api/**");
        }
    }
}
