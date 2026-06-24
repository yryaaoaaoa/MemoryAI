package com.jobai.agent.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.interview.cache.InterviewSessionCache;
import com.jobai.agent.interview.cache.InterviewSessionCache.CachedSession;
import com.jobai.agent.interview.model.InterviewQuestionDTO;
import com.jobai.agent.interview.model.InterviewReportDTO;
import com.jobai.agent.interview.model.InterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 异步评估编排器 — 从 InterviewSessionService 分离，
 * 以便 @Async 通过 Spring AOP 代理正确工作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewEvaluationService {

    private final InterviewPersistenceService persistenceService;
    private final AnswerEvaluationService evaluationService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;

    @Async("taskExecutor")
    public void evaluateAsync(String sessionId, Long userId) {
        log.info("Async evaluation started: sessionId={}, userId={}", sessionId, userId);
        try {
            persistenceService.updateEvaluateStatus(sessionId,
                    InterviewSessionEntity.EvaluateStatus.PROCESSING.name(), null);

            CachedSession session = getCachedSession(sessionId);
            List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

            InterviewReportDTO report = evaluationService.evaluateInterview(
                    sessionId, userId, session.getResumeText(), questions);

            sessionCache.updateStatus(sessionId, "EVALUATED");
            persistenceService.updateSessionStatus(sessionId,
                    InterviewSessionEntity.Status.EVALUATED.name());
            persistenceService.updateEvaluateStatus(sessionId,
                    InterviewSessionEntity.EvaluateStatus.COMPLETED.name(), null);

            log.info("Async evaluation complete: sessionId={}, score={}",
                    sessionId, report.overallScore());
        } catch (Exception e) {
            log.error("Async evaluation failed: sessionId={}, error={}", sessionId, e.getMessage(), e);
            persistenceService.updateEvaluateStatus(sessionId,
                    InterviewSessionEntity.EvaluateStatus.FAILED.name(),
                    e.getMessage() != null && e.getMessage().length() > 500
                            ? e.getMessage().substring(0, 500) : e.getMessage());
        }
    }

    private CachedSession getCachedSession(String sessionId) {
        return sessionCache.getSession(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not in cache during async evaluation: " + sessionId));
    }
}
