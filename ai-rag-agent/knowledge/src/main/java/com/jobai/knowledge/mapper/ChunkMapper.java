package com.jobai.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobai.knowledge.entity.Chunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChunkMapper extends BaseMapper<Chunk> {

    void insertBatch(@Param("list") List<Chunk> chunks);

    /** Only chunks committed by a completed, visible document may enter retrieval results. */
    @Select("""
            <script>
            SELECT c.vector_id FROM chunk c
            JOIN document d ON d.id = c.doc_id AND d.kb_id = c.kb_id
            JOIN knowledge_base kb ON kb.id = c.kb_id
            WHERE d.status = 'READY' AND d.is_delete = 0 AND kb.is_delete = 0
              AND (#{userId} IS NULL OR kb.user_id = 0 OR kb.user_id = #{userId})
              AND c.vector_id IN
              <foreach collection="vectorIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<String> selectReadyVectorIds(@Param("vectorIds") java.util.Collection<String> vectorIds,
                                     @Param("userId") Long userId);

    /** Caller checks knowledge-base visibility before sampling usable source content. */
    @Select("""
            SELECT c.* FROM chunk c
            JOIN document d ON d.id = c.doc_id AND d.kb_id = c.kb_id
            WHERE c.kb_id = #{kbId} AND d.status = 'READY' AND d.is_delete = 0
            ORDER BY c.doc_id, c.chunk_index
            LIMIT #{limit}
            """)
    List<Chunk> selectReadySamples(@Param("kbId") Long kbId, @Param("limit") int limit);
}
