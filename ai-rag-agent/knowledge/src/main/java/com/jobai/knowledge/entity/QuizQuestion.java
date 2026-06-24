package com.jobai.knowledge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quiz_question")
public class QuizQuestion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private Long docId;
    private String topic;
    private String questionText;
    private String questionType;
    private String optionsJson;
    private String answer;
    private String explanation;
    private Long sourceChunkId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
