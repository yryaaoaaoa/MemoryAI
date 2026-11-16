package com.jobai.agent.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobai.agent.interview.model.InterviewSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface InterviewSessionMapper extends BaseMapper<InterviewSessionEntity> {

    @Update("""
        UPDATE interview_session SET evaluate_status = 'FAILED',
          evaluate_error = '上次评估因服务重启中断，请重新评估'
        WHERE status = 'COMPLETED' AND (evaluate_status IS NULL OR evaluate_status IN ('PENDING', 'PROCESSING'))
        """)
    int failInterruptedEvaluations();

    @Select("SELECT * FROM interview_session WHERE session_id = #{sessionId}")
    Optional<InterviewSessionEntity> findBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM interview_session WHERE session_id = #{sessionId} FOR UPDATE")
    Optional<InterviewSessionEntity> findBySessionIdForUpdate(@Param("sessionId") String sessionId);

    @Select("""
        SELECT * FROM interview_session
        WHERE resume_id = #{resumeId} AND user_id = #{userId}
          AND status IN ('CREATED', 'IN_PROGRESS')
        ORDER BY created_at DESC
        LIMIT 1
        """)
    Optional<InterviewSessionEntity> findUnfinishedByResumeId(@Param("resumeId") Long resumeId, @Param("userId") Long userId);

    @Select("SELECT * FROM interview_session WHERE resume_id = #{resumeId} AND user_id = #{userId} ORDER BY created_at DESC")
    List<InterviewSessionEntity> findByResumeId(@Param("resumeId") Long resumeId, @Param("userId") Long userId);

    @Select("""
        SELECT * FROM interview_session
        WHERE skill_id = #{skillId} AND user_id = #{userId}
          AND (resume_text IS NOT NULL OR resume_id IS NULL OR EXISTS (
            SELECT 1 FROM resume r WHERE r.id = interview_session.resume_id AND r.user_id = #{userId}))
        ORDER BY created_at DESC
        LIMIT 10
        """)
    List<InterviewSessionEntity> findRecentBySkillId(@Param("skillId") String skillId, @Param("userId") Long userId);

    @Select("""
        SELECT * FROM interview_session
        WHERE resume_id = #{resumeId} AND skill_id = #{skillId} AND user_id = #{userId}
          AND EXISTS (SELECT 1 FROM resume r WHERE r.id = interview_session.resume_id AND r.user_id = #{userId})
        ORDER BY created_at DESC
        LIMIT 10
        """)
    List<InterviewSessionEntity> findRecentByResumeIdAndSkillId(
            @Param("resumeId") Long resumeId,
            @Param("skillId") String skillId, @Param("userId") Long userId);

    @Select("SELECT * FROM interview_session WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<InterviewSessionEntity> findAllOrderByCreatedAtDesc(@Param("userId") Long userId);
}
