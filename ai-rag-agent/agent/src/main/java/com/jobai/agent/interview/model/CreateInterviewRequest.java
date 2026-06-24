package com.jobai.agent.interview.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateInterviewRequest(
        String resumeText,

        @Min(value = 3, message = "题目数量最少3题")
        @Max(value = 20, message = "题目数量最多20题")
        int questionCount,

        Long resumeId,

        Boolean forceCreate,

        String llmProvider,

        @NotBlank(message = "面试方向不能为空")
        String skillId,

        String difficulty,

        /**
         * JD 原文。当 skillId 为 "custom" 时必传，用于解析面试方向分类和参考文件映射。
         * 预设方向（java-backend 等）无需传此字段。
         */
        String jdText,

        /**
         * 面试模式: "batch"（一次性出完全部题目，逐题回答）
         *          "dynamic"（自适应出题，根据回答质量动态生成下一题）
         * 默认 "batch"。
         */
        String mode) {

    public CreateInterviewRequest {
        if (mode == null) mode = "batch";
    }
}
