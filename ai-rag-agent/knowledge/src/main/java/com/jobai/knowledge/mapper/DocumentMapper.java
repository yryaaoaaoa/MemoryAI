package com.jobai.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobai.knowledge.entity.Document;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface DocumentMapper extends BaseMapper<Document> {

    @Select("SELECT d.* FROM document d JOIN knowledge_base kb ON kb.id = d.kb_id "
            + "WHERE d.id = #{docId} AND d.is_delete = 0 AND kb.is_delete = 0")
    Document selectProcessable(@Param("docId") Long docId);

    @Update("""
            UPDATE document SET status = 'FAILED', error_message = '处理已停止，可重新上传或重试删除',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{docId} AND is_delete = 0
                AND status IN ('UPLOADING', 'PARSING', 'CHUNKING', 'EMBEDDING')
            """)
    int markCancelledProcessing(@Param("docId") Long docId);

    /** bypass logic-delete filter to find soft-deleted / failed documents */
    @Select("SELECT * FROM document WHERE kb_id = #{kbId} AND file_hash = #{fileHash} LIMIT 1")
    Document selectByKbAndFileHash(@Param("kbId") Long kbId, @Param("fileHash") String fileHash);

    /** Explicitly restore a deleted row; updateById deliberately excludes logically deleted rows. */
    @Update("""
            UPDATE document SET file_name = #{doc.fileName}, file_size = #{doc.fileSize},
                user_id = #{doc.userId}, status = 'UPLOADING', chunk_count = 0,
                error_message = NULL, is_delete = 0, deleted_at = NULL, deleted_by = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{doc.id} AND kb_id = #{doc.kbId} AND file_hash = #{doc.fileHash}
                AND (is_delete = 1 OR status = 'FAILED')
            """)
    int restartDocument(@Param("doc") Document doc);
}
