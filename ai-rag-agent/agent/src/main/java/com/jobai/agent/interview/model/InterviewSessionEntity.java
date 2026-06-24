package com.jobai.agent.interview.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("interview_session")
public class InterviewSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private String skillId;
    private String difficulty;
    private Long resumeId;
    private Integer totalQuestions;
    private Integer currentQuestionIndex;

    /** CREATED / IN_PROGRESS / COMPLETED / EVALUATED */
    private String status;

    /** JSON: List&lt;InterviewQuestionDTO&gt; */
    private String questionsJson;

    private Integer overallScore;
    private String overallFeedback;

    /** JSON array of strings */
    private String strengthsJson;
    private String improvementsJson;

    /** JSON: List&lt;ReferenceAnswer&gt; */
    private String referenceAnswersJson;

    private String llmProvider;

    /** Context summary (L2) — compressed summary of early rounds */
    private String contextSummary;

    /** batch / dynamic */
    private String mode;

    /** PENDING / PROCESSING / COMPLETED / FAILED */
    private String evaluateStatus;
    private String evaluateError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    private LocalDateTime deletedAt;

    private Long deletedBy;

    public enum Status {
        CREATED, IN_PROGRESS, COMPLETED, EVALUATED
    }

    public enum EvaluateStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }
}
