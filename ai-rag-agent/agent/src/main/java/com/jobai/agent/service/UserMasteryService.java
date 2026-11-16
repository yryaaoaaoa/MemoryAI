package com.jobai.agent.service;

import com.jobai.agent.model.MasteryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * user_mastery 表查询服务 — 读取长期掌握度数据。
 * 当前由 WrongQuestionService 的刷题提交更新；面试画像独立保存在 user_skill_profile。
 *
 * <p>读取时自动施加时间衰减：
 * 超过计划复习日期后，每过 1 天，mastery 衰减 2%，最低保留 50%。
 * 衰减不落库，仅影响读取展示和出题优先级。</p>
 */
@Service
@RequiredArgsConstructor
public class UserMasteryService {

    /** 每日衰减率（2%） */
    private static final double DAILY_DECAY = 0.02;
    /** 衰减下限（保留原始 mastery 的 50%） */
    private static final double MIN_DECAY_FACTOR = 0.5;

    private final JdbcTemplate jdbc;

    /**
     * 查询某个用户的所有掌握度记录，按掌握度升序（薄弱优先）。
     * 返回时已施加时间衰减。
     */
    public List<MasteryEntry> getMasteries(Long userId) {
        if (userId == null) return List.of();
        LocalDate today = LocalDate.now();
        return jdbc.query(
                "SELECT topic, mastery, total_attempts, correct_attempts, next_review_at FROM user_mastery WHERE user_id = ?",
                (rs, rowNum) -> {
                    int rawMastery = rs.getInt("mastery");
                    java.sql.Date reviewSql = rs.getDate("next_review_at");
                    LocalDate nextReview = reviewSql != null ? reviewSql.toLocalDate() : null;
                    int decayed = applyDecay(rawMastery, nextReview, today);
                    return new MasteryEntry(
                            rs.getString("topic"),
                            decayed,
                            rs.getInt("total_attempts"),
                            rs.getInt("correct_attempts"),
                            nextReview);
                },
                userId).stream()
                .sorted(Comparator.comparingInt(MasteryEntry::mastery).thenComparing(MasteryEntry::topic))
                .toList();
    }

    /**
     * 查询掌握度最低的 N 个 topic（薄弱知识点），已考虑时间衰减。
     * 只返回到该复习了的 topic（next_review_at 为空或已到期）。
     */
    public List<MasteryEntry> getWeakestTopics(Long userId, int limit) {
        LocalDate today = LocalDate.now();
        return getMasteries(userId).stream()
                .filter(m -> m.mastery() < 60)
                .filter(m -> m.nextReviewAt() == null || !m.nextReviewAt().isAfter(today))
                .limit(limit)
                .toList();
    }

    /**
     * 对 mastery 施加时间衰减。
     *
     * @param rawMastery 数据库原始掌握度
     * @param nextReview 上次设定的下次复习日期（null 表示从未设置，不衰减）
     * @param today      当前日期
     * @return 衰减后的掌握度
     */
    private int applyDecay(int rawMastery, LocalDate nextReview, LocalDate today) {
        if (nextReview == null) return rawMastery;
        long daysOverdue = ChronoUnit.DAYS.between(nextReview, today);
        if (daysOverdue <= 0) return rawMastery;

        double factor = Math.max(MIN_DECAY_FACTOR, 1.0 - daysOverdue * DAILY_DECAY);
        return (int) Math.round(rawMastery * factor);
    }

    /**
     * 获取总答题数。
     */
    public int getTotalQuizzes(Long userId) {
        Integer val = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quiz_record WHERE user_id = ?", Integer.class, userId);
        return val != null ? val : 0;
    }

    /**
     * 获取正确率（0-100）。
     */
    public int getCorrectRate(Long userId) {
        Integer val = jdbc.queryForObject(
                "SELECT COALESCE(ROUND(AVG(is_correct) * 100), 0) FROM quiz_record WHERE user_id = ?",
                Integer.class, userId);
        return val != null ? val : 0;
    }

    /**
     * 获取最长连续正确次数。
     */
    public int getMaxStreak(Long userId) {
        // A topic's current streak is not the user's historical best across all quizzes.
        int[] currentAndMax = {0, 0};
        jdbc.query("SELECT is_correct FROM quiz_record WHERE user_id = ? ORDER BY id",
                (RowCallbackHandler) rs -> {
                    currentAndMax[0] = rs.getInt("is_correct") == 1 ? currentAndMax[0] + 1 : 0;
                    currentAndMax[1] = Math.max(currentAndMax[1], currentAndMax[0]);
                }, userId);
        return currentAndMax[1];
    }

    /**
     * 获取技术栈（从已答题的 topic 去重提取）。
     */
    public List<String> getTechStack(Long userId) {
        return jdbc.query(
                "SELECT DISTINCT topic FROM user_mastery WHERE user_id = ? AND total_attempts > 0 ORDER BY topic",
                (rs, rowNum) -> rs.getString("topic"),
                userId);
    }
}
