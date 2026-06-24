package com.jobai.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 错题本服务 — Redis 缓存 + MySQL 持久化
 *
 * Redis 数据结构:
 *   SortedSet "wrong:set:{userId}"   → member=questionId, score=错误时间戳
 *   Set       "wrong:topics:{userId}" → member=topic (知识点标签)
 *
 * 核心流程:
 *   答错 → 写入 MySQL quiz_record → 写入 Redis SortedSet
 *   答对 → 写入 MySQL quiz_record → 从 Redis SortedSet 移除
 *   查错题本 → Redis 分页取 questionId → MySQL JOIN 补全题目和用户答案
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WrongQuestionService {

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    static final String KEY_SET = "wrong:set:";
    static final String KEY_TOPICS = "wrong:topics:";

    // ======================== DTO ========================

    @Data
    public static class WrongQuestionVO {
        private Long id;
        private String questionText;
        private Map<String, String> options;
        private String correctAnswer;
        private String explanation;
        private String topic;
        private String userAnswer;
        private int score;
        private LocalDateTime wrongTime;
    }

    @Data
    public static class WrongQuestionPage {
        private List<WrongQuestionVO> records;
        private int page;
        private int size;
        private long total;
        private long totalPages;

        public static WrongQuestionPage empty(int page, int size) {
            WrongQuestionPage p = new WrongQuestionPage();
            p.records = List.of();
            p.page = page;
            p.size = size;
            p.total = 0;
            p.totalPages = 0;
            return p;
        }
    }

    @Data
    public static class WrongTopicVO {
        private String topic;
        private long count;
    }

    @Data
    public static class SubmitResult {
        private boolean correct;
        private int score;
        private String correctAnswer;
        private String explanation;
    }

    // ======================== 公开 API ========================

    /**
     * 记录答错 — 写入 Redis SortedSet
     */
    public void recordWrongAttempt(Long userId, Long questionId, String topic) {
        redis.opsForZSet().add(KEY_SET + userId, String.valueOf(questionId), System.currentTimeMillis());
        if (topic != null && !topic.isBlank()) {
            redis.opsForSet().add(KEY_TOPICS + userId, topic);
        }
    }

    /**
     * 从错题本移出（答对或手动移除）
     */
    public void removeWrongQuestion(Long userId, Long questionId) {
        redis.opsForZSet().remove(KEY_SET + userId, String.valueOf(questionId));
    }

    /**
     * 错题总数
     */
    public long getWrongQuestionCount(Long userId) {
        Long total = redis.opsForZSet().size(KEY_SET + userId);
        return total != null ? total : 0L;
    }

    /**
     * 分页查询错题列表（最新在前）
     *
     * @param userId 用户 ID
     * @param topic  知识点筛选（null 表示全部）
     * @param page   页码（从 1 开始）
     * @param size   每页条数
     */
    public WrongQuestionPage listQuestions(Long userId, String topic, int page, int size) {
        String setKey = KEY_SET + userId;
        Long total = redis.opsForZSet().size(setKey);
        if (total == null || total == 0) {
            return WrongQuestionPage.empty(page, size);
        }

        int start = (page - 1) * size;
        int end = start + size - 1;
        Set<String> idStrs = redis.opsForZSet().reverseRange(setKey, start, end);
        if (idStrs == null || idStrs.isEmpty()) {
            return WrongQuestionPage.empty(page, size);
        }

        List<Long> ids = idStrs.stream().map(Long::valueOf).toList();
        List<WrongQuestionVO> questions = fetchWrongQuestions(userId, ids, topic);

        WrongQuestionPage result = new WrongQuestionPage();
        result.setRecords(questions);
        result.setPage(page);
        result.setSize(size);
        result.setTotal(total);
        result.setTotalPages((total + size - 1) / size);
        return result;
    }

    /**
     * 错题知识点分布统计
     */
    public List<WrongTopicVO> listTopics(Long userId) {
        Set<String> topics = redis.opsForSet().members(KEY_TOPICS + userId);
        if (topics == null || topics.isEmpty()) return List.of();

        return topics.stream().map(t -> {
            WrongTopicVO vo = new WrongTopicVO();
            vo.setTopic(t);
            try {
                Long cnt = jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT qr.question_id) FROM quiz_record qr " +
                        "JOIN quiz_question q ON q.id = qr.question_id " +
                        "WHERE qr.user_id = ? AND qr.is_correct = 0 AND q.topic = ?",
                        Long.class, userId, t);
                vo.setCount(cnt != null ? cnt : 0);
            } catch (Exception e) {
                vo.setCount(0);
            }
            return vo;
        }).sorted((a, b) -> Long.compare(b.count, a.count)).toList();
    }

    /**
     * 从错题本随机抽题重做
     */
    public List<WrongQuestionVO> getRetryQuestions(Long userId, int count) {
        Set<String> ids = redis.opsForZSet().reverseRange(KEY_SET + userId, 0, count * 2 - 1);
        if (ids == null || ids.isEmpty()) return List.of();

        List<Long> idList = new ArrayList<>(ids.stream().map(Long::valueOf).toList());
        Collections.shuffle(idList);
        List<Long> picked = idList.subList(0, Math.min(count, idList.size()));

        return fetchWrongQuestions(userId, picked, null);
    }

    /**
     * 提交答案 — 自动批改（选择题精确匹配）+ 记录到 MySQL + 更新 Redis
     */
    public SubmitResult submitAnswer(Long userId, Long questionId, String userAnswer, int durationSec) {
        // 1. 取题
        var question = jdbc.queryForMap(
                "SELECT question_type, answer, explanation, topic FROM quiz_question WHERE id = ?",
                questionId);

        String correctAnswer = str(question.get("answer"));
        String explanation = str(question.get("explanation"));
        String topic = str(question.get("topic"));
        String qType = str(question.get("question_type"));

        // 2. 自动批改
        boolean isCorrect = "choice".equals(qType)
                && userAnswer != null
                && userAnswer.strip().equalsIgnoreCase(correctAnswer.strip());
        int score = isCorrect ? 100 : 0;

        // 3. 入库
        jdbc.update(
                "INSERT INTO quiz_record (user_id, question_id, user_answer, is_correct, score, duration_sec, mastery_delta) VALUES (?, ?, ?, ?, ?, ?, 0)",
                userId, questionId, userAnswer, isCorrect ? 1 : 0, score, durationSec);

        // 4. 更新 Redis
        if (isCorrect) {
            removeWrongQuestion(userId, questionId);
        } else {
            recordWrongAttempt(userId, questionId, topic);
        }

        // 5. 更新 user_mastery
        updateUserMastery(userId, topic, isCorrect);

        SubmitResult result = new SubmitResult();
        result.setCorrect(isCorrect);
        result.setScore(score);
        result.setCorrectAnswer(correctAnswer);
        result.setExplanation(explanation);
        return result;
    }

    // ======================== 掌握度 ========================

    /**
     * 每次提交答案后更新 user_mastery 表。
     * 如果不存在则创建行，增量更新统计信息。
     */
    private void updateUserMastery(Long userId, String topic, boolean isCorrect) {
        if (topic == null || topic.isBlank()) return;
        jdbc.update("""
                INSERT INTO user_mastery (user_id, topic, mastery, total_attempts, correct_attempts, streak, next_review_at)
                VALUES (?, ?, ?, 1, ?, ?,
                    DATE_ADD(CURDATE(), INTERVAL IF(?, 2, 1) DAY))
                AS new
                ON DUPLICATE KEY UPDATE
                    total_attempts = user_mastery.total_attempts + 1,
                    correct_attempts = user_mastery.correct_attempts + new.correct_attempts,
                    streak = IF(new.streak > 0, user_mastery.streak + 1, 0),
                    mastery = LEAST(100, ROUND(
                        (user_mastery.correct_attempts + new.correct_attempts) * 100.0
                        / (user_mastery.total_attempts + 1))),
                    next_review_at = IF(new.correct_attempts > 0,
                        LEAST(DATE_ADD(CURDATE(), INTERVAL 30 DAY),
                              DATE_ADD(CURDATE(), INTERVAL POW(2, COALESCE(user_mastery.streak, 0) + 1) DAY)),
                        DATE_ADD(CURDATE(), INTERVAL 1 DAY))
                """,
                userId, topic, isCorrect ? 100 : 0, isCorrect ? 1 : 0, isCorrect ? 1 : 0, isCorrect ? 1 : 0);
    }

    /**
     * 面试评估后更新掌握度。
     * 将分类平均分按题目数量折算为 correct_attempts 写入 user_mastery，
     * 与刷题数据共用同一公式，实现长期记忆融合。
     */
    public void updateMasteryFromInterview(Long userId, String topic, int score, int questionCount) {
        if (topic == null || topic.isBlank() || questionCount <= 0) return;
        jdbc.update("""
                INSERT INTO user_mastery (user_id, topic, mastery, total_attempts, correct_attempts, streak, next_review_at)
                VALUES (?, ?, ?, ?, ?, 0, DATE_ADD(CURDATE(), INTERVAL 7 DAY))
                AS new
                ON DUPLICATE KEY UPDATE
                    total_attempts = user_mastery.total_attempts + new.total_attempts,
                    correct_attempts = user_mastery.correct_attempts + new.correct_attempts,
                    mastery = LEAST(100, ROUND(
                        (user_mastery.correct_attempts + new.correct_attempts) * 100.0
                        / (user_mastery.total_attempts + new.total_attempts))),
                    next_review_at = DATE_ADD(CURDATE(), INTERVAL 7 DAY)
                """,
                userId, topic, score, questionCount, (int) Math.round(score * questionCount / 100.0));
        log.debug("Mastery updated from interview: userId={}, topic={}, score={}, questions={}",
                userId, topic, score, questionCount);
    }

    // ======================== 批量检查 ========================

    /**
     * 批量检查一批题目 ID 的答题状态。
     * 返回 questionId -> SubmitResult 的映射（仅针对已答题的题目）。
     */
    public Map<Long, SubmitResult> batchCheckStatus(Long userId, List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) return Map.of();

        String placeholders = questionIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.addAll(questionIds);
        params.add(userId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.question_id, r.user_answer, r.is_correct, r.score, q.answer, q.explanation " +
                "FROM quiz_record r " +
                "JOIN quiz_question q ON q.id = r.question_id " +
                "WHERE r.user_id = ? AND r.question_id IN (" + placeholders + ") " +
                "AND r.id = (SELECT MAX(r2.id) FROM quiz_record r2 WHERE r2.user_id = ? AND r2.question_id = r.question_id)",
                params.toArray());

        Map<Long, SubmitResult> result = new LinkedHashMap<>();
        for (var row : rows) {
            Long qid = ((Number) row.get("question_id")).longValue();
            boolean correct = ((Number) row.get("is_correct")).intValue() == 1;
            SubmitResult sr = new SubmitResult();
            sr.setCorrect(correct);
            sr.setScore(((Number) row.get("score")).intValue());
            sr.setCorrectAnswer(str(row.get("answer")));
            sr.setExplanation(str(row.get("explanation")));
            result.put(qid, sr);
        }
        return result;
    }

    // ======================== 内部方法 ========================

    /**
     * 根据 questionId 列表，批量从 MySQL 查出题目详情 + 用户最近一次错误答案
     */
    private List<WrongQuestionVO> fetchWrongQuestions(Long userId, List<Long> ids, String topic) {
        if (ids.isEmpty()) return List.of();

        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));

        String sql = """
            SELECT q.id, q.question_text, q.options_json, q.answer, q.explanation, q.topic,
                   r.user_answer, r.score, r.created_at
            FROM (
                SELECT question_id, MAX(id) AS max_id
                FROM quiz_record
                WHERE user_id = ? AND is_correct = 0 AND question_id IN (%s)
                GROUP BY question_id
            ) latest
            JOIN quiz_record r ON r.id = latest.max_id
            JOIN quiz_question q ON q.id = latest.question_id
            """ + (topic != null && !topic.isBlank() ? " AND q.topic = ?" : "") + """
            ORDER BY r.created_at DESC
            """;

        String finalSql = String.format(sql, placeholders);

        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.addAll(ids);
        if (topic != null && !topic.isBlank()) {
            params.add(topic);
        }

        RowMapper<WrongQuestionVO> mapper = (ResultSet rs, int rowNum) -> {
            WrongQuestionVO vo = new WrongQuestionVO();
            vo.setId(rs.getLong("id"));
            vo.setQuestionText(rs.getString("question_text"));
            vo.setCorrectAnswer(rs.getString("answer"));
            vo.setExplanation(rs.getString("explanation"));
            vo.setTopic(rs.getString("topic"));
            vo.setUserAnswer(rs.getString("user_answer"));
            vo.setScore(rs.getInt("score"));
            vo.setOptions(parseOptions(rs.getString("options_json")));
            Timestamp ts = rs.getTimestamp("created_at");
            vo.setWrongTime(ts != null ? ts.toLocalDateTime() : null);
            return vo;
        };

        return jdbc.query(finalSql, mapper, params.toArray());
    }

    private Map<String, String> parseOptions(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse options JSON: {}", json, e);
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }
}
