package com.jobai.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.knowledge.entity.Resume;
import com.jobai.knowledge.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private final ResumeMapper resumeMapper;
    private final ResumeProcessor resumeProcessor;

    @Transactional
    public Resume upload(MultipartFile file, Long userId) {
        byte[] content = readContent(file);
        String hash = sha256(content);
        String fileName = file.getOriginalFilename();

        Resume existing = resumeMapper.selectOne(
                new LambdaQueryWrapper<Resume>().eq(Resume::getUserId, userId)
                        .eq(Resume::getFileHash, hash));
        if (existing != null) {
            log.info("Resume already exists: id={}, hash={}", existing.getId(), hash);
            return existing;
        }

        Resume resume = new Resume();
        resume.setFileName(fileName != null ? fileName : "unknown");
        resume.setFileHash(hash);
        resume.setFileSize(file.getSize());
        resume.setUserId(userId);
        resume.setStatus(DocumentStatus.UPLOADING);
        resume.setRetryCount(0);
        resumeMapper.insert(resume);

        scheduleParse(resume, content);

        return resume;
    }

    public List<Resume> list(Long userId) {
        return resumeMapper.selectList(
                new LambdaQueryWrapper<Resume>()
                        .eq(Resume::getUserId, userId)
                        .orderByDesc(Resume::getCreatedAt));
    }

    public Resume getById(Long id, Long userId) {
        Resume resume = resumeMapper.selectById(id);
        if (resume == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历不存在");
        }
        if (!userId.equals(resume.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问此简历");
        }
        return resume;
    }

    @Transactional
    public void reanalyze(Long id, MultipartFile file, Long userId) {
        Resume resume = getById(id, userId);
        byte[] content = readContent(file);
        String hash = sha256(content);
        if (List.of("UPLOADING", "PARSING", "ANALYZING").contains(resume.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历正在解析，请完成后再重新解析");
        }
        Resume duplicate = resumeMapper.selectOne(new LambdaQueryWrapper<Resume>()
                .eq(Resume::getUserId, userId).eq(Resume::getFileHash, hash).ne(Resume::getId, id));
        if (duplicate != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "此简历文件已存在，请使用已有简历");
        }
        resume.setFileHash(hash);
        resume.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown");
        resume.setFileSize(file.getSize());
        // Persist cleared values: nulls are ignored by MyBatis, and the JSON column rejects an empty string.
        resume.setRawText("");
        resume.setStructuredJson("{\"sections\":[]}");
        resume.setStatus("UPLOADING");
        resume.setErrorMsg("");
        resume.setRetryCount(0);
        resumeMapper.updateById(resume);
        scheduleParse(resume, content);
    }

    public void delete(Long id, Long userId) {
        getById(id, userId);
        resumeMapper.deleteById(id);
    }

    private void scheduleParse(Resume resume, byte[] content) {
        Runnable submit = () -> resumeProcessor.parseAsync(resume.getId(), resume.getFileName(), content);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { submit.run(); }
            });
        } else {
            submit.run();
        }
    }

    private byte[] readContent(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "读取简历文件失败");
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
