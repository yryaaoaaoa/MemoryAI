package com.jobai.agent.interview.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("interview_answer")
public class InterviewAnswerEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private Integer questionIndex;
    private String question;
    private String category;
    private String userAnswer;
    private Integer score;
    private String feedback;
    private String referenceAnswer;

    /** JSON array of strings */
    private String keyPointsJson;

    private LocalDateTime answeredAt;
}
