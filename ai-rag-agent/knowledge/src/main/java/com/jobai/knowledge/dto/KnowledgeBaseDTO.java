package com.jobai.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeBaseDTO {
    private Long id;
    private String name;
    private String description;
    private Integer documentCount;
    private Integer chunkCount;
    private String priority;
    private Long userId;
    private boolean system;     // true = 系统公共库, false = 私有库
    private String ownerName;   // 拥有者用户名（admin 或当前用户）
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
