package com.jobai.api.controller;

import com.jobai.agent.service.QuizService;
import com.jobai.api.config.CurrentUserIdArgumentResolver;
import com.jobai.api.service.StreamingChatService;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.GlobalExceptionHandler;
import com.jobai.rag.service.ChatSessionService;
import com.jobai.rag.service.LearningNoteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RagOwnershipTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void forbiddenSessionReturns403BeforeStartingModelRequest() throws Exception {
        ChatSessionService sessions = mock(ChatSessionService.class);
        StreamingChatService streaming = mock(StreamingChatService.class);
        when(sessions.beginUserTurn(10L, 1L, "question"))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null, List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new RagController(sessions, mock(QuizService.class), streaming, mock(LearningNoteService.class), mock(com.jobai.rag.service.LearningTreeService.class)))
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/agent/chat/stream").contentType("application/json")
                        .content("{\"sessionId\":10,\"message\":\"question\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(streaming);
    }
}
