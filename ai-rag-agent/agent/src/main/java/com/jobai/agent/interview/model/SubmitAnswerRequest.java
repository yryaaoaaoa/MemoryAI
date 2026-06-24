package com.jobai.agent.interview.model;

public record SubmitAnswerRequest(
        String sessionId,
        int questionIndex,
        String answer) {
}
