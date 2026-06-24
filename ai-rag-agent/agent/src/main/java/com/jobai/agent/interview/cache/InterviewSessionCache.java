package com.jobai.agent.interview.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.interview.model.InterviewQuestionDTO;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis cache for active interview sessions — 24h TTL.
 * Keys:
 * <ul>
 *   <li>{@code interview:session:{sessionId}} → JSON (CachedSession)</li>
 *   <li>{@code interview:resume:{resumeId}} → sessionId (reverse index)</li>
 * </ul>
 */
@Slf4j
@Service
public class InterviewSessionCache {

    private static final String SESSION_KEY = "interview:session:";
    private static final String RESUME_KEY = "interview:resume:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public InterviewSessionCache(StringRedisTemplate redis) {
        this.redis = redis;
        this.mapper = new ObjectMapper();
    }

    // ==================== Session CRUD ====================

    public void saveSession(String sessionId, String resumeText, Long resumeId,
                            List<InterviewQuestionDTO> questions, int currentIndex, String status) {
        saveSession(sessionId, resumeText, resumeId, questions, currentIndex, status, 0, null, null, null, null);
    }

    public void saveSession(String sessionId, String resumeText, Long resumeId,
                            List<InterviewQuestionDTO> questions, int currentIndex,
                            String status, int mainQuestionCount, String allocationsJson) {
        saveSession(sessionId, resumeText, resumeId, questions, currentIndex, status,
                mainQuestionCount, allocationsJson, null, null, null);
    }

    public void saveSession(String sessionId, String resumeText, Long resumeId,
                            List<InterviewQuestionDTO> questions, int currentIndex,
                            String status, int mainQuestionCount, String allocationsJson,
                            String skillId, String difficulty, String mode) {
        try {
            CachedSession session = new CachedSession();
            session.setSessionId(sessionId);
            session.setResumeText(resumeText);
            session.setResumeId(resumeId);
            session.setSkillId(skillId);
            session.setDifficulty(difficulty);
            session.setQuestionsJson(mapper.writeValueAsString(questions));
            session.setCurrentIndex(currentIndex);
            session.setStatus(status);
            session.setMainQuestionCount(mainQuestionCount);
            session.setAllocationsJson(allocationsJson);
            session.setMode(mode);

            String value = mapper.writeValueAsString(session);
            redis.opsForValue().set(SESSION_KEY + sessionId, value, TTL);

            if (resumeId != null && isUnfinished(status)) {
                redis.opsForValue().set(RESUME_KEY + resumeId, sessionId, TTL);
            }

            log.debug("Session cached: sessionId={}, status={}", sessionId, status);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化会话缓存失败");
        }
    }

    public Optional<CachedSession> getSession(String sessionId) {
        String json = redis.opsForValue().get(SESSION_KEY + sessionId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, CachedSession.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize session cache: sessionId={}", sessionId, e);
            return Optional.empty();
        }
    }

    public void updateStatus(String sessionId, String status) {
        getSession(sessionId).ifPresent(session -> {
            session.setStatus(status);
            putSession(session);
            if (!isUnfinished(status) && session.getResumeId() != null) {
                redis.delete(RESUME_KEY + session.getResumeId());
            }
        });
    }

    public void updateCurrentIndex(String sessionId, int index) {
        getSession(sessionId).ifPresent(session -> {
            session.setCurrentIndex(index);
            putSession(session);
        });
    }

    public void updateMainQuestionCount(String sessionId, int count) {
        getSession(sessionId).ifPresent(session -> {
            session.setMainQuestionCount(count);
            putSession(session);
        });
    }

    public void updateAllocations(String sessionId, String allocationsJson) {
        getSession(sessionId).ifPresent(session -> {
            session.setAllocationsJson(allocationsJson);
            putSession(session);
        });
    }

    public void updateQuestions(String sessionId, List<InterviewQuestionDTO> questions) {
        getSession(sessionId).ifPresent(session -> {
            try {
                session.setQuestionsJson(mapper.writeValueAsString(questions));
                putSession(session);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize questions: sessionId={}", sessionId, e);
            }
        });
    }

    public void updateContextSummary(String sessionId, String contextSummary) {
        getSession(sessionId).ifPresent(session -> {
            session.setContextSummary(contextSummary);
            putSession(session);
        });
    }

    public void deleteSession(String sessionId) {
        getSession(sessionId).ifPresent(session -> {
            if (session.getResumeId() != null) {
                redis.delete(RESUME_KEY + session.getResumeId());
            }
        });
        redis.delete(SESSION_KEY + sessionId);
    }

    public void refreshTTL(String sessionId) {
        redis.expire(SESSION_KEY + sessionId, TTL);
    }

    // ==================== Reverse Index ====================

    public Optional<String> findUnfinishedSessionId(Long resumeId) {
        String sessionId = redis.opsForValue().get(RESUME_KEY + resumeId);
        if (sessionId != null) {
            return getSession(sessionId)
                    .filter(s -> isUnfinished(s.getStatus()))
                    .map(s -> sessionId)
                    .or(() -> {
                        redis.delete(RESUME_KEY + resumeId);
                        return Optional.empty();
                    });
        }
        return Optional.empty();
    }

    // ==================== Helpers ====================

    private void putSession(CachedSession session) {
        try {
            String value = mapper.writeValueAsString(session);
            redis.opsForValue().set(SESSION_KEY + session.getSessionId(), value, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize session: sessionId={}", session.getSessionId(), e);
        }
    }

    private boolean isUnfinished(String status) {
        return "CREATED".equals(status) || "IN_PROGRESS".equals(status);
    }

    // ==================== DTO ====================

    @Data
    public static class CachedSession {
        private String sessionId;
        private String resumeText;
        private Long resumeId;
        private String skillId;
        private String difficulty;
        private String questionsJson;
        private int currentIndex;
        private String status;
        /** How many main (non-follow-up) questions have been generated so far. */
        private int mainQuestionCount;
        /** JSON: Map&lt;categoryKey, remaining count&gt; — dynamic allocation tracking. */
        private String allocationsJson;
        /** L2 context summary — compressed early-round conversation. */
        private String contextSummary;

        /** batch / dynamic */
        private String mode;

        public List<InterviewQuestionDTO> getQuestions(ObjectMapper mapper) {
            try {
                return mapper.readValue(questionsJson,
                        mapper.getTypeFactory().constructCollectionType(List.class, InterviewQuestionDTO.class));
            } catch (JsonProcessingException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "反序列化题目列表失败");
            }
        }
    }
}
