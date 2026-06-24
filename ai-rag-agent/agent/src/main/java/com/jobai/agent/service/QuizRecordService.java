package com.jobai.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 刷题记录查询 — 从 quiz_record JOIN quiz_question 拉取完整答题历史。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizRecordService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Data
    public static class QuizRecordVO {
        private Long id;
        private Long questionId;
        private String questionText;
        private Map<String, String> options;
        private String correctAnswer;
        private String explanation;
        private String topic;
        private String userAnswer;
        private boolean isCorrect;
        private int score;
        private int duration;
        private LocalDateTime createdAt;
    }

    @Data
    public static class QuizRecordPage {
        private List<QuizRecordVO> records;
        private int page;
        private int size;
        private long total;
        private long totalPages;

        public static QuizRecordPage empty(int page, int size) {
            QuizRecordPage p = new QuizRecordPage();
            p.records = List.of();
            p.page = page;
            p.size = size;
            p.total = 0;
            p.totalPages = 0;
            return p;
        }
    }

    /**
     * 分页查询刷题记录（最新在前）
     *
     * @param userId   用户 ID
     * @param topic    知识点筛选（null 表示全部）
     * @param correct  正确/错误筛选（null 表示全部）
     * @param dateFrom 起始日期（null 不限）
     * @param dateTo   截止日期（null 不限）
     * @param page     页码（从 1 开始）
     * @param size     每页条数
     */
    public QuizRecordPage listRecords(Long userId, String topic, Boolean correct,
                                       LocalDate dateFrom, LocalDate dateTo,
                                       int page, int size) {
        List<Object> countParams = new ArrayList<>();
        countParams.add(userId);

        StringBuilder where = new StringBuilder("WHERE qr.user_id = ?");

        if (topic != null && !topic.isBlank()) {
            where.append(" AND q.topic = ?");
            countParams.add(topic);
        }
        if (correct != null) {
            where.append(" AND qr.is_correct = ?");
            countParams.add(correct ? 1 : 0);
        }
        if (dateFrom != null) {
            where.append(" AND qr.created_at >= ?");
            countParams.add(dateFrom.atStartOfDay());
        }
        if (dateTo != null) {
            where.append(" AND qr.created_at <= ?");
            countParams.add(dateTo.plusDays(1).atStartOfDay());
        }

        long total;
        try {
            Long cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM quiz_record qr JOIN quiz_question q ON q.id = qr.question_id " + where,
                    Long.class, countParams.toArray());
            total = cnt != null ? cnt : 0;
        } catch (Exception e) {
            log.warn("Count quiz records failed: {}", e.getMessage());
            return QuizRecordPage.empty(page, size);
        }

        if (total == 0) {
            return QuizRecordPage.empty(page, size);
        }

        int offset = (page - 1) * size;
        List<Object> queryParams = new ArrayList<>(countParams);
        queryParams.add(size);
        queryParams.add(offset);

        String sql = """
                SELECT qr.id, qr.question_id, qr.user_answer, qr.is_correct, qr.score, qr.duration_sec, qr.created_at,
                       q.question_text, q.options_json, q.answer, q.explanation, q.topic
                FROM quiz_record qr
                JOIN quiz_question q ON q.id = qr.question_id
                %s
                ORDER BY qr.created_at DESC
                LIMIT ? OFFSET ?
                """.formatted(where);

        RowMapper<QuizRecordVO> mapper = (rs, rowNum) -> {
            QuizRecordVO vo = new QuizRecordVO();
            vo.setId(rs.getLong("id"));
            vo.setQuestionId(rs.getLong("question_id"));
            vo.setQuestionText(rs.getString("question_text"));
            vo.setCorrectAnswer(rs.getString("answer"));
            vo.setExplanation(rs.getString("explanation"));
            vo.setTopic(rs.getString("topic"));
            vo.setUserAnswer(rs.getString("user_answer"));
            vo.setCorrect(rs.getInt("is_correct") == 1);
            vo.setScore(rs.getInt("score"));
            vo.setDuration(rs.getInt("duration_sec"));
            vo.setOptions(parseOptions(rs.getString("options_json")));
            Timestamp ts = rs.getTimestamp("created_at");
            vo.setCreatedAt(ts != null ? ts.toLocalDateTime() : null);
            return vo;
        };

        List<QuizRecordVO> records = jdbc.query(sql, mapper, queryParams.toArray());

        QuizRecordPage result = new QuizRecordPage();
        result.setRecords(records);
        result.setPage(page);
        result.setSize(size);
        result.setTotal(total);
        result.setTotalPages((total + size - 1) / size);
        return result;
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
}
