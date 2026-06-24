package com.jobai.agent.interview.model;

import java.util.List;

public record InterviewSessionDTO(
        String sessionId,
        String resumeText,
        int totalQuestions,
        int currentQuestionIndex,
        List<InterviewQuestionDTO> questions,
        String status,
        String mode) {

    public enum Status {
        CREATED, IN_PROGRESS, COMPLETED, EVALUATED
    }
}
