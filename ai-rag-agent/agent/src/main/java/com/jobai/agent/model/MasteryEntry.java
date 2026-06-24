package com.jobai.agent.model;

import java.time.LocalDate;

public record MasteryEntry(
        String topic,
        int mastery,
        int totalAttempts,
        int correctAttempts,
        LocalDate nextReviewAt
) {
    public MasteryEntry(String topic, int mastery, int totalAttempts, int correctAttempts) {
        this(topic, mastery, totalAttempts, correctAttempts, null);
    }
}
