package com.jobai.knowledge.service;

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
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeProcessor {
    private final ResumeMapper resumeMapper;
    private final ResumeParser resumeParser;
    private final LlmService llmService;

    @PostConstruct
    public void recoverInterruptedParsing() {
        resumeMapper.recoverInterruptedParsing();
    }

    @Async("taskExecutor")
    public void parseAsync(Long resumeId, String fileName, byte[] content) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) return;
        try {
            resume.setStatus("PARSING");
            resumeMapper.updateById(resume);
            String rawText = resumeParser.extractText(new ByteArrayInputStream(content), fileName);
            if (rawText == null || rawText.isBlank()) {
                throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "简历内容为空");
            }
            if (fileName.toLowerCase().endsWith(".pdf") && llmService.isAvailable()) {
                try {
                    String reordered = llmService.reorderResumeText(rawText);
                    if (reordered != null && !reordered.isBlank()) rawText = reordered;
                } catch (Exception e) {
                    log.warn("Resume reordering failed; retaining original text: id={}", resumeId, e);
                }
            }
            resume.setRawText(rawText);
            resume.setStatus("ANALYZING");
            resumeMapper.updateById(resume);
            resume.setStructuredJson(resumeParser.parseStructured(rawText));
            resume.setErrorMsg(llmService.isAvailable() ? "" : "模型未配置，已保留简历原文，可配置后重新解析");
            resume.setStatus("READY");
            resumeMapper.updateById(resume);
        } catch (Exception e) {
            log.error("Resume parsing failed: id={}", resumeId, e);
            resume.setStatus("FAILED");
            resume.setRetryCount((resume.getRetryCount() == null ? 0 : resume.getRetryCount()) + 1);
            resume.setErrorMsg("简历解析失败，请重新上传重试：" + e.getMessage());
            resumeMapper.updateById(resume);
        }
    }
}
