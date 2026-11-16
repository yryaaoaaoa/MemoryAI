package com.jobai.rag.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("learning_note")
public class LearningNote {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String confusion;
    private String content;
    private String status;
    private Long version;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String createRequestId;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String createRequestHash;
    private Long sourceSessionId;
    private String sourceMessageIds;
    private String sourceSnapshot;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
