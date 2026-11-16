package com.jobai.knowledge.evaluation;

import com.jobai.common.BusinessException;
import com.jobai.common.evaluation.QaRecord;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedEvaluationServiceTest {
    private final UnifiedEvaluationService service = new UnifiedEvaluationService();
    private final List<QaRecord> questions = List.of(new QaRecord(0, "Q", "Java", "A"));

    @Test void failedModelDoesNotBecomeAnOfficialZeroScoreReport() {
        assertThrows(BusinessException.class, () -> service.evaluate((s, u) -> {
            throw new IllegalStateException("provider unavailable");
        }, "session", questions, "", ""));
    }

    @Test void invalidOrIncompleteEvaluationCannotCreateScores() {
        for (String raw : List.of("not json", "{}", "{\"questionEvaluations\":[]}",
                "{\"questionEvaluations\":[{\"questionIndex\":0,\"feedback\":\"good\"}]}")) {
            assertThrows(BusinessException.class, () -> service.evaluate((s, u) -> raw, "session", questions, "", ""));
        }
    }

    @Test void validZeroIsPreservedAndSummaryFailureDoesNotDiscardValidScores() {
        var report = service.evaluate((s, u) -> {
            if (u.contains("汇总生成最终报告")) throw new IllegalStateException("summary unavailable");
            return "{\"overallFeedback\":\"Needs practice\",\"questionEvaluations\":[{\"questionIndex\":0,\"score\":0,\"feedback\":\"Incorrect\"}]}";
        }, "session", questions, "", "");
        assertEquals(0, report.overallScore());
        assertEquals("Incorrect", report.questionDetails().get(0).feedback());
    }

    @Test void everyBatchReceivesExplicitGlobalQuestionIndices() {
        var records = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> new QaRecord(i, "Q" + i, "Java", "A")).toList();
        var received = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
        var report = service.evaluate((system, user) -> {
            if (!user.contains("questionIndex:")) return "{}";
            assertTrue(system.contains("禁止从 0 重新编号"));
            var matcher = java.util.regex.Pattern.compile("questionIndex: (\\d+)").matcher(user);
            var evaluations = new java.util.ArrayList<String>();
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                received.add(index);
                evaluations.add("{\"questionIndex\":" + index + ",\"score\":80,\"feedback\":\"Q" + index + " feedback\"}");
            }
            return "{\"questionEvaluations\":[" + String.join(",", evaluations) + "]}";
        }, "session", records, "", "");
        assertEquals(java.util.Set.of(0, 1, 2, 3, 4, 5, 6), received);
        assertEquals("Q5 feedback", report.questionDetails().get(5).feedback());
        assertEquals("Q6 feedback", report.questionDetails().get(6).feedback());
        assertEquals(80, report.overallScore());
    }
}
