package com.jobai.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobai.rag.entity.LearningNote;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface LearningNoteMapper extends BaseMapper<LearningNote> {
    @Select("SELECT * FROM learning_note WHERE user_id=#{userId} AND create_request_id=#{requestId}")
    LearningNote findByCreateRequest(@Param("userId") Long userId, @Param("requestId") String requestId);

    // One autocommit statement: the owner/request unique key makes a lost-response retry safe.
    @Insert("""
            INSERT INTO learning_note (user_id,title,confusion,content,status,version,create_request_id,
              create_request_hash,source_session_id,source_message_ids,source_snapshot,created_at,updated_at)
            VALUES (#{userId},#{title},#{confusion},#{content},#{status},#{version},#{createRequestId},
              #{createRequestHash},#{sourceSessionId},#{sourceMessageIds},#{sourceSnapshot},#{createdAt},#{updatedAt})
            ON DUPLICATE KEY UPDATE id=id
            """)
    int insertOnce(LearningNote note);

    @Update("""
            UPDATE learning_note SET title=#{note.title},confusion=#{note.confusion},content=#{note.content},
              status=#{note.status},source_message_ids=#{note.sourceMessageIds},source_snapshot=#{note.sourceSnapshot},
              updated_at=#{note.updatedAt},version=version+1
            WHERE id=#{note.id} AND user_id=#{note.userId} AND version=#{expectedVersion}
            """)
    int updateIfVersion(@Param("note") LearningNote note, @Param("expectedVersion") long expectedVersion);
}
