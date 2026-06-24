package com.jobai.agent.interview.service;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.interview.model.*;
import com.jobai.agent.interview.mapper.InterviewAnswerMapper;
import com.jobai.agent.interview.mapper.InterviewSessionMapper;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.auth.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPersistenceService {

    private static final int MAX_HISTORICAL_QUESTIONS = 60;

    private final InterviewSessionMapper sessionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final ObjectMapper objectMapper;

    // =================== Session CRUD ===================

    @Transactional(rollbackFor = Exception.class)
    public void saveSession(String sessionId, Long resumeId, int totalQuestions,
                            List<InterviewQuestionDTO> questions,
                            String llmProvider, String skillId, String difficulty,
                            String mode) {
        try {
            InterviewSessionEntity entity = new InterviewSessionEntity();
            entity.setSessionId(sessionId);
            entity.setResumeId(resumeId);
            entity.setTotalQuestions(totalQuestions);
            entity.setCurrentQuestionIndex(0);
            entity.setStatus(InterviewSessionEntity.Status.CREATED.name());
            entity.setQuestionsJson(objectMapper.writeValueAsString(questions));
            entity.setLlmProvider(llmProvider != null ? llmProvider : "deepseek");
            entity.setSkillId(skillId != null ? skillId : "java-backend");
            entity.setDifficulty(difficulty != null ? difficulty : "mid");
            entity.setMode(mode != null ? mode : "batch");

            // 设置用户ID
            Long userId = AuthContext.get();
            if (userId != null) {
                entity.setUserId(userId);
            }

            sessionMapper.insert(entity);
            log.info("Interview session saved: sessionId={}, skillId={}, resumeId={}, userId={}",
                    sessionId, skillId, resumeId, userId);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目列表失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateSessionStatus(String sessionId, String status) {
        findBySessionId(sessionId).ifPresent(entity -> {
            entity.setStatus(status);
            if (InterviewSessionEntity.Status.COMPLETED.name().equals(status)
                    || InterviewSessionEntity.Status.EVALUATED.name().equals(status)) {
                entity.setCompletedAt(LocalDateTime.now());
            }
            sessionMapper.updateById(entity);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateQuestionsJson(String sessionId, List<InterviewQuestionDTO> questions) {
        findBySessionId(sessionId).ifPresent(entity -> {
            try {
                entity.setQuestionsJson(objectMapper.writeValueAsString(questions));
                sessionMapper.updateById(entity);
            } catch (JsonProcessingException e) {
                log.warn("Failed to update questions JSON: sessionId={}", sessionId, e);
            }
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentQuestionIndex(String sessionId, int index) {
        findBySessionId(sessionId).ifPresent(entity -> {
            entity.setCurrentQuestionIndex(index);
            sessionMapper.updateById(entity);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateContextSummary(String sessionId, String contextSummary) {
        findBySessionId(sessionId).ifPresent(entity -> {
            entity.setContextSummary(contextSummary);
            sessionMapper.updateById(entity);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateEvaluateStatus(String sessionId, String status, String error) {
        findBySessionId(sessionId).ifPresent(entity -> {
            entity.setEvaluateStatus(status);
            entity.setEvaluateError(error);
            sessionMapper.updateById(entity);
        });
    }

    // =================== Answer CRUD ===================

    @Transactional(rollbackFor = Exception.class)
    public void saveAnswer(String sessionId, int questionIndex, String question, String category,
                           String userAnswer, int score, String feedback) {
        InterviewAnswerEntity answer = answerMapper
                .findBySessionIdAndQuestionIndex(sessionId, questionIndex)
                .orElseGet(() -> {
                    InterviewAnswerEntity created = new InterviewAnswerEntity();
                    created.setSessionId(sessionId);
                    created.setQuestionIndex(questionIndex);
                    return created;
                });

        answer.setQuestion(question);
        answer.setCategory(category);
        answer.setUserAnswer(userAnswer);
        answer.setScore(score);
        answer.setFeedback(feedback);

        if (answer.getId() == null) {
            answerMapper.insert(answer);
        } else {
            answerMapper.updateById(answer);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveReport(String sessionId, InterviewReportDTO report) {
        findBySessionId(sessionId).ifPresent(session -> {
            try {
                session.setOverallScore(report.overallScore());
                session.setOverallFeedback(report.overallFeedback());
                session.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
                session.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
                session.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
                session.setStatus(InterviewSessionEntity.Status.EVALUATED.name());
                session.setCompletedAt(LocalDateTime.now());
                sessionMapper.updateById(session);

                // Update each answer with evaluation + reference answer
                List<InterviewAnswerEntity> existingAnswers = answerMapper.findBySessionId(sessionId);
                java.util.Map<Integer, InterviewAnswerEntity> answerMap = existingAnswers.stream()
                        .collect(Collectors.toMap(InterviewAnswerEntity::getQuestionIndex, a -> a, (a1, a2) -> a1));

                java.util.Map<Integer, InterviewReportDTO.ReferenceAnswer> refMap = new java.util.HashMap<>();
                if (report.referenceAnswers() != null) {
                    refMap = report.referenceAnswers().stream()
                            .collect(Collectors.toMap(
                                    InterviewReportDTO.ReferenceAnswer::questionIndex, r -> r, (r1, r2) -> r1));
                }

                for (InterviewReportDTO.QuestionEvaluation eval : report.questionDetails()) {
                    InterviewAnswerEntity answer = answerMap.get(eval.questionIndex());
                    if (answer == null) {
                        answer = new InterviewAnswerEntity();
                        answer.setSessionId(sessionId);
                        answer.setQuestionIndex(eval.questionIndex());
                        answer.setQuestion(eval.question());
                        answer.setCategory(eval.category());
                        answer.setUserAnswer(eval.userAnswer());
                    }
                    answer.setScore(eval.score());
                    answer.setFeedback(eval.feedback());

                    InterviewReportDTO.ReferenceAnswer ref = refMap.get(eval.questionIndex());
                    if (ref != null) {
                        answer.setReferenceAnswer(ref.referenceAnswer());
                        if (ref.keyPoints() != null && !ref.keyPoints().isEmpty()) {
                            answer.setKeyPointsJson(objectMapper.writeValueAsString(ref.keyPoints()));
                        }
                    }

                    if (answer.getId() == null) {
                        answerMapper.insert(answer);
                    } else {
                        answerMapper.updateById(answer);
                    }
                }
                log.info("Interview report saved: sessionId={}, score={}", sessionId, report.overallScore());
            } catch (JacksonException e) {
                log.error("Failed to serialize report JSON: sessionId={}", sessionId, e);
            }
        });
    }

    // =================== Queries ===================

    public Optional<InterviewSessionEntity> findBySessionId(String sessionId) {
        return sessionMapper.findBySessionId(sessionId);
    }

    public List<InterviewSessionEntity> findByResumeId(Long resumeId) {
        return sessionMapper.findByResumeId(resumeId);
    }

    public List<InterviewSessionEntity> findAll() {
        return sessionMapper.findAllOrderByCreatedAtDesc();
    }

    public Optional<InterviewSessionEntity> findUnfinishedSession(Long resumeId) {
        return sessionMapper.findUnfinishedByResumeId(resumeId);
    }

    public List<InterviewAnswerEntity> findAnswersBySessionId(String sessionId) {
        return answerMapper.findBySessionId(sessionId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionBySessionId(String sessionId) {
        findBySessionId(sessionId).ifPresentOrElse(
                session -> {
                    // Delete all associated answers
                    List<InterviewAnswerEntity> answers = answerMapper.findBySessionId(sessionId);
                    for (InterviewAnswerEntity answer : answers) {
                        answerMapper.deleteById(answer.getId());
                    }
                    sessionMapper.deleteById(session.getId());
                    log.info("Deleted interview session: sessionId={}", sessionId);
                },
                () -> {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在");
                });
    }

    // =================== Historical Questions ===================

    public List<HistoricalQuestion> getHistoricalQuestions(String skillId, Long resumeId) {
        List<InterviewSessionEntity> sessions;
        if (resumeId != null) {
            sessions = sessionMapper.findRecentByResumeIdAndSkillId(resumeId, skillId);
        } else {
            sessions = sessionMapper.findRecentBySkillId(skillId);
        }

        log.info("Loading historical questions: skillId={}, resumeId={}, sessions={}",
                skillId, resumeId, sessions.size());

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        return sessions.stream()
                .map(InterviewSessionEntity::getQuestionsJson)
                .filter(json -> json != null && !json.isEmpty())
                .flatMap(json -> {
                    try {
                        List<InterviewQuestionDTO> questions = objectMapper.readValue(json,
                                new TypeReference<List<InterviewQuestionDTO>>() {});
                        return questions.stream()
                                .filter(q -> !q.isFollowUp())
                                .map(q -> new HistoricalQuestion(q.question(), q.type(), q.topicSummary()));
                    } catch (Exception e) {
                        log.error("Failed to parse historical questions JSON", e);
                        return Stream.empty();
                    }
                })
                .filter(hq -> seen.add(hq.question()))
                .limit(MAX_HISTORICAL_QUESTIONS)
                .toList();
    }
}
