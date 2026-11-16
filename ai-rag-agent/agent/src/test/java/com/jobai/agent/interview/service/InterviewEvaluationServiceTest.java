package com.jobai.agent.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.interview.cache.InterviewSessionCache;
import com.jobai.agent.interview.model.*;
import com.jobai.common.BusinessException;
import com.jobai.common.auth.AuthContext;
import com.jobai.knowledge.mapper.ResumeMapper;
import com.jobai.knowledge.entity.Resume;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InterviewEvaluationServiceTest {
    private final InterviewPersistenceService persistence = mock(InterviewPersistenceService.class);
    private final AnswerEvaluationService evaluator = mock(AnswerEvaluationService.class);
    private final InterviewSessionCache cache = mock(InterviewSessionCache.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ResumeMapper resumes = mock(ResumeMapper.class);
    private final InterviewEvaluationService service = new InterviewEvaluationService(persistence, evaluator, cache, json, resumes);

    @Test void rejectsWrongExplicitOwnerBeforeAnyEvaluationWrite() {
        when(persistence.startEvaluation("session", 7L)).thenThrow(new BusinessException(404, "面试会话不存在"));
        assertThrows(BusinessException.class, () -> service.evaluateAsync("session", 7L));
        verify(persistence, never()).updateEvaluateStatus(anyString(), anyString(), any());
        verifyNoInteractions(cache, evaluator);
    }

    @Test void evaluatesFromDatabaseAfterCacheLossWithoutAuthThreadLocal() throws Exception {
        AuthContext.clear();
        var entity = new InterviewSessionEntity(); entity.setUserId(7L); entity.setResumeText("resume");
        entity.setQuestionsJson(json.writeValueAsString(List.of(InterviewQuestionDTO.create(0, "Q", "JAVA", "Java"))));
        when(persistence.findBySessionId("session")).thenReturn(Optional.of(entity));
        when(persistence.startEvaluation("session", 7L)).thenReturn(true);
        var answer = new InterviewAnswerEntity(); answer.setQuestionIndex(0); answer.setUserAnswer("A");
        when(persistence.findAnswersBySessionId("session")).thenReturn(List.of(answer));
        when(cache.getSession("session")).thenReturn(Optional.empty());
        when(evaluator.evaluateInterview(eq("session"), eq(7L), eq("resume"), anyList()))
                .thenReturn(new InterviewReportDTO("session", 1, 90, List.of(), List.of(), "", List.of(), List.of(), List.of()));
        service.evaluateAsync("session", 7L);
        verify(evaluator).evaluateInterview(eq("session"), eq(7L), eq("resume"), argThat(qs -> "A".equals(qs.get(0).userAnswer())));
        verify(persistence, never()).updateEvaluateStatus(eq("session"), eq("FAILED"), any());
    }

    @Test void marksLegacyMissingOrForeignResumeAsFailedRatherThanLeavingPending() {
        var entity = new InterviewSessionEntity(); entity.setUserId(7L); entity.setResumeId(23L);
        var foreignResume = new Resume(); foreignResume.setUserId(8L);
        when(persistence.findBySessionId("session")).thenReturn(Optional.of(entity));
        when(persistence.startEvaluation("session", 7L)).thenReturn(true);
        when(resumes.selectById(23L)).thenReturn(foreignResume);
        service.evaluateAsync("session", 7L);
        verify(persistence).updateEvaluateStatus(eq("session"), eq("FAILED"), contains("简历快照"));
        verifyNoInteractions(evaluator, cache);
    }

    @Test void usesResumeSnapshotAfterOriginalResumeWasDeleted() throws Exception {
        var entity = new InterviewSessionEntity(); entity.setUserId(7L); entity.setResumeId(23L);
        entity.setResumeText("saved resume");
        entity.setQuestionsJson(json.writeValueAsString(List.of(InterviewQuestionDTO.create(0, "Q", "JAVA", "Java"))));
        when(persistence.startEvaluation("session", 7L)).thenReturn(true);
        when(persistence.findBySessionId("session")).thenReturn(Optional.of(entity));
        when(evaluator.evaluateInterview(eq("session"), eq(7L), eq("saved resume"), anyList()))
                .thenReturn(new InterviewReportDTO("session", 1, 90, List.of(), List.of(), "", List.of(), List.of(), List.of()));
        service.evaluateAsync("session", 7L);
        verify(evaluator).evaluateInterview(eq("session"), eq(7L), eq("saved resume"), anyList());
        verifyNoInteractions(resumes);
    }

    @Test void ignoresDuplicateEvaluationThatCannotClaimPendingState() {
        when(persistence.startEvaluation("session", 7L)).thenReturn(false);
        service.evaluateAsync("session", 7L);
        verifyNoInteractions(evaluator, resumes, cache);
        verify(persistence, never()).findBySessionId(anyString());
    }
}
