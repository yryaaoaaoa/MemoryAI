package com.jobai.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobai.rag.entity.ChatSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    @Update("UPDATE chat_session SET total_prompt_tokens = total_prompt_tokens + #{promptDelta}, " +
            "total_completion_tokens = total_completion_tokens + #{completionDelta}, " +
            "updated_at = NOW() WHERE id = #{sessionId}")
    int addTokenUsage(@Param("sessionId") Long sessionId,
                      @Param("promptDelta") int promptDelta,
                      @Param("completionDelta") int completionDelta);
}
