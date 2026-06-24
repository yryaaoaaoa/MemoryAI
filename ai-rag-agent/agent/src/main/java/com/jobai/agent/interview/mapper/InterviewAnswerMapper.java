package com.jobai.agent.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobai.agent.interview.model.InterviewAnswerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface InterviewAnswerMapper extends BaseMapper<InterviewAnswerEntity> {

    @Select("""
        SELECT * FROM interview_answer
        WHERE session_id = #{sessionId}
        ORDER BY question_index
        """)
    List<InterviewAnswerEntity> findBySessionId(@Param("sessionId") String sessionId);

    @Select("""
        SELECT * FROM interview_answer
        WHERE session_id = #{sessionId} AND question_index = #{questionIndex}
        """)
    Optional<InterviewAnswerEntity> findBySessionIdAndQuestionIndex(
            @Param("sessionId") String sessionId,
            @Param("questionIndex") int questionIndex);
}
