package com.jobai.agent.interview.model;

public record SubmitAnswerResponse(
        boolean hasNext,
        InterviewQuestionDTO nextQuestion,
        int currentIndex,
        int totalQuestions) {
}
