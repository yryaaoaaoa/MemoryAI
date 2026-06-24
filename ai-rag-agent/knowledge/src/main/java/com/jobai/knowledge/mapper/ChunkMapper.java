package com.jobai.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobai.knowledge.entity.Chunk;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ChunkMapper extends BaseMapper<Chunk> {

    void insertBatch(@Param("list") List<Chunk> chunks);
}
