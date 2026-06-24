package com.jobai.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.knowledge.entity.Resume;
import com.jobai.knowledge.mapper.ResumeMapper;
import com.jobai.knowledge.parser.ResumeParser;
import com.jobai.knowledge.llm.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private final ResumeMapper resumeMapper;
    private final ResumeParser resumeParser;
    private final LlmService llmService;

    @Transactional
    public Resume upload(MultipartFile file, Long userId) {
        String hash = sha256(file);
        String fileName = file.getOriginalFilename();

        Resume existing = resumeMapper.selectOne(
                new LambdaQueryWrapper<Resume>().eq(Resume::getFileHash, hash));
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

        parseAsync(resume.getId(), file);

        return resume;
    }

    @Async
    public void parseAsync(Long resumeId, MultipartFile file) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) return;

        try {
            updateStatus(resume, "PARSING");

            String rawText;
            String fileName = file.getOriginalFilename();
            try (InputStream in = file.getInputStream()) {
                rawText = resumeParser.extractText(in, fileName);
            }

            if (rawText.isBlank()) {
                throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "简历内容为空");
            }

            if (fileName != null && fileName.toLowerCase().endsWith(".pdf") && llmService.isAvailable()) {
                try {
                    log.info("Reordering resume text (PDF multi-column detection): id={}", resumeId);
                    long start = System.currentTimeMillis();
                    rawText = llmService.reorderResumeText(rawText);
                    log.info("Resume text reordered: id={}, time={}ms", resumeId, System.currentTimeMillis() - start);
                } catch (Exception e) {
                    log.warn("Failed to reorder resume text, falling back to original: id={}, error={}", resumeId, e.getMessage());
                }
            }

            resume.setRawText(rawText);
            resumeMapper.updateById(resume);

            updateStatus(resume, "ANALYZING");
            String structuredJson = resumeParser.parseStructured(rawText);
            resume.setStructuredJson(structuredJson);

            resume.setStatus("READY");
            resumeMapper.updateById(resume);

            log.info("Resume parsed successfully: id={}, size={}", resumeId, rawText.length());

        } catch (Exception e) {
            log.error("Resume parse failed: id={}", resumeId, e);

            int retries = resume.getRetryCount() != null ? resume.getRetryCount() : 0;
            if (retries < 3) {
                resume.setRetryCount(retries + 1);
                resume.setStatus("PENDING_RETRY");
                resume.setErrorMsg(e.getMessage());
                resumeMapper.updateById(resume);
                log.info("Resume scheduled for retry: id={}, attempt={}/3", resumeId, retries + 1);
            } else {
                resume.setStatus("FAILED");
                resume.setErrorMsg(e.getMessage());
                resumeMapper.updateById(resume);
            }
        }
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
        resume.setStatus("UPLOADING");
        resume.setErrorMsg(null);
        resume.setRetryCount(0);
        resumeMapper.updateById(resume);
        parseAsync(id, file);
    }

    public void delete(Long id, Long userId) {
        getById(id, userId);
        resumeMapper.deleteById(id);
    }

    private void updateStatus(Resume resume, String status) {
        resume.setStatus(status);
        resumeMapper.updateById(resume);
    }

    private String sha256(MultipartFile file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = file.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件哈希计算失败");
        }
    }
}
