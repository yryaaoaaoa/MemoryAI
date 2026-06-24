package com.jobai.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobai.knowledge.entity.Document;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DocumentMapper extends BaseMapper<Document> {

    /** bypass logic-delete filter to find soft-deleted / failed documents */
    @Select("SELECT * FROM document WHERE file_hash = #{fileHash} LIMIT 1")
    Document selectByFileHash(@Param("fileHash") String fileHash);
}
