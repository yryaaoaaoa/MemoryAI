package com.jobai.agent.interview.model;

/**
 * Compressed historical question for deduplication in question generation.
 */
public record HistoricalQuestion(
        String question,
        String type,
        String topicSummary) {
}
