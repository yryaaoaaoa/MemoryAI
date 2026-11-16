package com.jobai.agent.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.interview.mapper.*;
import com.jobai.agent.interview.model.*;
import com.jobai.common.BusinessException;
import com.jobai.common.auth.AuthContext;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InterviewPersistenceServiceTest {
    private final InterviewSessionMapper sessions = mock(InterviewSessionMapper.class);
    private final InterviewAnswerMapper answers = mock(InterviewAnswerMapper.class);
    private final InterviewPersistenceService service = new InterviewPersistenceService(sessions, answers, new ObjectMapper());

    @AfterEach void clear() { AuthContext.clear(); }

    @Test void allUserQueriesPassCurrentOwnerToSql() {
        AuthContext.set(7L, "user");
        service.findAll(); service.findByResumeId(12L); service.findUnfinishedSession(12L);
        service.getHistoricalQuestions("java-backend", null); service.getHistoricalQuestions("java-backend", 12L);
        verify(sessions).findAllOrderByCreatedAtDesc(7L);
        verify(sessions).findByResumeId(12L, 7L);
        verify(sessions).findUnfinishedByResumeId(12L, 7L);
        verify(sessions).findRecentBySkillId("java-backend", 7L);
        verify(sessions).findRecentByResumeIdAndSkillId(12L, "java-backend", 7L);
    }

    @Test void rejectsUnauthenticatedListAndHistory() {
        assertThrows(BusinessException.class, service::findAll);
        assertThrows(BusinessException.class, () -> service.getHistoricalQuestions("java-backend", null));
        verifyNoInteractions(sessions);
    }

    @Test void databaseStatusRejectsLateDraftAfterEvaluationEvenWithStaleRequestState() {
        var entity = new InterviewSessionEntity(); entity.setStatus("EVALUATED");
        when(sessions.findBySessionIdForUpdate("session")).thenReturn(Optional.of(entity));
        assertThrows(BusinessException.class, () -> service.saveAnswer("session", 0, "Q", "Java", "late", 0, null));
        verifyNoInteractions(answers);
    }

    @Test void lateStartedStatusCannotReopenCompletedSession() {
        var entity = new InterviewSessionEntity(); entity.setStatus("COMPLETED");
        when(sessions.findBySessionIdForUpdate("session")).thenReturn(Optional.of(entity));
        assertThrows(BusinessException.class, () -> service.updateSessionStatus("session", "IN_PROGRESS"));
        assertEquals("COMPLETED", entity.getStatus());
        verify(sessions, never()).updateById(any(InterviewSessionEntity.class));
    }

    @Test void progressSavesBothQuestionListAndIndexInOneUpdate() throws Exception {
        var entity = new InterviewSessionEntity(); entity.setStatus("CREATED"); entity.setId(1L);
        when(sessions.findBySessionIdForUpdate("session")).thenReturn(Optional.of(entity));
        service.saveProgress("session", List.of(InterviewQuestionDTO.create(0, "Q1", "JAVA", "Java"), InterviewQuestionDTO.create(1, "Q2", "JAVA", "Java")), 1);
        verify(sessions).updateById(entity);
        assertEquals(1, entity.getCurrentQuestionIndex()); assertEquals("IN_PROGRESS", entity.getStatus());
        assertEquals(2, new ObjectMapper().readTree(entity.getQuestionsJson()).size());
    }

    @Test void savesCategoryMarksAlongsideOverallReport() throws Exception {
        var entity = new InterviewSessionEntity(); entity.setSessionId("session");
        when(sessions.findBySessionIdForUpdate("session")).thenReturn(Optional.of(entity));
        var report = new InterviewReportDTO("session", 1, 90, List.of(new InterviewReportDTO.CategoryScore("Java", 90, 1)), List.of(), "Good", List.of(), List.of(), List.of());
        service.saveReport("session", report);
        assertEquals(90, new ObjectMapper().readTree(entity.getCategoryScoresJson()).get(0).get("score").asInt());
        assertEquals("EVALUATED", entity.getStatus());
        verify(sessions).updateById(entity);
    }

    @Test void completionPersistsFinalProgressAndPendingEvaluationTogether() throws Exception {
        var entity = new InterviewSessionEntity(); entity.setStatus("IN_PROGRESS"); entity.setCurrentQuestionIndex(0);
        when(sessions.findBySessionIdForUpdate("session")).thenReturn(Optional.of(entity));
        service.completeSession("session", List.of(InterviewQuestionDTO.create(0, "Q", "JAVA", "Java").withAnswer("A")), 1);
        assertEquals("COMPLETED", entity.getStatus()); assertEquals("PENDING", entity.getEvaluateStatus());
        assertEquals(1, entity.getCurrentQuestionIndex());
        assertEquals("A", new ObjectMapper().readTree(entity.getQuestionsJson()).get(0).get("userAnswer").asText());
        verify(sessions, times(1)).updateById(entity);
    }

    @Test void lateContextSummaryCannotOverwriteCompletedStatus() {
        var entity = new InterviewSessionEntity(); entity.setStatus("COMPLETED");
        when(sessions.findBySessionIdForUpdate("session")).thenReturn(Optional.of(entity));
        assertThrows(BusinessException.class, () -> service.updateContextSummary("session", "late summary"));
        verify(sessions, never()).updateById(any(InterviewSessionEntity.class));
    }

    @Test void evaluationStatusUpdateLocksRowAndPreservesItsLatestStatus() {
        var entity = new InterviewSessionEntity(); entity.setStatus("EVALUATED");
        when(sessions.findBySessionIdForUpdate("session")).thenReturn(Optional.of(entity));
        service.updateEvaluateStatus("session", "COMPLETED", null);
        assertEquals("EVALUATED", entity.getStatus());
        verify(sessions).findBySessionIdForUpdate("session");
        verify(sessions, never()).findBySessionId(anyString());
    }

    @Test void failedEvaluationCanBeRetriedAndClaimedOnlyOnce() {
        var entity = new InterviewSessionEntity(); entity.setUserId(7L); entity.setStatus("COMPLETED");
        entity.setEvaluateStatus("FAILED"); entity.setEvaluateError("old failure");
        when(sessions.findBySessionIdForUpdate("session")).thenReturn(Optional.of(entity));
        assertEquals(404, assertThrows(BusinessException.class, () -> service.requestEvaluationRetry("session", 8L)).getCode());
        service.requestEvaluationRetry("session", 7L);
        assertEquals("PENDING", entity.getEvaluateStatus()); assertEquals("", entity.getEvaluateError());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.requestEvaluationRetry("session", 7L)).getCode());
        assertTrue(service.startEvaluation("session", 7L));
        assertFalse(service.startEvaluation("session", 7L));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.requestEvaluationRetry("session", 7L)).getCode());
    }

    @Test void savesFullCustomDirectionSnapshotWithTheSession() throws Exception {
        AuthContext.set(7L, "user");
        var skill = new com.jobai.agent.interview.skill.InterviewSkillService.SkillDTO("custom", "JD", "岗位方向",
                List.of(new com.jobai.agent.interview.skill.InterviewSkillService.SkillCategoryDTO("JAVA", "Java", "CORE", "java.md", true)),
                false, "original JD", null, null);
        service.saveSession("session", null, 1, List.of(InterviewQuestionDTO.create(0, "Q", "JAVA", "Java")),
                null, "custom", "mid", "batch", null, null, skill);
        var saved = org.mockito.ArgumentCaptor.forClass(InterviewSessionEntity.class);
        verify(sessions).insert(saved.capture());
        assertEquals(skill, new ObjectMapper().readValue(saved.getValue().getSkillSnapshotJson(), skill.getClass()));
        assertEquals("", saved.getValue().getResumeText());
    }
}
