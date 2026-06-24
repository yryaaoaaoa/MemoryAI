package com.jobai.knowledge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chunk")
public class Chunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private Long kbId;

    private String content;

    private String headingPath;

    private Integer chunkIndex;

    private Integer tokenCount;

    private String vectorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
