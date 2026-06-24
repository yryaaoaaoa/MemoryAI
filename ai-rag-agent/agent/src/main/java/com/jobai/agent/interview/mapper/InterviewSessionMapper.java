package com.jobai.agent.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobai.agent.interview.model.InterviewSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface InterviewSessionMapper extends BaseMapper<InterviewSessionEntity> {

    @Select("SELECT * FROM interview_session WHERE session_id = #{sessionId}")
    Optional<InterviewSessionEntity> findBySessionId(@Param("sessionId") String sessionId);

    @Select("""
        SELECT * FROM interview_session
        WHERE resume_id = #{resumeId}
          AND status IN ('CREATED', 'IN_PROGRESS')
        ORDER BY created_at DESC
        LIMIT 1
        """)
    Optional<InterviewSessionEntity> findUnfinishedByResumeId(@Param("resumeId") Long resumeId);

    @Select("SELECT * FROM interview_session WHERE resume_id = #{resumeId} ORDER BY created_at DESC")
    List<InterviewSessionEntity> findByResumeId(@Param("resumeId") Long resumeId);

    @Select("""
        SELECT * FROM interview_session
        WHERE skill_id = #{skillId}
        ORDER BY created_at DESC
        LIMIT 10
        """)
    List<InterviewSessionEntity> findRecentBySkillId(@Param("skillId") String skillId);

    @Select("""
        SELECT * FROM interview_session
        WHERE resume_id = #{resumeId} AND skill_id = #{skillId}
        ORDER BY created_at DESC
        LIMIT 10
        """)
    List<InterviewSessionEntity> findRecentByResumeIdAndSkillId(
            @Param("resumeId") Long resumeId,
            @Param("skillId") String skillId);

    @Select("SELECT * FROM interview_session ORDER BY created_at DESC")
    List<InterviewSessionEntity> findAllOrderByCreatedAtDesc();
}
