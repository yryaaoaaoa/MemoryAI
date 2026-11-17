package com.jobai.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.common.BusinessException;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.rag.entity.ChatMessage;
import com.jobai.rag.entity.ChatSession;
import com.jobai.rag.entity.LearningNote;
import com.jobai.rag.mapper.ChatMessageMapper;
import com.jobai.rag.mapper.ChatSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningTreeQuestionsTest {
    final ChatSessionMapper sessions = mock(ChatSessionMapper.class);
    final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    final ChatSessionService chats = mock(ChatSessionService.class);
    final LearningNoteService notes = mock(LearningNoteService.class);
    final LlmService llm = mock(LlmService.class);
    final LearningTreeService service = new LearningTreeService(sessions, messages, chats, notes, llm,
            mock(TransactionTemplate.class), Runnable::run);
    LearningNote note;

    @BeforeEach void setup() {
        ChatSession session = new ChatSession(); session.setId(20L); session.setUserId(1L); session.setNoteId(5L);
        when(sessions.selectById(20L)).thenReturn(session);
        note = new LearningNote(); note.setId(5L); note.setUserId(1L);
        note.setTreeJson("""
                {"version":1,"nodes":[{"id":"embedding","title":"Token 怎么变成向量？","body":"旧总结保留但不作原文",
                "children":[],"sourceMessageIds":[1,2]}]}
                """);
        note.setSourceSnapshot("""
                [{"id":1,"sessionId":10,"role":"user","content":"Token 怎么变成向量？"},
                 {"id":2,"sessionId":10,"role":"assistant","content":"按 ID 查表。\\n\\n```python\\nx = table[42]\\n```"},
                 {"id":3,"sessionId":11,"role":"user","content":"Attention 怎么计算？"}]
                """);
        when(notes.get(5L,1L)).thenReturn(note);
    }

    @Test void originalConversationSurvivesDeletionAndIgnoresLegacySummary() {
        var detail = service.nodeDetail(20L,1L,"embedding");
        assertEquals(List.of(1L,2L),detail.messages().stream().map(LearningNoteService.SourceMessage::id).toList());
        assertEquals("按 ID 查表。\n\n```python\nx = table[42]\n```",detail.messages().get(1).content());
        assertTrue(service.contextForNode(20L,1L,"embedding").contains("table[42]"));
        assertFalse(service.contextForNode(20L,1L,"embedding").contains("旧总结"));
        verifyNoInteractions(messages,chats,llm);
    }

    @Test void invalidReferencesCannotExposeMessagesOutsideCapturedSnapshot() {
        note.setTreeJson(note.getTreeJson().replace("[1,2]","[1,2,999]"));
        assertEquals(2,service.nodeDetail(20L,1L,"embedding").messages().size());
        verifyNoInteractions(messages);
        assertThrows(IllegalArgumentException.class,()->service.nodeDetail(20L,1L,"foreign-node"));
        assertEquals(403,assertThrows(BusinessException.class,()->service.nodeDetail(20L,2L,"embedding")).getCode());
        verifyNoInteractions(llm);
    }

    @Test void oldAutomaticAssociationResolvesOnlyItsExactCapturedTurn() {
        note.setTreeJson(note.getTreeJson().replace(",\"sourceMessageIds\":[1,2]",""));
        ChatMessage question = new ChatMessage();question.setId(1L);question.setSessionId(10L);question.setRole("user");question.setMessageOrder(0);
        ChatMessage reply = new ChatMessage();reply.setId(2L);reply.setSessionId(10L);reply.setRole("assistant");reply.setMessageOrder(1);
        reply.setMetadata("{\"note_node_id\":\"embedding\",\"note_id\":5}");
        when(messages.selectBatchIds(anyCollection())).thenReturn(List.of(question,reply));
        assertEquals(2,service.nodeDetail(20L,1L,"embedding").messages().size());
        when(messages.selectBatchIds(anyCollection())).thenReturn(List.of());
        assertTrue(service.nodeDetail(20L,1L,"embedding").messages().isEmpty());
    }

    @Test void recommendationsExcludeAskedQuestionsAndReceiveOriginalContext() throws Exception {
        ChatMessage justAsked = new ChatMessage();justAsked.setRole("user");justAsked.setContent("向量有多少维？");
        when(chats.getMessages(20L,1L)).thenReturn(List.of(justAsked));
        when(llm.chat(anyString(),anyString(),eq(1024))).thenReturn("""
                {"questions":["Token 怎么变成向量?","向量有多少维？","Embedding 参数怎么训练？"]}
                """);
        assertEquals(List.of("Embedding 参数怎么训练？"),service.suggestions(20L,1L,"embedding").questions());
        var input = ArgumentCaptor.forClass(String.class);verify(llm).chat(anyString(),input.capture(),eq(1024));
        var json = new ObjectMapper().readTree(input.getValue());
        assertTrue(json.path("originalConversation").toString().contains("table[42]"));
        assertTrue(json.path("askedQuestions").toString().contains("Attention 怎么计算"));
        assertFalse(json.path("questionTree").toString().contains("旧总结"));
    }

    @Test void recommendationFailureDoesNotModifyOrHideConversation() {
        when(llm.chat(anyString(),anyString(),anyInt())).thenReturn("{\"questions\":[123]}");
        assertEquals(503,assertThrows(BusinessException.class,()->service.suggestions(20L,1L,"embedding")).getCode());
        assertEquals(2,service.nodeDetail(20L,1L,"embedding").messages().size());
        verify(notes,never()).update(anyLong(),anyLong(),any());
    }

    @Test void treeRoundTripKeepsSourceIdsAndRejectsMalformedReferences() throws Exception {
        var parsed = NoteTree.parse(note.getTreeJson());
        assertEquals(List.of(1L,2L),parsed.nodes().get(0).sourceMessageIds());
        assertEquals(List.of(1L,2L),NoteTree.parse(new ObjectMapper().writeValueAsString(parsed)).nodes().get(0).sourceMessageIds());
        for (String bad : List.of("[1,1]","[-1]","[1.5]","[\"1\"]","{}"))
            assertThrows(IllegalArgumentException.class,()->NoteTree.parse(note.getTreeJson().replace("[1,2]",bad)));
    }
}
