package com.jobai.agent.interview.model;

import java.util.List;

public record InterviewReportDTO(
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
            int questionCount) {
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
