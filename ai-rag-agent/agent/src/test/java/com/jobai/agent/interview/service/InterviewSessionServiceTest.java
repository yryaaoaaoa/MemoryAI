package com.jobai.agent.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.interview.cache.InterviewSessionCache;
import com.jobai.agent.interview.cache.InterviewSessionCache.CachedSession;
import com.jobai.agent.interview.model.*;
import com.jobai.agent.interview.skill.InterviewSkillService;
import com.jobai.common.BusinessException;
import com.jobai.common.auth.AuthContext;
import com.jobai.knowledge.entity.Resume;
import com.jobai.knowledge.mapper.ResumeMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InterviewSessionServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final InterviewPersistenceService persistence = mock(InterviewPersistenceService.class);
    private final InterviewSessionCache cache = mock(InterviewSessionCache.class);
    private final InterviewEvaluationService evaluation = mock(InterviewEvaluationService.class);
    private final InterviewQuestionService questionService = mock(InterviewQuestionService.class);
    private final ResumeMapper resumes = mock(ResumeMapper.class);
    private final DynamicQuestionService dynamic = mock(DynamicQuestionService.class);
    private final InterviewSkillService skills = mock(InterviewSkillService.class);
    private final InterviewSessionService service = new InterviewSessionService(
            questionService, persistence, evaluation, cache, resumes, json, dynamic, skills);

    @BeforeEach void login() { AuthContext.set(7L, "user"); }
    @AfterEach void logout() { AuthContext.clear(); }

    private InterviewSessionEntity entity(String status) throws Exception {
        var entity = new InterviewSessionEntity();
        entity.setSessionId("session"); entity.setUserId(7L); entity.setStatus(status);
        entity.setMode("dynamic"); entity.setSkillId("java-backend"); entity.setDifficulty("mid");
        entity.setCurrentQuestionIndex(0); entity.setTotalQuestions(6);
        entity.setQuestionsJson(json.writeValueAsString(List.of(InterviewQuestionDTO.create(0, "Q1", "JAVA", "Java"))));
        when(persistence.findBySessionId("session")).thenReturn(Optional.of(entity));
        return entity;
    }

    @Test void deniesForeignSessionsBeforeReadingEvenWarmCache() throws Exception {
        entity("IN_PROGRESS").setUserId(8L);
        assertThrows(BusinessException.class, () -> service.getSession("session"));
        assertThrows(BusinessException.class, () -> service.getCurrentQuestion("session"));
        assertThrows(BusinessException.class, () -> service.getReport("session"));
        assertThrows(BusinessException.class, () -> service.submitAnswer(new SubmitAnswerRequest("session", 0, "answer")));
        assertThrows(BusinessException.class, () -> service.saveAnswer(new SubmitAnswerRequest("session", 0, "answer")));
        assertThrows(BusinessException.class, () -> service.completeInterview("session"));
        assertThrows(BusinessException.class, () -> service.deleteSession("session"));
        verifyNoInteractions(cache, evaluation);
        verify(persistence, never()).deleteSessionBySessionId(anyString());
    }

    @Test void rejectsForeignResumeEvenWithInlineTextAndForceCreate() {
        var resume = new Resume(); resume.setUserId(8L);
        when(resumes.selectById(23L)).thenReturn(resume);
        var request = new CreateInterviewRequest("inline", 6, 23L, true, null, "java-backend", "mid", null, "batch");
        assertThrows(BusinessException.class, () -> service.createSession(request));
        assertThrows(BusinessException.class, () -> service.findUnfinishedSession(23L));
        verifyNoInteractions(questionService, dynamic, cache);
    }

    @Test void rejectsDraftAndSubmitAfterCompletionBeforeAnyCacheWrite() throws Exception {
        entity("EVALUATED");
        var request = new SubmitAnswerRequest("session", 0, "changed");
        assertThrows(BusinessException.class, () -> service.saveAnswer(request));
        assertThrows(BusinessException.class, () -> service.submitAnswer(request));
        verifyNoInteractions(cache);
        verify(persistence, never()).saveAnswer(anyString(), anyInt(), any(), any(), any(), anyInt(), any());
    }

    @Test void persistsDynamicQuestionAndIndexBeforeUpdatingCache() throws Exception {
        var entity = entity("IN_PROGRESS");
        entity.setResumeText(""); entity.setAllocationsJson("{\"JAVA\":6}");
        var cached = new CachedSession(); cached.setSessionId("session"); cached.setStatus("IN_PROGRESS");
        cached.setMode("dynamic"); cached.setSkillId("java-backend"); cached.setDifficulty("mid");
        cached.setResumeText("");
        cached.setQuestionsJson(entity.getQuestionsJson()); cached.setAllocationsJson("{\"JAVA\":6}");
        when(cache.getSession("session")).thenReturn(Optional.of(cached));
        when(dynamic.decide(isNull(InterviewSkillService.SkillDTO.class), eq("mid"), isNull(), eq(""), anyList(), anyList(), isNull(), isNull()))
                .thenReturn(new DynamicQuestionService.NextAction("next_topic", "Q2", "JAVA", "Java", "topic", "next"));
        when(dynamic.buildRecentWindow(anyList())).thenReturn(new DynamicQuestionService.WindowResult("", 0));
        var result = service.submitAnswer(new SubmitAnswerRequest("session", 0, "A1"));
        assertTrue(result.hasNext());
        var ordered = inOrder(persistence, cache);
        ordered.verify(persistence).saveProgress(eq("session"), argThat(qs -> qs.size() == 2 && "A1".equals(qs.get(0).userAnswer())), eq(1));
        ordered.verify(cache).updateQuestions(eq("session"), argThat(qs -> qs.size() == 2));
        ordered.verify(cache).updateCurrentIndex("session", 1);
    }

    @Test void restoresDynamicQuestionsIndexAllocationAndInlineResume() throws Exception {
        var entity = entity("IN_PROGRESS");
        entity.setCurrentQuestionIndex(1); entity.setResumeText("inline resume"); entity.setAllocationsJson("{\"JAVA\":6}");
        entity.setQuestionsJson(json.writeValueAsString(List.of(InterviewQuestionDTO.create(0, "Q1", "JAVA", "Java"), InterviewQuestionDTO.create(1, "Q2", "JAVA", "Java"))));
        var savedAnswer = new InterviewAnswerEntity(); savedAnswer.setQuestionIndex(0); savedAnswer.setUserAnswer("A1");
        when(persistence.findAnswersBySessionId("session")).thenReturn(List.of(savedAnswer));
        when(cache.getSession("session")).thenReturn(Optional.empty());
        doAnswer(call -> {
            var restored = new CachedSession(); restored.setSessionId("session"); restored.setCurrentIndex(call.getArgument(4));
            restored.setQuestionsJson(json.writeValueAsString(call.getArgument(3))); restored.setMainQuestionCount(call.getArgument(6));
            restored.setResumeText(call.getArgument(1)); restored.setMode(call.getArgument(10));
            when(cache.getSession("session")).thenReturn(Optional.of(restored));
            return null;
        }).when(cache).saveSession(eq("session"), eq("inline resume"), isNull(), anyList(), eq(1), eq("IN_PROGRESS"), eq(6), eq("{\"JAVA\":6}"), eq("java-backend"), eq("mid"), eq("dynamic"));
        var restored = service.getSession("session");
        assertEquals(1, restored.currentQuestionIndex()); assertEquals(2, restored.questions().size());
        assertEquals("A1", restored.questions().get(0).userAnswer()); assertEquals(6, restored.totalQuestions());
        assertEquals("inline resume", restored.resumeText());
    }

    @Test void reportReturnsPersistedCategoryMarks() throws Exception {
        var entity = entity("EVALUATED");
        entity.setCategoryScoresJson("[{\"category\":\"Java\",\"score\":85,\"questionCount\":2}]");
        when(persistence.findAnswersBySessionId("session")).thenReturn(List.of());
        assertEquals(List.of(new InterviewReportDTO.CategoryScore("Java", 85, 2)), service.getReport("session").categoryScores());
    }

    @Test void deletedResumeDoesNotHideOwnedHistoricalReportWithSnapshot() throws Exception {
        var entity = entity("EVALUATED"); entity.setResumeId(23L); entity.setResumeText("saved resume");
        when(persistence.findAnswersBySessionId("session")).thenReturn(List.of());
        assertNotNull(service.getReport("session"));
        verifyNoInteractions(resumes);
    }

    @Test void failedReportExposesExplicitRetryableStatus() throws Exception {
        var entity = entity("COMPLETED"); entity.setEvaluateStatus("FAILED"); entity.setEvaluateError("模型服务暂不可用");
        assertEquals(Map.of("ready", false, "status", "FAILED", "error", "模型服务暂不可用"), service.getReportResponse("session"));
        service.retryEvaluation("session");
        var order = inOrder(persistence, evaluation);
        order.verify(persistence).requestEvaluationRetry("session", 7L);
        order.verify(evaluation).evaluateAsync("session", 7L);
    }

    @Test void oldReportRebuildsCategoryMarksFromSavedAnswers() throws Exception {
        entity("EVALUATED");
        var first = new InterviewAnswerEntity(); first.setQuestionIndex(0); first.setCategory("Java"); first.setScore(80);
        var second = new InterviewAnswerEntity(); second.setQuestionIndex(1); second.setCategory("Java"); second.setScore(100);
        when(persistence.findAnswersBySessionId("session")).thenReturn(List.of(first, second));
        assertEquals(List.of(new InterviewReportDTO.CategoryScore("Java", 90, 2)), service.getReport("session").categoryScores());
    }

    @Test void rejectsLegacySessionPointingAtAnotherUsersResume() throws Exception {
        entity("IN_PROGRESS").setResumeId(23L);
        var foreignResume = new Resume(); foreignResume.setUserId(8L);
        when(resumes.selectById(23L)).thenReturn(foreignResume);
        assertThrows(BusinessException.class, () -> service.getSession("session"));
        assertThrows(BusinessException.class, () -> service.getReport("session"));
        assertThrows(BusinessException.class, () -> service.submitAnswer(new SubmitAnswerRequest("session", 0, "A")));
        verifyNoInteractions(cache, evaluation);
    }

    @Test void ignoresOldCachedResumeContextEvenWhenQuestionsAndIndexMatch() throws Exception {
        var entity = entity("IN_PROGRESS"); entity.setResumeText("owned snapshot");
        var stale = new CachedSession(); stale.setSessionId("session"); stale.setQuestionsJson(entity.getQuestionsJson());
        stale.setCurrentIndex(0); stale.setResumeText("stale private context");
        stale.setMode("dynamic"); stale.setSkillId("java-backend"); stale.setDifficulty("mid");
        when(cache.getSession("session")).thenReturn(Optional.of(stale));
        when(persistence.findAnswersBySessionId("session")).thenReturn(List.of());
        doAnswer(call -> {
            var restored = new CachedSession(); restored.setSessionId("session");
            restored.setQuestionsJson(json.writeValueAsString(call.getArgument(3)));
            restored.setResumeText(call.getArgument(1)); restored.setMainQuestionCount(call.getArgument(6));
            restored.setMode(call.getArgument(10));
            when(cache.getSession("session")).thenReturn(Optional.of(restored));
            return null;
        }).when(cache).saveSession(eq("session"), eq("owned snapshot"), isNull(), anyList(), eq(0), eq("IN_PROGRESS"), eq(6), isNull(), eq("java-backend"), eq("mid"), eq("dynamic"));
        assertEquals("owned snapshot", service.getSession("session").resumeText());
    }
}
