package com.jobai.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobai.knowledge.entity.Resume;
import org.apache.ibatis.annotations.Update;

public interface ResumeMapper extends BaseMapper<Resume> {
    @Update("""
            UPDATE resume SET status = 'FAILED', error_msg = '上次解析已中断，请重新上传解析',
                updated_at = CURRENT_TIMESTAMP
            WHERE status IN ('UPLOADING', 'PARSING', 'ANALYZING') AND deleted_at IS NULL
            """)
    int recoverInterruptedParsing();
}
