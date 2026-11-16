package com.jobai.agent.service;

import com.jobai.agent.model.MasteryEntry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMasteryServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserMasteryService service = new UserMasteryService(jdbc);

    private ResultSet row(String topic, int mastery, LocalDate nextReview) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("topic")).thenReturn(topic);
        when(rs.getInt("mastery")).thenReturn(mastery);
        when(rs.getInt("total_attempts")).thenReturn(10);
        when(rs.getInt("correct_attempts")).thenReturn(mastery / 10);
        when(rs.getDate("next_review_at")).thenReturn(nextReview == null ? null : Date.valueOf(nextReview));
        return rs;
    }

    private void rows(ResultSet... rows) {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<MasteryEntry>>any(), eq(42L)))
                .thenAnswer(invocation -> {
                    RowMapper<MasteryEntry> mapper = invocation.getArgument(1);
                    List<MasteryEntry> result = new ArrayList<>();
                    for (int i = 0; i < rows.length; i++) result.add(mapper.mapRow(rows[i], i));
                    return result;
                });
    }

    @Test void weakestTopicUsesDecayedScoreRatherThanOriginalDatabaseOrder() throws Exception {
        LocalDate today = LocalDate.now();
        rows(row("recent", 50, today), row("overdue", 80, today.minusDays(25)));

        var masteries = service.getMasteries(42L);
        assertEquals(List.of("overdue", "recent"), masteries.stream().map(MasteryEntry::topic).toList());
        assertEquals(40, masteries.get(0).mastery());
        assertEquals("overdue", service.getWeakestTopics(42L, 1).get(0).topic());
    }

    @Test void weakTopicSelectionRespectsDueDateAndThreshold() throws Exception {
        LocalDate today = LocalDate.now();
        rows(row("not due", 20, today.plusDays(1)), row("strong", 60, today),
                row("due", 59, today), row("unscheduled", 50, null));

        var weak = service.getWeakestTopics(42L, 10);
        assertEquals(List.of("unscheduled", "due"), weak.stream().map(MasteryEntry::topic).toList());
    }

    @Test void decayStartsAfterDueDateAndStopsAtHalfTheOriginalScore() throws Exception {
        LocalDate today = LocalDate.now();
        rows(row("old", 80, today.minusDays(100)), row("today", 80, today),
                row("tomorrow", 80, today.plusDays(1)), row("unset", 80, null));

        var masteries = service.getMasteries(42L);
        assertEquals(List.of(40, 80, 80, 80), masteries.stream().map(MasteryEntry::mastery).toList());
    }

    @Test void historicalLongestStreakSurvivesLaterWrongAnswers() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (int correct : new int[]{1, 1, 1, 0, 1, 1, 0}) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getInt("is_correct")).thenReturn(correct);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), eq(42L));

        assertEquals(3, service.getMaxStreak(42L));
        assertEquals(0, service.getMaxStreak(43L));
    }

    @Test void databaseFailureDoesNotMasqueradeAsNoLearningHistory() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), eq(42L)))
                .thenThrow(failure);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(42L))).thenThrow(failure);
        doThrow(failure).when(jdbc).query(anyString(), any(RowCallbackHandler.class), eq(42L));

        assertThrows(DataAccessResourceFailureException.class, () -> service.getMasteries(42L));
        assertThrows(DataAccessResourceFailureException.class, () -> service.getTechStack(42L));
        assertThrows(DataAccessResourceFailureException.class, () -> service.getTotalQuizzes(42L));
        assertThrows(DataAccessResourceFailureException.class, () -> service.getCorrectRate(42L));
        assertThrows(DataAccessResourceFailureException.class, () -> service.getMaxStreak(42L));
    }
}
