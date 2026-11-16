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
        requireUserId();
        Resume ownedResume = request.resumeId() != null ? requireOwnedResume(request.resumeId()) : null;
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
            resumeText = ownedResume.getRawText();
        }

        if (MODE_DYNAMIC.equals(mode)) {
            return createDynamicSession(sessionId, skillId, difficulty, resumeText, request, mode);
        }

        // — Batch mode (existing) —
        // Load historical questions for deduplication
        List<HistoricalQuestion> history = persistenceService.getHistoricalQuestions(skillId, request.resumeId());
        SkillDTO skill = resolveSkill(skillId, request.jdText());

        // Generate ALL questions at once (batch generation)
        // For custom JD (skillId="custom"), jdText is passed to resolve categories and refs
        List<InterviewQuestionDTO> questions = questionService.generateQuestions(
                skill, difficulty, resumeText, request.resumeId(), request.questionCount(), history);

        // Count main questions for display purposes
        int mainCount = (int) questions.stream().filter(q -> !q.isFollowUp()).count();

        log.info("Interview session created (batch): sessionId={}, skill={}, questions={}(main={})",
                sessionId, skillId, questions.size(), mainCount);

        persistenceService.saveSession(sessionId, request.resumeId(), request.questionCount(),
                questions, request.llmProvider(), skillId, difficulty, MODE_BATCH, resumeText, null, skill);

        // Cache only after the durable record exists.
        sessionCache.saveSession(sessionId,
                resumeText != null ? resumeText : "",
                request.resumeId(), questions, 0,
                InterviewSessionDTO.Status.CREATED.name(), mainCount, null, skillId, difficulty, MODE_BATCH);

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
                skill, difficulty, request.resumeId(), resumeText, questions, remaining, null, null);

        InterviewQuestionDTO firstQ = toInterviewQuestion(first, questions);
        questions.add(firstQ);

        int mainCount = 1;
        int approximateTotal = allocation.values().stream().mapToInt(Integer::intValue).sum();

        log.info("Interview session created (dynamic): sessionId={}, skill={}, firstQ={}, estimatedTotal={}",
                sessionId, skillId, firstQ.questionIndex(), approximateTotal);

        persistenceService.saveSession(sessionId, request.resumeId(), approximateTotal,
                questions, request.llmProvider(), skillId, difficulty, MODE_DYNAMIC, resumeText, allocationsJson, skill);

        // Cache only after the durable record exists.
        sessionCache.saveSession(sessionId,
                resumeText != null ? resumeText : "",
                request.resumeId(), questions, 0,
                InterviewSessionDTO.Status.CREATED.name(),
                approximateTotal, allocationsJson, skillId, difficulty, MODE_DYNAMIC);

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
        InterviewSessionEntity entity = requireOwnedSession(sessionId);
        Optional<CachedSession> cached = sessionCache.getSession(sessionId);
        if (cached.isPresent() && isCacheCurrent(cached.get(), entity)) {
            cached.get().setStatus(entity.getStatus());
            return toDTO(cached.get());
        }

        CachedSession restored = restoreFromDb(sessionId);
        if (restored == null) throw new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在");
        return toDTO(restored);
    }

    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        requireOwnedResume(resumeId);
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
        if ("COMPLETED".equals(session.getStatus()) || "EVALUATED".equals(session.getStatus())) return null;
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (session.getCurrentIndex() >= questions.size()) return null;

        if ("CREATED".equals(session.getStatus())) {
            persistenceService.updateSessionStatus(sessionId, InterviewSessionEntity.Status.IN_PROGRESS.name());
            sessionCache.updateStatus(sessionId, InterviewSessionDTO.Status.IN_PROGRESS.name());
        }

        return questions.get(session.getCurrentIndex());
    }

    // ==================== Submit / Save ====================

    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        requireActiveSession(request.sessionId());
        CachedSession session = getOrRestore(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size() || index != session.getCurrentIndex()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "只能提交当前问题 (current=" + session.getCurrentIndex() + ", submitted=" + index + ")");
        }

        // Save the answer
        InterviewQuestionDTO q = questions.get(index);
        questions.set(index, q.withAnswer(request.answer()));

        // Persist answer to DB
        persistenceService.saveAnswer(request.sessionId(), index,
                q.question(), q.category(), request.answer(), 0, null);
        sessionCache.updateQuestions(request.sessionId(), questions);

        // Dynamic mode: generate next question based on answer quality
        if (MODE_DYNAMIC.equals(session.getMode())) {
            return submitAnswerDynamic(request.sessionId(), session, questions, index);
        }

        // — Batch mode (existing) —
        int nextIndex = index + 1;
        boolean isLastQuestion = nextIndex >= questions.size();

        if (isLastQuestion) {
            // All questions answered → complete and trigger async evaluation
            markCompleted(request.sessionId(), questions, nextIndex);
            sessionCache.updateCurrentIndex(request.sessionId(), nextIndex);

            scheduleEvaluation(request.sessionId());
            log.info("Interview completed (batch): sessionId={}, totalQuestions={}",
                    request.sessionId(), questions.size());

            return new SubmitAnswerResponse(false, null, nextIndex, questions.size());
        }

        // Advance to next question
        persistenceService.saveProgress(request.sessionId(), questions, nextIndex);
        sessionCache.updateCurrentIndex(request.sessionId(), nextIndex);

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
        InterviewSessionEntity entity = requireOwnedSession(sessionId);
        SkillDTO skill = skillService.getSessionSkill(entity.getSkillId(), entity.getSkillSnapshotJson());
        String referenceContent = skillService.buildCategoryReference(skill, currentCategory);

        // 3. Get L2 context summary
        String contextSummary = session.getContextSummary();

        // 4. Call DynamicQuestionService.decide()
        NextAction next = dynamicQuestionService.decide(
                skill, session.getDifficulty(),
                session.getResumeId(), session.getResumeText(), questions, remaining,
                contextSummary, referenceContent);

        log.info("Dynamic next action: sessionId={}, action={}, category={}, why={}",
                sessionId, next.action(), next.category(), next.why());

        int nextIndex = answeredIndex + 1;

        if ("complete".equals(next.action())) {
            // All categories sufficiently covered → end interview
            completeDynamicSession(sessionId, questions, nextIndex);
            return new SubmitAnswerResponse(false, null, nextIndex, questions.size());
        }

        // 5. Create new question and append
        InterviewQuestionDTO newQuestion = toInterviewQuestion(next, questions);
        questions.add(newQuestion);
        persistenceService.saveProgress(sessionId, questions, nextIndex);
        sessionCache.updateQuestions(sessionId, questions);
        sessionCache.updateCurrentIndex(sessionId, nextIndex);

        // 6. L2 compression: if some rounds have slid out of the L3 window
        WindowResult window = dynamicQuestionService.buildRecentWindow(questions);
        if (window.windowStartIndex() > 0) {
            try {
                String newSummary = dynamicQuestionService.compressSummary(
                        contextSummary, questions, window.windowStartIndex());
                if (!newSummary.equals(contextSummary)) {
                    persistenceService.updateContextSummary(sessionId, newSummary);
                    sessionCache.updateContextSummary(sessionId, newSummary);
                }
            } catch (Exception e) {
                log.warn("L2 compression failed: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }

        log.info("Answer saved (dynamic): sessionId={}, qIdx={}, nextIdx={}, newQuestion=[{}] {}",
                sessionId, answeredIndex, nextIndex, next.category(), truncate(next.question(), 60));

        return new SubmitAnswerResponse(true, newQuestion, nextIndex, questions.size());
    }

    private void completeDynamicSession(String sessionId, List<InterviewQuestionDTO> questions, int nextIndex) {
        markCompleted(sessionId, questions, nextIndex);
        sessionCache.updateCurrentIndex(sessionId, nextIndex);

        scheduleEvaluation(sessionId);
        log.info("Interview completed (dynamic): sessionId={}", sessionId);
    }

    public void saveAnswer(SubmitAnswerRequest request) {
        requireActiveSession(request.sessionId());
        CachedSession session = getOrRestore(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的问题索引: " + index);
        }

        InterviewQuestionDTO q = questions.get(index);
        persistenceService.saveAnswer(request.sessionId(), index,
                q.question(), q.category(), request.answer(), 0, null);
        questions.set(index, q.withAnswer(request.answer()));

        sessionCache.updateQuestions(request.sessionId(), questions);

        if ("CREATED".equals(session.getStatus())) {
            persistenceService.updateSessionStatus(request.sessionId(), InterviewSessionEntity.Status.IN_PROGRESS.name());
            sessionCache.updateStatus(request.sessionId(),
                    InterviewSessionDTO.Status.IN_PROGRESS.name());
        }
    }

    // ==================== Complete ====================

    public void completeInterview(String sessionId) {
        requireActiveSession(sessionId);
        CachedSession session = getOrRestore(sessionId);

        if ("COMPLETED".equals(session.getStatus()) || "EVALUATED".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试已结束");
        }

        markCompleted(sessionId, null, null);

        scheduleEvaluation(sessionId);
        log.info("Interview completed early: sessionId={}", sessionId);
    }

    private void markCompleted(String sessionId, List<InterviewQuestionDTO> questions, Integer index) {
        persistenceService.completeSession(sessionId, questions, index);
        sessionCache.updateStatus(sessionId, InterviewSessionDTO.Status.COMPLETED.name());
    }

    // ==================== Report ====================

    public void retryEvaluation(String sessionId) {
        requireOwnedSession(sessionId);
        persistenceService.requestEvaluationRetry(sessionId, requireUserId());
        scheduleEvaluation(sessionId);
    }

    private void scheduleEvaluation(String sessionId) {
        try {
            evaluationService.evaluateAsync(sessionId, requireUserId());
        } catch (Exception e) {
            persistenceService.updateEvaluateStatus(sessionId, "FAILED", "评估任务未能启动，请重新评估");
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "评估任务未能启动，请重新评估");
        }
    }

    public Object getReportResponse(String sessionId) {
        InterviewSessionEntity entity = requireOwnedSession(sessionId);
        if ("COMPLETED".equals(entity.getStatus())) {
            String status = entity.getEvaluateStatus() != null ? entity.getEvaluateStatus() : "PENDING";
            if ("FAILED".equals(status)) {
                return Map.of("ready", false, "status", status, "error",
                        entity.getEvaluateError() != null ? entity.getEvaluateError() : "评估失败，请重新评估");
            }
            if ("PENDING".equals(status) || "PROCESSING".equals(status)) {
                return Map.of("ready", false, "status", status, "message", "评估进行中，请稍后刷新");
            }
        }
        return getReport(sessionId);
    }

    public InterviewReportDTO getReport(String sessionId) {
        InterviewSessionEntity entity = requireOwnedSession(sessionId);

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

            List<InterviewReportDTO.CategoryScore> categoryScores;
            if (entity.getCategoryScoresJson() != null && !entity.getCategoryScoresJson().isBlank()) {
                categoryScores = objectMapper.readValue(entity.getCategoryScoresJson(),
                        typeFactory.constructCollectionType(List.class, InterviewReportDTO.CategoryScore.class));
            } else {
                // Older reports predate category_scores_json: rebuild from their saved marks.
                Map<String, IntSummaryStatistics> stats = questionDetails.stream().collect(Collectors.groupingBy(
                        q -> q.category() != null ? q.category() : "综合", LinkedHashMap::new,
                        Collectors.summarizingInt(InterviewReportDTO.QuestionEvaluation::score)));
                categoryScores = stats.entrySet().stream().map(e -> new InterviewReportDTO.CategoryScore(
                        e.getKey(), (int) Math.round(e.getValue().getAverage()), (int) e.getValue().getCount())).toList();
            }

            return new InterviewReportDTO(
                    entity.getSessionId(),
                    entity.getTotalQuestions() != null ? entity.getTotalQuestions() : 0,
                    entity.getOverallScore() != null ? entity.getOverallScore() : 0,
                    categoryScores,
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
        requireOwnedSession(sessionId);
        sessionCache.deleteSession(sessionId);
        persistenceService.deleteSessionBySessionId(sessionId);
        log.info("Session deleted: sessionId={}", sessionId);
    }

    // ==================== Private Helpers ====================

    private CachedSession getOrRestore(String sessionId) {
        InterviewSessionEntity entity = requireOwnedSession(sessionId);
        Optional<CachedSession> cached = sessionCache.getSession(sessionId);
        if (cached.isPresent() && isCacheCurrent(cached.get(), entity)) {
            cached.get().setStatus(entity.getStatus());
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

    private boolean isCacheCurrent(CachedSession cached, InterviewSessionEntity entity) {
        return Objects.equals(cached.getCurrentIndex(), entity.getCurrentQuestionIndex())
                && Objects.equals(cached.getQuestionsJson(), entity.getQuestionsJson())
                && Objects.equals(cached.getResumeId(), entity.getResumeId())
                && Objects.equals(cached.getResumeText(), entity.getResumeText() != null ? entity.getResumeText() : "")
                && Objects.equals(cached.getAllocationsJson(), entity.getAllocationsJson())
                && Objects.equals(cached.getMode(), entity.getMode())
                && Objects.equals(cached.getSkillId(), entity.getSkillId())
                && Objects.equals(cached.getDifficulty(), entity.getDifficulty());
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
            int mainCount = MODE_DYNAMIC.equals(entity.getMode()) && entity.getTotalQuestions() != null
                    ? entity.getTotalQuestions() : (int) questions.stream().filter(q -> !q.isFollowUp()).count();

            // Load resume text for cache restore
            String resumeText = entity.getResumeText();
            if (resumeText == null && entity.getResumeId() != null) {
                Resume resume = resumeMapper.selectById(entity.getResumeId());
                if (resume != null && Objects.equals(resume.getUserId(), entity.getUserId())) {
                    resumeText = resume.getRawText();
                }
            }
            if (resumeText == null) resumeText = "";

            // Populate cache (restore mode for dynamic sessions)
            sessionCache.saveSession(entity.getSessionId(), resumeText, entity.getResumeId(),
                    questions, entity.getCurrentQuestionIndex(), entity.getStatus(),
                    mainCount, entity.getAllocationsJson(), entity.getSkillId(), entity.getDifficulty(),
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

    private Long requireUserId() {
        Long userId = AuthContext.get();
        if (userId == null) throw new BusinessException(ErrorCode.NOT_AUTHENTICATED);
        return userId;
    }

    private InterviewSessionEntity requireOwnedSession(String sessionId) {
        Long userId = requireUserId();
        InterviewSessionEntity entity = persistenceService.findBySessionId(sessionId)
                .filter(s -> userId.equals(s.getUserId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在"));
        // Pre-fix sessions may be owned by this user but reference another user's resume.
        if (entity.getResumeId() != null && entity.getResumeText() == null) {
            Resume resume = resumeMapper.selectById(entity.getResumeId());
            if (resume == null || !userId.equals(resume.getUserId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在");
            }
        }
        return entity;
    }

    private void requireActiveSession(String sessionId) {
        String status = requireOwnedSession(sessionId).getStatus();
        if (!"CREATED".equals(status) && !"IN_PROGRESS".equals(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试已结束，不能修改答案");
        }
    }

    private Resume requireOwnedResume(Long resumeId) {
        Long userId = requireUserId();
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null || !userId.equals(resume.getUserId()) || resume.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历不存在");
        }
        return resume;
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
