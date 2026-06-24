package com.jobai.common.evaluation;

import java.util.List;

/**
 * 统一评估报告 — 语言无关，所有面试类型共享。
 */
public record EvaluationReport(
        String sessionId,
        int totalQuestions,
        int overallScore,
        List<CategoryScore> categoryScores,
        List<QuestionEvaluation> questionDetails,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<ReferenceAnswer> referenceAnswers) {

    public record CategoryScore(
            String category,
            int score,
            int answeredCount,
            int totalCount) {
    }

    public record QuestionEvaluation(
            int questionIndex,
            String question,
            String category,
            String userAnswer,
            int score,
            String feedback) {
    }

    public record ReferenceAnswer(
            int questionIndex,
            String question,
            String referenceAnswer,
            List<String> keyPoints) {
    }
}
