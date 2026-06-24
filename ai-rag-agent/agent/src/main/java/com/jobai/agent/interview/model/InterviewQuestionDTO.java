package com.jobai.agent.interview.model;

/**
 * Interview question — immutable data carrier.
 * type is the skill category key (e.g. MYSQL, SPRING).
 */
public record InterviewQuestionDTO(
        int questionIndex,
        String question,
        String type,
        String category,
        String topicSummary,
        String userAnswer,
        Integer score,
        String feedback,
        boolean isFollowUp,
        Integer parentQuestionIndex) {

    public static InterviewQuestionDTO create(int index, String question, String type, String category) {
        return new InterviewQuestionDTO(index, question, type, category, null, null, null, null, false, null);
    }

    public static InterviewQuestionDTO create(int index, String question, String type, String category,
                                               String topicSummary, boolean isFollowUp, Integer parentIndex) {
        return new InterviewQuestionDTO(index, question, type, category, topicSummary, null, null, null,
                isFollowUp, parentIndex);
    }

    public InterviewQuestionDTO withAnswer(String answer) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary,
                answer, score, feedback, isFollowUp, parentQuestionIndex);
    }

    public InterviewQuestionDTO withEvaluation(int evalScore, String evalFeedback) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary,
                userAnswer, evalScore, evalFeedback, isFollowUp, parentQuestionIndex);
    }
}
