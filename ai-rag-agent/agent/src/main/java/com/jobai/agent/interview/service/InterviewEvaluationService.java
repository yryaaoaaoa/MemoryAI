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
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.ArrayList;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.knowledge.mapper.ResumeMapper;

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
    private final ResumeMapper resumeMapper;

    @PostConstruct
    public void recoverInterruptedEvaluations() {
        persistenceService.recoverInterruptedEvaluations();
    }

    @Async("taskExecutor")
    public void evaluateAsync(String sessionId, Long userId) {
        log.info("Async evaluation started: sessionId={}, userId={}", sessionId, userId);
        // Claim the durable pending state before calling the model, including on retries.
        if (!persistenceService.startEvaluation(sessionId, userId)) return;
        try {
            InterviewSessionEntity entity = persistenceService.findBySessionId(sessionId).orElseThrow();
            if (entity.getResumeId() != null && entity.getResumeText() == null) {
                var resume = resumeMapper.selectById(entity.getResumeId());
                if (resume == null || !userId.equals(resume.getUserId())) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "原简历已不可用，且该面试没有简历快照");
                }
                entity.setResumeText(resume.getRawText());
            }

            CachedSession session = loadPersistedSession(sessionId, entity);
            List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

            InterviewReportDTO report = evaluationService.evaluateInterview(
                    sessionId, userId, session.getResumeText(), questions);

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

    private CachedSession loadPersistedSession(String sessionId, InterviewSessionEntity entity) throws Exception {
        // Use the durable snapshot and answers: Redis may be absent or lag behind a committed draft.
        List<InterviewQuestionDTO> questions = new ArrayList<>(objectMapper.readValue(entity.getQuestionsJson(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, InterviewQuestionDTO.class)));
        for (var answer : persistenceService.findAnswersBySessionId(sessionId)) {
            int index = answer.getQuestionIndex();
            if (index >= 0 && index < questions.size()) {
                questions.set(index, questions.get(index).withAnswer(answer.getUserAnswer()));
            }
        }
        CachedSession session = new CachedSession();
        session.setSessionId(sessionId);
        session.setResumeText(entity.getResumeText() != null ? entity.getResumeText() : "");
        session.setQuestionsJson(objectMapper.writeValueAsString(questions));
        return session;
    }
}
