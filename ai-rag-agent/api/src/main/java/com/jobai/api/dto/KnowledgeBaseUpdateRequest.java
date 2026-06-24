package com.jobai.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeBaseUpdateRequest {

    @NotBlank(message = "知识库名称不能为空")
    private String name;

    private String description;

    private String priority;
}
