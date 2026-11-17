package com.jobai.api.controller;

import com.jobai.agent.service.QuizService;
import com.jobai.agent.tool.KnowledgeRetrievalTool;
import com.jobai.api.config.CurrentUserIdArgumentResolver;
import com.jobai.api.service.StreamingChatService;
import com.jobai.common.GlobalExceptionHandler;
import com.jobai.common.JobAiProperties;
import com.jobai.rag.service.ChatSessionService;
import com.jobai.rag.service.LearningNoteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RagKnowledgeToggleTest {
    private final ChatSessionService sessions = mock(ChatSessionService.class);
    private final LearningNoteService notes = mock(LearningNoteService.class);

    private MockMvc mvc(StreamingChatService streaming) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1", null, List.of()));
        return MockMvcBuilders.standaloneSetup(new RagController(sessions, mock(QuizService.class), streaming, notes, mock(com.jobai.rag.service.LearningTreeService.class)))
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @CsvSource({"omitted,false", "false,false", "true,true"})
    void requestModeIsPassedToStreamingAndDefaultsToOff(String option, boolean expected) throws Exception {
        var streaming = mock(StreamingChatService.class);
        when(sessions.beginUserTurn(10L,1L,"question")).thenReturn(new ChatSessionService.ChatContext("summary",List.of(),"resume",3));
        when(notes.contextForSession(10L,1L)).thenReturn("note");
        String field = "omitted".equals(option) ? "" : ",\"useKnowledgeBase\":" + option;
        mvc(streaming).perform(post("/api/agent/chat/stream").contentType("application/json")
                        .content("{\"message\":\"question\",\"sessionId\":10" + field + "}"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        verify(streaming).streamChat(eq("question"),eq(List.of()),any(SseEmitter.class),eq(10L),eq(1L),eq("summarynote"),eq("resume"),eq(3),eq(expected));
        verify(streaming, times(expected ? 1 : 0)).requireKnowledgeBaseAvailable();
        verify(sessions).beginUserTurn(10L,1L,"question");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"true\"", "\"false\"", "1", "0", "[]", "{}"})
    void nonBooleanModeIsRejectedBeforeWritingTheUserTurn(String option) throws Exception {
        var streaming = mock(StreamingChatService.class);
        mvc(streaming).perform(post("/api/agent/chat/stream").contentType("application/json")
                        .content("{\"message\":\"question\",\"sessionId\":10,\"useKnowledgeBase\":" + option + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("useKnowledgeBase 必须为布尔值 true 或 false"));
        verifyNoInteractions(sessions,notes,streaming);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableKnowledgeBaseIsRejectedBeforeWritingTheUserTurn(boolean beanPresent) throws Exception {
        var properties = new JobAiProperties();
        // Both a globally disabled deployment and a missing retrieval bean are unavailable.
        properties.getRag().setEnabled(!beanPresent);
        var retrieval = beanPresent ? mock(KnowledgeRetrievalTool.class) : null;
        var streaming = new StreamingChatService(properties,retrieval,sessions);
        mvc(streaming).perform(post("/api/agent/chat/stream").contentType("application/json")
                        .content("{\"message\":\"question\",\"sessionId\":10,\"useKnowledgeBase\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("知识库检索当前不可用，请关闭“知识库检索”后重试")));
        verifyNoInteractions(sessions,notes);
        if (retrieval != null) verifyNoInteractions(retrieval);
    }
}
