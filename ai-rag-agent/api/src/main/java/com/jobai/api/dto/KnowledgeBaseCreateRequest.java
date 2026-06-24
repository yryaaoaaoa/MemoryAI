package com.jobai.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeBaseCreateRequest {

    @NotBlank(message = "知识库名称不能为空")
    private String name;

    private String description;

    private String priority;

    /** Admin-only: create as system public KB (userId=0, visible to all) */
    private boolean system;
}
