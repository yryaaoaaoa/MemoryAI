package com.jobai.common.evaluation;

/**
 * 通用问答对 — 文本面试和语音面试评估共享。
 */
public record QaRecord(
        int questionIndex,
        String question,
        String category,
        String userAnswer) {
}
