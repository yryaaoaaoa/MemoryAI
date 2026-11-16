package com.jobai.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.common.BusinessException;
import com.jobai.knowledge.entity.Resume;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.knowledge.mapper.ResumeMapper;
import com.jobai.knowledge.parser.ResumeParser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumeProcessingTest {
    @Test
    void uploadSchedulesCopiedBytesOnlyAfterCommit() {
        var mapper = mock(ResumeMapper.class);
        var worker = mock(ResumeProcessor.class);
        var service = new ResumeService(mapper, worker);
        doAnswer(i -> { ((Resume)i.getArgument(0)).setId(8L); return 1; }).when(mapper).insert(any(Resume.class));
        byte[] bytes = "resume".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        TransactionSynchronizationManager.initSynchronization();
        try {
            var resume = service.upload(new MockMultipartFile("file", "r.txt", "text/plain", bytes), 1L);
            assertEquals("UPLOADING", resume.getStatus());
            verifyNoInteractions(worker);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            verify(worker).parseAsync(eq(8L), eq("r.txt"), eq(bytes));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test
    void invalidModelJsonAndMissingSectionsFailInsteadOfReturningNull() {
        var llm = mock(LlmService.class);
        when(llm.isAvailable()).thenReturn(true);
        var parser = new ResumeParser(new ObjectMapper(), llm);
        for (String json : new String[]{"not-json", "{}", "{\"sections\":[]}"}) {
            when(llm.chat(anyString(), anyString())).thenReturn(json);
            assertThrows(BusinessException.class, () -> parser.parseStructured("原文"));
        }
    }

    @Test
    void processingFailureRetainsRawTextAndIsManuallyRetryable() throws Exception {
        var mapper = mock(ResumeMapper.class);
        var parser = mock(ResumeParser.class);
        var resume = new Resume(); resume.setId(1L);
        when(mapper.selectById(1L)).thenReturn(resume);
        when(parser.extractText(any(), eq("r.txt"))).thenReturn("原始简历");
        when(parser.parseStructured(anyString())).thenThrow(new IllegalArgumentException("bad JSON"));
        new ResumeProcessor(mapper, parser, mock(LlmService.class)).parseAsync(1L, "r.txt", new byte[]{1});
        assertEquals("FAILED", resume.getStatus());
        assertEquals("原始简历", resume.getRawText());
        assertEquals(1, resume.getRetryCount());
        assertTrue(resume.getErrorMsg().contains("重新上传"));
    }

    @Test
    void validStructuredResumeAndUnavailableModelFallbackPreserveContent() throws Exception {
        var llm = mock(LlmService.class);
        var parser = new ResumeParser(new ObjectMapper(), llm);
        var raw = new ObjectMapper().readTree(parser.parseStructured("Java 开发"));
        assertEquals("Java 开发", raw.path("sections").get(0).path("content").asText());
        when(llm.isAvailable()).thenReturn(true);
        String valid = "{\"sections\":[{\"type\":\"skills\",\"content\":\"Java\",\"tags\":[\"Java\"]}]}";
        when(llm.chat(anyString(), anyString())).thenReturn(valid);
        assertEquals(valid, parser.parseStructured("Java"));
        var mapper = mock(ResumeMapper.class); var resume = new Resume(); resume.setId(1L);
        when(mapper.selectById(1L)).thenReturn(resume);
        new ResumeProcessor(mapper, parser, llm).parseAsync(1L, "r.txt", "Java".getBytes());
        assertEquals("READY", resume.getStatus()); assertEquals(valid, resume.getStructuredJson());
    }

    @Test
    void interruptedResumeIsRecoveredAndCanBeReanalyzedNormally() throws Exception {
        var mapper = mock(ResumeMapper.class);
        var parser = mock(ResumeParser.class);
        var llm = mock(LlmService.class);
        when(llm.isAvailable()).thenReturn(true);
        var worker = new ResumeProcessor(mapper, parser, llm);
        var service = new ResumeService(mapper, worker);
        var resume = new Resume(); resume.setId(9L); resume.setUserId(42L); resume.setStatus("ANALYZING");
        when(mapper.selectById(9L)).thenReturn(resume);
        when(mapper.recoverInterruptedParsing()).thenAnswer(i -> {
            resume.setStatus("FAILED"); resume.setErrorMsg("上次解析已中断，请重新上传解析");
            return 1;
        });
        when(parser.extractText(any(), eq("r.txt"))).thenReturn("Java 开发");
        when(parser.parseStructured("Java 开发")).thenReturn("{\"sections\":[{\"type\":\"skills\",\"content\":\"Java\",\"tags\":[]}]}");
        worker.recoverInterruptedParsing();
        assertEquals("FAILED", resume.getStatus());
        assertTrue(resume.getErrorMsg().contains("中断"));
        service.reanalyze(9L, new MockMultipartFile("file", "r.txt", "text/plain", "Java 开发".getBytes()), 42L);
        assertEquals("READY", resume.getStatus());
        assertEquals("", resume.getErrorMsg());
        verify(mapper).recoverInterruptedParsing();
        verify(parser).parseStructured("Java 开发");
    }
}
