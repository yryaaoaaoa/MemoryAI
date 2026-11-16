package com.jobai.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 错题本与掌握度：以用户每道题的最新答题记录为准。
 * 手动移除持久化到记录中，不删除答题历史，也不改变掌握度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WrongQuestionService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private static final String ACTIVE_WRONG_FROM = """
            FROM quiz_record r
            JOIN quiz_question q ON q.id = r.question_id
            WHERE r.user_id = ? AND r.is_correct = 0 AND r.wrong_removed = 0
              AND (q.user_id = r.user_id OR q.user_id IS NULL)
              AND NOT EXISTS (SELECT 1 FROM quiz_record newer
                  WHERE newer.user_id = r.user_id AND newer.question_id = r.question_id AND newer.id > r.id)
            """;
    private static final String WRONG_COLUMNS = """
            SELECT q.id, q.question_text, q.options_json, q.answer, q.explanation, q.topic,
                   r.user_answer, r.score, r.created_at
            """;

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

    /** 移出当前错题；下一次答错会产生新的未移除记录。 */
    public void removeWrongQuestion(Long userId, Long questionId) {
        jdbc.update("UPDATE quiz_record SET wrong_removed = 1 WHERE user_id = ? AND question_id = ? AND is_correct = 0",
                userId, questionId);
    }

    public long getWrongQuestionCount(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) " + ACTIVE_WRONG_FROM, Long.class, userId);
    }

    public WrongQuestionPage listQuestions(Long userId, String topic, int page, int size) {
        List<Object> params = new ArrayList<>();
        params.add(userId);
        String from = ACTIVE_WRONG_FROM;
        if (topic != null && !topic.isBlank()) {
            from += " AND q.topic = ?";
            params.add(topic);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, params.toArray());
        params.add(size);
        params.add((long) (page - 1) * size);
        WrongQuestionPage result = new WrongQuestionPage();
        result.setRecords(jdbc.query(WRONG_COLUMNS + from + " ORDER BY r.id DESC LIMIT ? OFFSET ?",
                wrongQuestionMapper(), params.toArray()));
        result.setPage(page);
        result.setSize(size);
        result.setTotal(total);
        result.setTotalPages((total + size - 1) / size);
        return result;
    }

    public List<WrongTopicVO> listTopics(Long userId) {
        return jdbc.query("SELECT q.topic, COUNT(*) AS count " + ACTIVE_WRONG_FROM
                + " GROUP BY q.topic ORDER BY count DESC, q.topic", (rs, rowNum) -> {
            WrongTopicVO vo = new WrongTopicVO();
            vo.setTopic(rs.getString("topic"));
            vo.setCount(rs.getLong("count"));
            return vo;
        }, userId);
    }

    /** 从全部当前错题中抽取，而不只在最近的几题中抽取。 */
    public List<WrongQuestionVO> getRetryQuestions(Long userId, int count) {
        return jdbc.query(WRONG_COLUMNS + ACTIVE_WRONG_FROM + " ORDER BY RAND() LIMIT ?",
                wrongQuestionMapper(), userId, count);
    }

    /**
     * 提交答案 — 答题记录与掌握度在同一事务中保存
     */
    @Transactional
    public SubmitResult submitAnswer(Long userId, Long questionId, String userAnswer, int durationSec) {
        // 1. 取题
        var rows = jdbc.queryForList(
                """
                SELECT question_type, answer, explanation, topic FROM quiz_question q
                WHERE q.id = ? AND (q.user_id = ? OR (q.user_id IS NULL AND EXISTS (
                    SELECT 1 FROM quiz_record r WHERE r.question_id = q.id AND r.user_id = ?)))
                """, questionId, userId, userId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "题目不存在");
        var question = rows.get(0);

        String correctAnswer = str(question.get("answer"));
        String explanation = str(question.get("explanation"));
        String topic = str(question.get("topic"));
        String qType = str(question.get("question_type"));

        if (!"choice".equals(qType)) throw new IllegalArgumentException("此接口仅支持选择题");
        // 答案已在接口入口校验并标准化。
        boolean isCorrect = userAnswer.equalsIgnoreCase(correctAnswer.strip());
        int score = isCorrect ? 100 : 0;

        // 3. 入库
        jdbc.update(
                "INSERT INTO quiz_record (user_id, question_id, user_answer, is_correct, score, duration_sec, mastery_delta) VALUES (?, ?, ?, ?, ?, ?, 0)",
                userId, questionId, userAnswer, isCorrect ? 1 : 0, score, durationSec);

        // 4. 当前错题由最新记录计算；同时更新掌握度。
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
        // MySQL assignments run left to right: derive values before updating their counters.
        // Bound the exponent before DATE_ADD so long correct streaks cannot overflow a date.
        jdbc.update("""
                INSERT INTO user_mastery (user_id, topic, mastery, total_attempts, correct_attempts, streak, next_review_at)
                VALUES (?, ?, ?, 1, ?, ?,
                    DATE_ADD(CURDATE(), INTERVAL IF(?, 2, 1) DAY))
                AS new
                ON DUPLICATE KEY UPDATE
                    mastery = LEAST(100, ROUND(
                        (user_mastery.correct_attempts + new.correct_attempts) * 100.0
                        / (user_mastery.total_attempts + 1))),
                    next_review_at = IF(new.correct_attempts > 0,
                        DATE_ADD(CURDATE(), INTERVAL LEAST(30,
                            POW(2, LEAST(5, COALESCE(user_mastery.streak, 0) + 1))) DAY),
                        DATE_ADD(CURDATE(), INTERVAL 1 DAY)),
                    total_attempts = user_mastery.total_attempts + 1,
                    correct_attempts = user_mastery.correct_attempts + new.correct_attempts,
                    streak = IF(new.streak > 0, user_mastery.streak + 1, 0)
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
                    mastery = LEAST(100, ROUND(
                        (user_mastery.correct_attempts + new.correct_attempts) * 100.0
                        / (user_mastery.total_attempts + new.total_attempts))),
                    total_attempts = user_mastery.total_attempts + new.total_attempts,
                    correct_attempts = user_mastery.correct_attempts + new.correct_attempts,
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
                "AND (q.user_id = r.user_id OR q.user_id IS NULL) " +
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

    private RowMapper<WrongQuestionVO> wrongQuestionMapper() {
        return (rs, rowNum) -> {
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
