package com.jobai.api.controller;

import com.jobai.api.config.CurrentUserIdArgumentResolver;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.GlobalExceptionHandler;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.rag.entity.ChatMessage;
import com.jobai.rag.entity.LearningNote;
import com.jobai.rag.mapper.ChatSessionMapper;
import com.jobai.rag.mapper.LearningNoteMapper;
import com.jobai.rag.service.ChatSessionService;
import com.jobai.rag.service.LearningNoteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LearningNoteControllerTest {
    LearningNoteMapper notes = mock(LearningNoteMapper.class);
    ChatSessionService chats = mock(ChatSessionService.class);
    LlmService llm = mock(LlmService.class);
    LearningNoteService service = new LearningNoteService(notes, mock(ChatSessionMapper.class), chats, llm);
    MockMvc mvc;
    final String valid = """
            {"title":"Transaction","confusion":"why","content":"explanation","status":"PENDING",
             "sourceSessionId":10,"sourceMessageIds":[1],"requestId":"10000000-0000-0000-0000-000000000001"}
            """;

    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1",null,List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new LearningNoteController(service))
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        ChatMessage m = new ChatMessage(); m.setId(1L); m.setSessionId(10L); m.setRole("user"); m.setContent("why?");
        when(chats.getMessages(10L,1L)).thenReturn(List.of(m));
        doAnswer(inv -> {
            LearningNote n = inv.getArgument(0); n.setId(5L);
            when(notes.findByCreateRequest(n.getUserId(),n.getCreateRequestId())).thenReturn(n); return 1;
        }).when(notes).insertOnce(any(LearningNote.class));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void creationReturnsNoteWithAuthenticatedOwnerAndAuthoritativeSnapshot() throws Exception {
        mvc.perform(post("/api/learning-notes").contentType("application/json").content(valid))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.createRequestId").doesNotExist())
                .andExpect(jsonPath("$.data.createRequestHash").doesNotExist())
                .andExpect(jsonPath("$.data.sourceSnapshot").value(org.hamcrest.Matchers.containsString("why?")));
        verifyNoInteractions(llm);
    }

    @Test void legacyPositionFieldsAreIgnoredAndDoNotChangeCreationRetryIdentity() throws Exception {
        String legacy = valid.stripTrailing().replace("}", ",\"sourceRanges\":[{\"id\":1,\"start\":999,\"end\":1000}]}");
        mvc.perform(post("/api/learning-notes").contentType("application/json").content(legacy))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceRanges").doesNotExist());
        mvc.perform(post("/api/learning-notes").contentType("application/json").content(valid))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(5));
        verify(notes,times(1)).insertOnce(any());
        verify(chats,times(1)).getMessages(10L,1L);
    }

    @Test void bodyModeOrganizesDraftWithoutSourceSelectionOrMessageLookup() throws Exception {
        when(llm.chat(anyString(),anyString())).thenReturn("{\"title\":\"Edited\",\"confusion\":\"\",\"content\":\"organized\"}");
        mvc.perform(post("/api/learning-notes/draft").contentType("application/json")
                        .content("""
                                {"mode":"BODY","currentDraft":{"title":"Edited","confusion":"","content":"my own draft"},
                                 "includedSourceMessageIds":[999]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("organized"));
        verifyNoInteractions(chats,notes);
    }

    @Test void mergeLimitExplainsRecoveryAndInvalidModesAreRejected() throws Exception {
        ChatMessage source = new ChatMessage(); source.setId(1L); source.setRole("assistant"); source.setContent("x".repeat(24000));
        when(chats.getMessages(10L,1L)).thenReturn(List.of(source));
        String input = "{\"mode\":\"MERGE\",\"sourceSessionId\":10,\"sourceMessageIds\":[1],\"currentDraft\":{\"title\":\"title\",\"confusion\":\"\",\"content\":\""+"x".repeat(24000)+"\"}}";
        mvc.perform(post("/api/learning-notes/draft").contentType("application/json").content(input))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("整理正文")));
        mvc.perform(post("/api/learning-notes/draft").contentType("application/json").content(input.replace("MERGE","INVALID")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(llm,notes);
    }

    @Test void badStatusAndMalformedMessageIdsReturn400WithoutSaving() throws Exception {
        mvc.perform(post("/api/learning-notes").contentType("application/json").content(valid.replace("PENDING","OTHER")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/learning-notes").contentType("application/json").content(valid.replace("[1]","[\"not-a-number\"]")))
                .andExpect(status().isBadRequest());
        verify(notes,never()).insertOnce(any(LearningNote.class));
    }

    @Test void inaccessibleSourceReturns403AndMissingNoteReturns404() throws Exception {
        when(chats.getMessages(10L,1L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        mvc.perform(post("/api/learning-notes").contentType("application/json").content(valid))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/learning-notes/999")).andExpect(status().isNotFound());
        verify(notes,never()).insertOnce(any(LearningNote.class));
    }

    @Test void draftFailureHasRecoverable503AndRawSaveStillWorks() throws Exception {
        when(llm.chat(anyString(),anyString())).thenThrow(new RuntimeException("down"));
        mvc.perform(post("/api/learning-notes/draft").contentType("application/json")
                        .content("{\"sourceSessionId\":10,\"sourceMessageIds\":[1]}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));
        mvc.perform(post("/api/learning-notes").contentType("application/json").content(valid))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(5));
    }

    @Test void retryReturnsSameNoteAndChangedPayloadReturns409() throws Exception {
        mvc.perform(post("/api/learning-notes").contentType("application/json").content(valid))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(5));
        mvc.perform(post("/api/learning-notes").contentType("application/json").content(valid))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(5));
        mvc.perform(post("/api/learning-notes").contentType("application/json").content(valid.replace("explanation","new explanation")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        verify(notes,times(1)).insertOnce(any());
    }

    @Test void missingVersionReturns400StaleVersionReturns409AndCurrentVersionSaves() throws Exception {
        LearningNote note = new LearningNote(); note.setId(5L); note.setUserId(1L); note.setVersion(2L);
        when(notes.selectById(5L)).thenReturn(note);
        when(notes.updateIfVersion(any(),eq(2L))).thenReturn(1);
        String edit = "{\"title\":\"Edited\",\"confusion\":\"\",\"content\":\"new explanation\",\"status\":\"REVIEW\"}";
        mvc.perform(put("/api/learning-notes/5").contentType("application/json").content(edit))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/learning-notes/5").contentType("application/json").content(edit.replace("}",",\"expectedVersion\":1}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        verify(notes,never()).updateIfVersion(any(),anyLong());
        mvc.perform(put("/api/learning-notes/5").contentType("application/json").content(edit.replace("}",",\"expectedVersion\":2}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.content").value("new explanation"));
    }
}
