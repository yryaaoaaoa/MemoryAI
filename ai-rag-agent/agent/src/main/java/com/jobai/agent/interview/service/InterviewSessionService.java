package com.jobai.agent.interview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.interview.cache.InterviewSessionCache;
import com.jobai.agent.interview.cache.InterviewSessionCache.CachedSession;
import com.jobai.agent.interview.model.*;
import com.jobai.agent.interview.service.DynamicQuestionService.NextAction;
import com.jobai.agent.interview.service.DynamicQuestionService.RemainingCategory;
import com.jobai.agent.interview.service.DynamicQuestionService.WindowResult;
import com.jobai.agent.interview.skill.InterviewSkillService;
import com.jobai.agent.interview.skill.InterviewSkillService.SkillCategoryDTO;
import com.jobai.agent.interview.skill.InterviewSkillService.SkillDTO;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.auth.AuthContext;
import com.jobai.knowledge.entity.Resume;
import com.jobai.knowledge.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    private final InterviewQuestionService questionService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewEvaluationService evaluationService;
    private final InterviewSessionCache sessionCache;
    private final ResumeMapper resumeMapper;
    private final ObjectMapper objectMapper;
    private final DynamicQuestionService dynamicQuestionService;
    private final InterviewSkillService skillService;

    public static final String MODE_BATCH = "batch";
    public static final String MODE_DYNAMIC = "dynamic";

    // ==================== Create ====================

    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        if (request.resumeId() != null && !Boolean.TRUE.equals(request.forceCreate())) {
            Optional<InterviewSessionDTO> unfinished = findUnfinishedSession(request.resumeId());
            if (unfinished.isPresent()) {
                log.info("Found unfinished session: resumeId={}, sessionId={}",
                        request.resumeId(), unfinished.get().sessionId());
                return unfinished.get();
            }
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String skillId = request.skillId();
        String difficulty = request.difficulty() != null ? request.difficulty() : "mid";
        String mode = request.mode() != null ? request.mode() : MODE_BATCH;

        // Load resume text if resumeId provided
        String resumeText = request.resumeText();
        if (resumeText == null && request.resumeId() != null) {
            Resume resume = resumeMapper.selectById(request.resumeId());
            if (resume != null) resumeText = resume.getRawText();
        }

        if (MODE_DYNAMIC.equals(mode)) {
            return createDynamicSession(sessionId, skillId, difficulty, resumeText, request, mode);
        }

        // — Batch mode (existing) —
        // Load historical questions for deduplication
        List<HistoricalQuestion> history = persistenceService.getHistoricalQuestions(skillId, request.resumeId());

        // Generate ALL questions at once (batch generation)
        // For custom JD (skillId="custom"), jdText is passed to resolve categories and refs
        List<InterviewQuestionDTO> questions = questionService.generateQuestions(
                skillId, difficulty, resumeText, request.resumeId(), request.questionCount(), history, request.jdText());

        // Count main questions for display purposes
        int mainCount = (int) questions.stream().filter(q -> !q.isFollowUp()).count();

        log.info("Interview session created (batch): sessionId={}, skill={}, questions={}(main={})",
                sessionId, skillId, questions.size(), mainCount);

        // Cache in Redis
        sessionCache.saveSession(sessionId,
                resumeText != null ? resumeText : "",
                request.resumeId(), questions, 0,
                InterviewSessionDTO.Status.CREATED.name());
        sessionCache.updateMainQuestionCount(sessionId, mainCount);

        // Persist to MySQL
        persistenceService.saveSession(sessionId, request.resumeId(), request.questionCount(),
                questions, request.llmProvider(), skillId, difficulty, MODE_BATCH);

        return new InterviewSessionDTO(sessionId,
                resumeText != null ? resumeText : "",
                request.questionCount(), 0, questions,
                InterviewSessionDTO.Status.CREATED.name(), MODE_BATCH);
    }

    // ==================== Dynamic Session ====================

    private InterviewSessionDTO createDynamicSession(String sessionId, String skillId,
                                                      String difficulty, String resumeText,
                                                      CreateInterviewRequest request, String mode) {
        // Resolve skill (preset or custom via JD)
        SkillDTO skill = resolveSkill(skillId, request.jdText());

        // Calculate question allocation across categories (掌握度加权)
        Long currentUserId = AuthContext.get();
        Map<String, Integer> allocation = skillService.calculateAllocation(skill.id(), skill.categories(), request.questionCount(), currentUserId);
        String allocationsJson = toAllocationsJson(allocation);

        // Generate the first question via DynamicQuestionService
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        List<RemainingCategory> remaining = buildRemainingCategories(allocation, questions);

        NextAction first = dynamicQuestionService.decide(
                skillId, difficulty, request.resumeId(), resumeText, questions, remaining, null, null);

        InterviewQuestionDTO firstQ = toInterviewQuestion(first, questions);
        questions.add(firstQ);

        int mainCount = 1;
        int approximateTotal = allocation.values().stream().mapToInt(Integer::intValue).sum();

        log.info("Interview session created (dynamic): sessionId={}, skill={}, firstQ={}, estimatedTotal={}",
                sessionId, skillId, firstQ.questionIndex(), approximateTotal);

        // Cache in Redis
        sessionCache.saveSession(sessionId,
                resumeText != null ? resumeText : "",
                request.resumeId(), questions, 0,
                InterviewSessionDTO.Status.CREATED.name(),
                approximateTotal, allocationsJson, skillId, difficulty, MODE_DYNAMIC);

        // Persist to MySQL
        persistenceService.saveSession(sessionId, request.resumeId(), approximateTotal,
                questions, request.llmProvider(), skillId, difficulty, MODE_DYNAMIC);

        return new InterviewSessionDTO(sessionId,
                resumeText != null ? resumeText : "",
                approximateTotal, 0, questions,
                InterviewSessionDTO.Status.CREATED.name(), MODE_DYNAMIC);
    }

    private SkillDTO resolveSkill(String skillId, String jdText) {
        if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId) && jdText != null && !jdText.isBlank()) {
            var categories = skillService.parseJd(jdText);
            return new SkillDTO("custom", "自定义面试", "基于 JD 解析的面试方向",
                    categories.stream().map(c -> new SkillCategoryDTO(
                            c.key(), c.label(), c.priority(), c.ref(),
                            Boolean.TRUE.equals(c.shared()))).toList(),
                    false, jdText, null, null);
        }
        return skillService.getSkill(skillId);
    }

    // ==================== Read ====================

    public InterviewSessionDTO getSession(String sessionId) {
        Optional<CachedSession> cached = sessionCache.getSession(sessionId);
        if (cached.isPresent()) return toDTO(cached.get());

        CachedSession restored = restoreFromDb(sessionId);
        if (restored == null) throw new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在");
        return toDTO(restored);
    }

    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        Optional<String> cachedId = sessionCache.findUnfinishedSessionId(resumeId);
        if (cachedId.isPresent()) {
            return sessionCache.getSession(cachedId.get()).map(this::toDTO);
        }

        return persistenceService.findUnfinishedSession(resumeId)
                .flatMap(entity -> {
                    CachedSession restored = restoreFromEntity(entity);
                    return restored != null ? Optional.of(toDTO(restored)) : Optional.empty();
                });
    }

    public InterviewSessionDTO findUnfinishedOrThrow(Long resumeId) {
        return findUnfinishedSession(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "无可恢复的面试会话"));
    }

    // ==================== Question ====================

    public Map<String, Object> getCurrentQuestionResponse(String sessionId) {
        InterviewQuestionDTO question = getCurrentQuestion(sessionId);
        if (question == null) {
            return Map.of("completed", true, "message", "所有问题已回答完毕");
        }
        return Map.of("completed", false, "question", question);
    }

    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        CachedSession session = getOrRestore(sessionId);
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (session.getCurrentIndex() >= questions.size()) return null;

        if ("CREATED".equals(session.getStatus())) {
            sessionCache.updateStatus(sessionId, InterviewSessionDTO.Status.IN_PROGRESS.name());
            try {
                persistenceService.updateSessionStatus(sessionId,
                        InterviewSessionEntity.Status.IN_PROGRESS.name());
            } catch (Exception e) {
                log.warn("Failed to update status: {}", e.getMessage());
            }
        }

        return questions.get(session.getCurrentIndex());
    }

    // ==================== Submit / Save ====================

    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestore(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index != session.getCurrentIndex()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "只能提交当前问题 (current=" + session.getCurrentIndex() + ", submitted=" + index + ")");
        }

        // Save the answer
        InterviewQuestionDTO q = questions.get(index);
        questions.set(index, q.withAnswer(request.answer()));
        sessionCache.updateQuestions(request.sessionId(), questions);

        // Persist answer to DB
        persistenceService.saveAnswer(request.sessionId(), index,
                q.question(), q.category(), request.answer(), 0, null);

        // Dynamic mode: generate next question based on answer quality
        if (MODE_DYNAMIC.equals(session.getMode())) {
            return submitAnswerDynamic(request.sessionId(), session, questions, index);
        }

        // — Batch mode (existing) —
        int nextIndex = index + 1;
        boolean isLastQuestion = nextIndex >= questions.size();

        if (isLastQuestion) {
            // All questions answered → complete and trigger async evaluation
            sessionCache.updateStatus(request.sessionId(), InterviewSessionDTO.Status.COMPLETED.name());
            sessionCache.updateCurrentIndex(request.sessionId(), nextIndex);

            try {
                persistenceService.updateSessionStatus(request.sessionId(),
                        InterviewSessionEntity.Status.COMPLETED.name());
                persistenceService.updateEvaluateStatus(request.sessionId(),
                        InterviewSessionEntity.EvaluateStatus.PENDING.name(), null);
            } catch (Exception e) {
                log.warn("Failed to complete session: {}", e.getMessage());
            }

            evaluationService.evaluateAsync(request.sessionId(), AuthContext.get());
            log.info("Interview completed (batch): sessionId={}, totalQuestions={}",
                    request.sessionId(), questions.size());

            return new SubmitAnswerResponse(false, null, nextIndex, questions.size());
        }

        // Advance to next question
        sessionCache.updateCurrentIndex(request.sessionId(), nextIndex);
        try {
            persistenceService.updateCurrentQuestionIndex(request.sessionId(), nextIndex);
        } catch (Exception e) {
            log.warn("Failed to update index: {}", e.getMessage());
        }

        InterviewQuestionDTO nextQuestion = questions.get(nextIndex);

        log.info("Answer saved (batch): sessionId={}, qIdx={}, nextIdx={}",
                request.sessionId(), index, nextIndex);

        return new SubmitAnswerResponse(true, nextQuestion, nextIndex, questions.size());
    }

    // ==================== Dynamic Submit ====================

    private SubmitAnswerResponse submitAnswerDynamic(String sessionId, CachedSession session,
                                                      List<InterviewQuestionDTO> questions, int answeredIndex) {
        // 1. Build remaining categories from allocation (stored at session creation)
        Map<String, Integer> allocation = parseAllocations(session.getAllocationsJson());
        List<RemainingCategory> remaining = buildRemainingCategories(allocation, questions);

        // 2. Get L4 reference content for the current category
        String currentCategory = findCurrentCategory(questions);
        String referenceContent = skillService.buildCategoryReference(session.getSkillId(), currentCategory);

        // 3. Get L2 context summary
        String contextSummary = session.getContextSummary();

        // 4. Call DynamicQuestionService.decide()
        NextAction next = dynamicQuestionService.decide(
                session.getSkillId(), session.getDifficulty(),
                session.getResumeId(), session.getResumeText(), questions, remaining,
                contextSummary, referenceContent);

        log.info("Dynamic next action: sessionId={}, action={}, category={}, why={}",
                sessionId, next.action(), next.category(), next.why());

        int nextIndex = answeredIndex + 1;

        if ("complete".equals(next.action())) {
            // All categories sufficiently covered → end interview
            completeDynamicSession(sessionId, nextIndex);
            return new SubmitAnswerResponse(false, null, nextIndex, questions.size());
        }

        // 5. Create new question and append
        InterviewQuestionDTO newQuestion = toInterviewQuestion(next, questions);
        questions.add(newQuestion);
        sessionCache.updateQuestions(sessionId, questions);
        sessionCache.updateCurrentIndex(sessionId, nextIndex);

        // 6. L2 compression: if some rounds have slid out of the L3 window
        WindowResult window = dynamicQuestionService.buildRecentWindow(questions);
        if (window.windowStartIndex() > 0) {
            try {
                String newSummary = dynamicQuestionService.compressSummary(
                        contextSummary, questions, window.windowStartIndex());
                if (!newSummary.equals(contextSummary)) {
                    sessionCache.updateContextSummary(sessionId, newSummary);
                    persistenceService.updateContextSummary(sessionId, newSummary);
                }
            } catch (Exception e) {
                log.warn("L2 compression failed: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }

        log.info("Answer saved (dynamic): sessionId={}, qIdx={}, nextIdx={}, newQuestion=[{}] {}",
                sessionId, answeredIndex, nextIndex, next.category(), truncate(next.question(), 60));

        return new SubmitAnswerResponse(true, newQuestion, nextIndex, questions.size());
    }

    private void completeDynamicSession(String sessionId, int nextIndex) {
        sessionCache.updateStatus(sessionId, InterviewSessionDTO.Status.COMPLETED.name());
        sessionCache.updateCurrentIndex(sessionId, nextIndex);

        try {
            persistenceService.updateSessionStatus(sessionId,
                    InterviewSessionEntity.Status.COMPLETED.name());
            persistenceService.updateEvaluateStatus(sessionId,
                    InterviewSessionEntity.EvaluateStatus.PENDING.name(), null);
        } catch (Exception e) {
            log.warn("Failed to complete dynamic session: {}", e.getMessage());
        }

        evaluationService.evaluateAsync(sessionId, AuthContext.get());
        log.info("Interview completed (dynamic): sessionId={}", sessionId);
    }

    public void saveAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestore(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的问题索引: " + index);
        }

        InterviewQuestionDTO q = questions.get(index);
        questions.set(index, q.withAnswer(request.answer()));

        sessionCache.updateQuestions(request.sessionId(), questions);

        if ("CREATED".equals(session.getStatus())) {
            sessionCache.updateStatus(request.sessionId(),
                    InterviewSessionDTO.Status.IN_PROGRESS.name());
            try {
                persistenceService.updateSessionStatus(request.sessionId(),
                        InterviewSessionEntity.Status.IN_PROGRESS.name());
            } catch (Exception e) {
                log.warn("Failed to sync status to DB: {}", e.getMessage());
            }
        }

        try {
            persistenceService.saveAnswer(request.sessionId(), index,
                    q.question(), q.category(), request.answer(), 0, null);
        } catch (Exception e) {
            log.warn("Failed to save answer: {}", e.getMessage());
        }
    }

    // ==================== Complete ====================

    public void completeInterview(String sessionId) {
        CachedSession session = getOrRestore(sessionId);

        if ("COMPLETED".equals(session.getStatus()) || "EVALUATED".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试已结束");
        }

        sessionCache.updateStatus(sessionId, InterviewSessionDTO.Status.COMPLETED.name());

        try {
            persistenceService.updateSessionStatus(sessionId,
                    InterviewSessionEntity.Status.COMPLETED.name());
            persistenceService.updateEvaluateStatus(sessionId,
                    InterviewSessionEntity.EvaluateStatus.PENDING.name(), null);
        } catch (Exception e) {
            log.warn("Failed to complete session: {}", e.getMessage());
        }

        evaluationService.evaluateAsync(sessionId, AuthContext.get());
        log.info("Interview completed early: sessionId={}", sessionId);
    }

    // ==================== Report ====================

    public InterviewReportDTO getReport(String sessionId) {
        InterviewSessionEntity entity = persistenceService.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在"));

        if (!"EVALUATED".equals(entity.getStatus()) && !"COMPLETED".equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试尚未完成");
        }

        if ("COMPLETED".equals(entity.getStatus())) {
            String evalStatus = entity.getEvaluateStatus();
            if (evalStatus == null) return null;
            if ("PENDING".equals(evalStatus) || "PROCESSING".equals(evalStatus)) return null;
            if ("FAILED".equals(evalStatus)) {
                throw new BusinessException(ErrorCode.LLM_SERVICE_FAILED,
                        "评估失败: " + (entity.getEvaluateError() != null ? entity.getEvaluateError() : "未知错误"));
            }
        }

        return buildReportFromEntity(entity);
    }

    private InterviewReportDTO buildReportFromEntity(InterviewSessionEntity entity) {
        try {
            var typeFactory = objectMapper.getTypeFactory();
            List<String> strengths = entity.getStrengthsJson() != null
                    ? objectMapper.readValue(entity.getStrengthsJson(),
                    typeFactory.constructCollectionType(List.class, String.class))
                    : List.of();
            List<String> improvements = entity.getImprovementsJson() != null
                    ? objectMapper.readValue(entity.getImprovementsJson(),
                    typeFactory.constructCollectionType(List.class, String.class))
                    : List.of();
            List<InterviewReportDTO.ReferenceAnswer> refAnswers = entity.getReferenceAnswersJson() != null
                    ? objectMapper.readValue(entity.getReferenceAnswersJson(),
                    typeFactory.constructCollectionType(List.class, InterviewReportDTO.ReferenceAnswer.class))
                    : List.of();

            // Rebuild question details from answer table
            List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(entity.getSessionId());
            List<InterviewReportDTO.QuestionEvaluation> questionDetails = answers.stream()
                    .map(a -> new InterviewReportDTO.QuestionEvaluation(
                            a.getQuestionIndex(), a.getQuestion(), a.getCategory(),
                            a.getUserAnswer(), a.getScore() != null ? a.getScore() : 0,
                            a.getFeedback() != null ? a.getFeedback() : ""))
                    .toList();

            return new InterviewReportDTO(
                    entity.getSessionId(),
                    entity.getTotalQuestions() != null ? entity.getTotalQuestions() : 0,
                    entity.getOverallScore() != null ? entity.getOverallScore() : 0,
                    List.of(), // categoryScores not persisted separately — LLM recalculates
                    questionDetails,
                    entity.getOverallFeedback() != null ? entity.getOverallFeedback() : "",
                    strengths,
                    improvements,
                    refAnswers
            );
        } catch (Exception e) {
            log.error("Failed to rebuild report from entity: sessionId={}", entity.getSessionId(), e);
            return null;
        }
    }

    // ==================== List ====================

    public List<Map<String, Object>> listSessions() {
        return persistenceService.findAll().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("sessionId", e.getSessionId());
                    m.put("skillId", e.getSkillId());
                    m.put("difficulty", e.getDifficulty());
                    m.put("mode", e.getMode() != null ? e.getMode() : "batch");
                    m.put("totalQuestions", e.getTotalQuestions());
                    m.put("status", e.getStatus());
                    m.put("overallScore", e.getOverallScore());
                    m.put("createdAt", e.getCreatedAt());
                    return m;
                }).toList();
    }

    public void deleteSession(String sessionId) {
        sessionCache.deleteSession(sessionId);
        persistenceService.deleteSessionBySessionId(sessionId);
        log.info("Session deleted: sessionId={}", sessionId);
    }

    // ==================== Private Helpers ====================

    private CachedSession getOrRestore(String sessionId) {
        Optional<CachedSession> cached = sessionCache.getSession(sessionId);
        if (cached.isPresent()) {
            sessionCache.refreshTTL(sessionId);
            return cached.get();
        }
        CachedSession restored = restoreFromDb(sessionId);
        if (restored != null) return restored;
        throw new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在");
    }

    private CachedSession restoreFromDb(String sessionId) {
        return persistenceService.findBySessionId(sessionId)
                .map(this::restoreFromEntity)
                .orElse(null);
    }

    private CachedSession restoreFromEntity(InterviewSessionEntity entity) {
        try {
            List<InterviewQuestionDTO> questions = objectMapper.readValue(
                    entity.getQuestionsJson(),
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, InterviewQuestionDTO.class));

            // Restore saved answers
            List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(
                    entity.getSessionId());
            for (InterviewAnswerEntity answer : answers) {
                int idx = answer.getQuestionIndex();
                if (idx >= 0 && idx < questions.size()) {
                    questions.set(idx, questions.get(idx).withAnswer(answer.getUserAnswer()));
                }
            }

            // Count main questions for proper display
            int mainCount = (int) questions.stream().filter(q -> !q.isFollowUp()).count();

            // Load resume text for cache restore
            String resumeText = "";
            if (entity.getResumeId() != null) {
                Resume resume = resumeMapper.selectById(entity.getResumeId());
                if (resume != null) resumeText = resume.getRawText() != null ? resume.getRawText() : "";
            }

            // Populate cache (restore mode for dynamic sessions)
            sessionCache.saveSession(entity.getSessionId(), resumeText, entity.getResumeId(),
                    questions, entity.getCurrentQuestionIndex(), entity.getStatus(),
                    mainCount, null, entity.getSkillId(), entity.getDifficulty(),
                    entity.getMode());

            // Restore L2 context summary (kept for potential future adaptive mode)
            if (entity.getContextSummary() != null && !entity.getContextSummary().isBlank()) {
                sessionCache.updateContextSummary(entity.getSessionId(), entity.getContextSummary());
            }

            log.info("Session restored from DB: sessionId={}, index={}, status={}",
                    entity.getSessionId(), entity.getCurrentQuestionIndex(), entity.getStatus());

            return sessionCache.getSession(entity.getSessionId()).orElse(null);
        } catch (Exception e) {
            log.error("Failed to restore session from DB: sessionId={}", entity.getSessionId(), e);
            return null;
        }
    }

    private InterviewSessionDTO toDTO(CachedSession session) {
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        int displayTotal = session.getMainQuestionCount() > 0
                ? session.getMainQuestionCount() : questions.size();
        String mode = session.getMode() != null ? session.getMode() : MODE_BATCH;
        return new InterviewSessionDTO(session.getSessionId(), session.getResumeText(),
                displayTotal,
                session.getCurrentIndex(), questions, session.getStatus(), mode);
    }

    // ==================== Dynamic Mode Helpers ====================

    private Map<String, Integer> parseAllocations(String allocationsJson) {
        if (allocationsJson == null || allocationsJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(allocationsJson,
                    new TypeReference<Map<String, Integer>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse allocations JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private String toAllocationsJson(Map<String, Integer> allocation) {
        try {
            return objectMapper.writeValueAsString(allocation);
        } catch (Exception e) {
            log.warn("Failed to serialize allocations: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Build remaining categories from the allocation map.
     * Allocation is stored at session creation and provides the planned question count per category.
     * Completed count is computed from the questions list.
     */
    private List<RemainingCategory> buildRemainingCategories(Map<String, Integer> allocation,
                                                              List<InterviewQuestionDTO> questions) {
        // Count main questions per category already generated
        Map<String, Integer> completed = new HashMap<>();
        for (InterviewQuestionDTO q : questions) {
            if (!q.isFollowUp()) {
                completed.merge(q.type(), 1, Integer::sum);
            }
        }

        return allocation.entrySet().stream()
                .map(entry -> {
                    int planned = entry.getValue();
                    int done = completed.getOrDefault(entry.getKey(), 0);
                    int remaining = Math.max(0, planned - done);
                    return new RemainingCategory(entry.getKey(), entry.getKey(), "NORMAL", planned, remaining);
                })
                .collect(Collectors.toList());
    }

    private String findCurrentCategory(List<InterviewQuestionDTO> questions) {
        for (int i = questions.size() - 1; i >= 0; i--) {
            if (!questions.get(i).isFollowUp()) {
                return questions.get(i).type();
            }
        }
        return null;
    }

    private InterviewQuestionDTO toInterviewQuestion(NextAction next, List<InterviewQuestionDTO> existing) {
        int nextIndex = existing.size();
        boolean isFollowUp = next.isFollowUp();
        Integer parentIndex = null;
        if (isFollowUp) {
            // Find the last main question as parent
            for (int i = existing.size() - 1; i >= 0; i--) {
                if (!existing.get(i).isFollowUp()) {
                    parentIndex = existing.get(i).questionIndex();
                    break;
                }
            }
        }
        return InterviewQuestionDTO.create(nextIndex, next.question(), next.type(),
                next.category(), next.topicSummary(), isFollowUp, parentIndex);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
