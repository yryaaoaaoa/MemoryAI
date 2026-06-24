package com.jobai.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.PageResult;
import com.jobai.common.auth.AuthContext;
import com.jobai.infrastructure.entity.User;
import com.jobai.infrastructure.mapper.UserMapper;
import com.jobai.knowledge.dto.KnowledgeBaseDTO;
import com.jobai.knowledge.entity.Chunk;
import com.jobai.knowledge.entity.Document;
import com.jobai.knowledge.entity.KnowledgeBase;
import com.jobai.knowledge.mapper.ChunkMapper;
import com.jobai.knowledge.mapper.DocumentMapper;
import com.jobai.knowledge.mapper.KnowledgeBaseMapper;
import com.jobai.knowledge.store.KnowledgeStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final KnowledgeStore knowledgeStore;
    private final UserMapper userMapper;

    /**
     * List knowledge bases visible to the current user.
     * @param type "system" = only system public KBs, "private" = only user's own, null/"all" = both
     */
    public PageResult<KnowledgeBase> list(int page, int size, Long userId, String type) {
        Page<KnowledgeBase> mpPage = new Page<>(page, size);
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();

        if ("system".equals(type)) {
            wrapper.eq(KnowledgeBase::getUserId, 0L);
        } else if ("private".equals(type)) {
            wrapper.eq(KnowledgeBase::getUserId, userId);
        } else {
            wrapper.and(w -> w.eq(KnowledgeBase::getUserId, userId)
                               .or().eq(KnowledgeBase::getUserId, 0L));
        }

        wrapper.orderByAsc(KnowledgeBase::getUserId)  // system KBs first
               .orderByDesc(KnowledgeBase::getCreatedAt);

        Page<KnowledgeBase> result = knowledgeBaseMapper.selectPage(mpPage, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }

    /** Backward-compatible: list all visible KBs */
    public PageResult<KnowledgeBase> list(int page, int size, Long userId) {
        return list(page, size, userId, null);
    }

    public KnowledgeBase getById(Long id, Long userId) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null || kb.getIsDelete() != null && kb.getIsDelete() == 1) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        // visible if it's the user's own KB, or a system public KB (userId=0)
        if (!userId.equals(kb.getUserId()) && !Long.valueOf(0).equals(kb.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问此知识库");
        }
        return kb;
    }

    /**
     * Create a knowledge base. Regular users always create private KBs.
     * Admin users can create system public KBs (userId=0) by passing a flag —
     * but for simplicity, regular users create private; admin creates via admin UI.
     */
    public KnowledgeBase create(KnowledgeBaseDTO dto, Long userId) {
        return create(dto, userId, false);
    }

    /**
     * Create a knowledge base. Admin users can set system=true to create
     * a system public KB (userId=0). Regular users always create private KBs.
     */
    public KnowledgeBase create(KnowledgeBaseDTO dto, Long userId, boolean system) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(dto.getName());
        kb.setDescription(dto.getDescription());
        kb.setPriority(dto.getPriority());
        if (system && AuthContext.isAdmin()) {
            kb.setUserId(0L);
        } else {
            kb.setUserId(userId);
        }
        knowledgeBaseMapper.insert(kb);
        return kb;
    }

    public KnowledgeBase update(Long id, KnowledgeBaseDTO dto, Long userId) {
        KnowledgeBase kb = getById(id, userId);
        // Only the owner (or admin for system KBs) can update
        if (Long.valueOf(0).equals(kb.getUserId())) {
            if (!AuthContext.isAdmin()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "系统公共知识库仅管理员可修改");
            }
        } else if (!userId.equals(kb.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权修改此知识库");
        }
        kb.setName(dto.getName());
        kb.setDescription(dto.getDescription());
        kb.setPriority(dto.getPriority());
        knowledgeBaseMapper.updateById(kb);
        return kb;
    }

    public void delete(Long id, Long userId) {
        KnowledgeBase kb = getById(id, userId);
        if (Long.valueOf(0).equals(kb.getUserId())) {
            if (!AuthContext.isAdmin()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "系统公共知识库仅管理员可删除");
            }
        } else if (!userId.equals(kb.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权删除此知识库");
        }
        knowledgeBaseMapper.deleteById(kb.getId());
        knowledgeStore.deleteByKbId(id);
    }

    public KnowledgeBaseDTO toDTO(KnowledgeBase kb) {
        KnowledgeBaseDTO dto = new KnowledgeBaseDTO();
        dto.setId(kb.getId());
        dto.setName(kb.getName());
        dto.setDescription(kb.getDescription());
        dto.setDocumentCount(Math.toIntExact(
                documentMapper.selectCount(new LambdaQueryWrapper<Document>().eq(Document::getKbId, kb.getId()))));
        dto.setChunkCount(Math.toIntExact(
                chunkMapper.selectCount(new LambdaQueryWrapper<Chunk>().eq(Chunk::getKbId, kb.getId()))));
        dto.setPriority(kb.getPriority());
        dto.setUserId(kb.getUserId());
        dto.setSystem(kb.getUserId() != null && kb.getUserId() == 0L);
        if (kb.getUserId() != null && kb.getUserId() == 0L) {
            dto.setOwnerName("系统管理员");
        } else if (kb.getUserId() != null) {
            User owner = userMapper.selectById(kb.getUserId());
            dto.setOwnerName(owner != null ? owner.getUsername() : "用户" + kb.getUserId());
        }
        dto.setCreatedAt(kb.getCreatedAt());
        dto.setUpdatedAt(kb.getUpdatedAt());
        return dto;
    }
}
