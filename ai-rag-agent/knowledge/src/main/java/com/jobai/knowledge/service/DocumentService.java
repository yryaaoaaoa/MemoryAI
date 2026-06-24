package com.jobai.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jobai.common.BusinessException;
import com.jobai.common.PageResult;
import com.jobai.common.ErrorCode;
import com.jobai.common.auth.AuthContext;
import com.jobai.knowledge.entity.Chunk;
import com.jobai.knowledge.entity.Document;
import com.jobai.knowledge.entity.KnowledgeBase;
import com.jobai.knowledge.mapper.ChunkMapper;
import com.jobai.knowledge.mapper.DocumentMapper;
import com.jobai.knowledge.mapper.KnowledgeBaseMapper;
import com.jobai.knowledge.store.KnowledgeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final DocumentProcessor documentProcessor;
    private final KnowledgeStore knowledgeStore;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public Document createDocument(MultipartFile file, Long kbId, Long userId) {
        // verify KB ownership
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getIsDelete() != null && kb.getIsDelete() == 1) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        // System public KB: only admin can upload
        if (Long.valueOf(0).equals(kb.getUserId()) && !AuthContext.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "系统公共知识库仅管理员可上传文档");
        }
        // Private KB: only owner can upload
        if (!Long.valueOf(0).equals(kb.getUserId()) && !userId.equals(kb.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权在此知识库上传文档");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不能为空");
        }

        byte[] fileContent;
        try {
            fileContent = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "文件读取失败: " + e.getMessage());
        }

        String fileHash = sha256(fileContent);
        Document existing = documentMapper.selectByFileHash(fileHash);
        if (existing != null) {
            if (existing.getIsDelete() == 1 || DocumentStatus.FAILED.equals(existing.getStatus())) {
                knowledgeStore.deleteByDocId(existing.getId());
                chunkMapper.delete(new LambdaQueryWrapper<Chunk>().eq(Chunk::getDocId, existing.getId()));

                existing.setFileName(fileName);
                existing.setFileSize(file.getSize());
                existing.setStatus(DocumentStatus.UPLOADING);
                existing.setChunkCount(0);
                existing.setErrorMessage(null);
                existing.setIsDelete(0);
                documentMapper.updateById(existing);

                documentProcessor.processDocument(existing.getId(), fileContent);
                return existing;
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件已存在，请勿重复上传");
        }

        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileHash(fileHash);
        doc.setFileSize(file.getSize());
        doc.setUserId(userId);
        doc.setStatus(DocumentStatus.UPLOADING);
        doc.setChunkCount(0);
        documentMapper.insert(doc);

        documentProcessor.processDocument(doc.getId(), fileContent);

        return doc;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
    }

    public Document getDocument(Long docId, Long userId) {
        Document doc = documentMapper.selectById(docId);
        if (doc == null || doc.getIsDelete() != null && doc.getIsDelete() == 1) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND, "文档不存在");
        }
        // Access allowed if doc belongs to user, or doc belongs to system KB (visible to all)
        if (doc.getUserId() != null && !userId.equals(doc.getUserId()) && !Long.valueOf(0).equals(doc.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问此文档");
        }
        return doc;
    }

    public PageResult<Document> listByKb(Long kbId, long pageNum, long pageSize, Long userId) {
        // verify KB ownership
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getIsDelete() != null && kb.getIsDelete() == 1) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        // Visible if system KB (userId=0) or user's own KB
        if (!Long.valueOf(0).equals(kb.getUserId()) && !userId.equals(kb.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问此知识库");
        }

        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .orderByDesc(Document::getCreatedAt);
        long total = documentMapper.selectCount(wrapper);
        long offset = (pageNum - 1) * pageSize;
        List<Document> records = documentMapper.selectList(wrapper.last("LIMIT " + offset + "," + pageSize));
        return PageResult.of(records, total, pageNum, pageSize);
    }

    public List<Chunk> getChunks(Long docId, Long userId) {
        getDocument(docId, userId);
        return chunkMapper.selectList(
                new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getDocId, docId)
                        .orderByAsc(Chunk::getChunkIndex)
        );
    }

    @Transactional
    public void deleteDocument(Long docId, Long userId) {
        Document doc = getDocument(docId, userId);
        knowledgeStore.deleteByDocId(docId);
        chunkMapper.delete(new LambdaQueryWrapper<Chunk>().eq(Chunk::getDocId, docId));
        documentMapper.deleteById(docId);
    }
}
