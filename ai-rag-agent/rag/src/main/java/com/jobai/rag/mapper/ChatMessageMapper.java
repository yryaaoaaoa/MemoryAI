package com.jobai.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobai.rag.entity.ChatMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY message_order DESC LIMIT #{limit}")
    List<ChatMessage> findRecentBySessionId(@Param("sessionId") Long sessionId, @Param("limit") int limit);

    @Select("SELECT MAX(message_order) FROM chat_message WHERE session_id = #{sessionId}")
    Integer selectMaxOrder(@Param("sessionId") Long sessionId);

    @Select("SELECT COUNT(*) FROM chat_message WHERE session_id = #{sessionId} AND role = 'user'")
    int countUserMessages(@Param("sessionId") Long sessionId);

    @Select("SELECT message_order FROM chat_message WHERE session_id = #{sessionId} AND role = 'user' ORDER BY message_order ASC LIMIT 1 OFFSET #{offset}")
    Integer findNthUserMessageOrder(@Param("sessionId") Long sessionId, @Param("offset") int offset);

    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} AND message_order >= #{startOrder} ORDER BY message_order ASC")
    List<ChatMessage> findByMinOrder(@Param("sessionId") Long sessionId, @Param("startOrder") int startOrder);

    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} AND message_order >= #{startOrder} AND message_order < #{endOrder} ORDER BY message_order ASC")
    List<ChatMessage> findByOrderRange(@Param("sessionId") Long sessionId, @Param("startOrder") int startOrder, @Param("endOrder") int endOrder);
}
