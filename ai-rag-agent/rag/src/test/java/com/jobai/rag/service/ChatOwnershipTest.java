package com.jobai.rag.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jobai.common.BusinessException;
import com.jobai.knowledge.entity.Resume;
import com.jobai.knowledge.mapper.ResumeMapper;
import com.jobai.rag.entity.ChatSession;
import com.jobai.rag.entity.ChatMessage;
import com.jobai.rag.mapper.ChatMessageMapper;
import com.jobai.rag.mapper.ChatSessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatOwnershipTest {
    private final ChatSessionMapper sessions = mock(ChatSessionMapper.class);
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final ChatCompressionService compression = mock(ChatCompressionService.class);
    private final ResumeMapper resumes = mock(ResumeMapper.class);
    private final ChatSessionService service = new ChatSessionService(sessions, messages, compression, resumes);

    @BeforeEach
    void initMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), ChatSession.class);
    }

    private ChatSession session(Long owner, Long resumeId) {
        ChatSession session = new ChatSession();
        session.setId(10L);
        session.setUserId(owner);
        session.setResumeId(resumeId);
        return session;
    }

    private Resume resume(Long owner) {
        Resume resume = new Resume();
        resume.setId(9L);
        resume.setUserId(owner);
        resume.setRawText("private resume text");
        return resume;
    }

    @Test
    void foreignSessionCannotWriteOrReadHistoryOrResume() {
        when(sessions.selectForUpdate(10L)).thenReturn(session(2L, 9L));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.beginUserTurn(10L, 1L, "question"));
        assertEquals(403, error.getCode());
        verifyNoInteractions(messages, compression, resumes);
    }

    @Test
    void ownSessionCanStartTurnAndReadOwnResume() {
        ChatSession session = session(1L, 9L);
        when(sessions.selectForUpdate(10L)).thenReturn(session);
        when(sessions.selectById(10L)).thenReturn(session);
        when(resumes.selectById(9L)).thenReturn(resume(1L));
        when(messages.countUserMessages(10L)).thenReturn(1);
        var result = service.beginUserTurn(10L, 1L, "question");
        assertEquals("private resume text", result.resumeText());
        verify(messages).insert(argThat((ChatMessage m) -> m.getSessionId().equals(10L)
                && m.getContent().equals("question") && m.getRole().equals("user")));
    }

    @Test
    void legacyForeignResumeAssociationIsNeverInjectedIntoChat() {
        ChatSession session = session(1L, 9L);
        when(sessions.selectForUpdate(10L)).thenReturn(session);
        when(sessions.selectById(10L)).thenReturn(session);
        when(resumes.selectById(9L)).thenReturn(resume(2L));
        when(messages.countUserMessages(10L)).thenReturn(1);
        assertEquals("", service.beginUserTurn(10L, 1L, "question").resumeText());
    }

    @Test
    void foreignResumeCannotBeAttachedToOwnSession() {
        when(sessions.selectById(10L)).thenReturn(session(1L, null));
        when(resumes.selectById(9L)).thenReturn(resume(2L));
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.setSessionResume(10L, 9L, 1L)).getCode());
        verify(sessions, never()).update(any(), any());
    }

    @Test
    void missingResumeCannotBeAttached() {
        when(sessions.selectById(10L)).thenReturn(session(1L, null));
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.setSessionResume(10L, 9L, 1L)).getCode());
        verify(sessions, never()).update(any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void detachUsesExplicitSqlNullInsteadOfIgnoredEntityNull() {
        when(sessions.selectById(10L)).thenReturn(session(1L, 9L));
        service.setSessionResume(10L, null, 1L);
        ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(sessions).update(isNull(), update.capture());
        assertTrue(update.getValue().getSqlSet().contains("resume_id="));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(null));
        verifyNoInteractions(resumes);
    }

    @Test
    void asyncResponseCannotBeWrittenToAForeignOrDeletedSession() {
        when(sessions.selectForUpdate(10L)).thenReturn(session(2L, null));
        assertThrows(BusinessException.class,
                () -> service.batchAppend(10L, 1L, List.of(Map.of("role", "assistant", "content", "answer"))));
        when(sessions.selectForUpdate(10L)).thenReturn(null);
        assertThrows(BusinessException.class,
                () -> service.batchAppend(10L, 1L, List.of(Map.of("role", "assistant", "content", "answer"))));
        verifyNoInteractions(messages);
    }
}
