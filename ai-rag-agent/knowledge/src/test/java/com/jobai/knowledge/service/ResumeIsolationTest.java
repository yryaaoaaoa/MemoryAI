package com.jobai.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jobai.common.BusinessException;
import com.jobai.knowledge.entity.Resume;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.knowledge.mapper.ResumeMapper;
import com.jobai.knowledge.parser.ResumeParser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumeIsolationTest {
    private ResumeMapper mapper;
    private ResumeParser parser;
    private ResumeService service;
    private final MockMultipartFile file = new MockMultipartFile("file", "updated.md", "text/markdown",
            "updated content".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Resume.class);
        mapper = mock(ResumeMapper.class);
        parser = mock(ResumeParser.class);
        service = new ResumeService(mapper, new ResumeProcessor(mapper, parser, mock(LlmService.class)));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void uploadDeduplicationRequiresBothCurrentUserAndHash() throws Exception {
        Resume existing = resume(7L, 5L);
        when(mapper.selectOne(any())).thenReturn(existing);
        assertSame(existing, service.upload(file, 5L));
        ArgumentCaptor<LambdaQueryWrapper> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectOne(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id ="), sql);
        assertTrue(sql.contains("file_hash ="), sql);
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(5L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(hash(file.getBytes())));
        verifyNoInteractions(parser);
    }

    @Test
    void reanalysisUpdatesIdentityOfUploadedFileAndClearsStaleContentOnFailure() throws Exception {
        Resume existing = resume(7L, 5L);
        existing.setRawText("old private text");
        existing.setStructuredJson("{\"sections\":[{\"type\":\"raw\",\"content\":\"old analysis\",\"tags\":[]}]}");
        when(mapper.selectById(7L)).thenReturn(existing);
        when(parser.extractText(any(), eq("updated.md"))).thenThrow(new IOException("invalid document"));
        service.reanalyze(7L, file, 5L);
        assertEquals(hash(file.getBytes()), existing.getFileHash());
        assertEquals("updated.md", existing.getFileName());
        assertEquals(file.getSize(), existing.getFileSize());
        assertEquals("", existing.getRawText());
        var cleared = new com.fasterxml.jackson.databind.ObjectMapper().readTree(existing.getStructuredJson());
        assertTrue(cleared.path("sections").isArray());
        assertTrue(cleared.path("sections").isEmpty());
        assertFalse(existing.getStructuredJson().contains("old analysis"));
        assertNotEquals("READY", existing.getStatus());
    }

    @Test
    void reanalysisCannotReplaceAnotherUsersResume() {
        Resume existing = resume(7L, 5L);
        when(mapper.selectById(7L)).thenReturn(existing);
        assertThrows(BusinessException.class, () -> service.reanalyze(7L, file, 6L));
        verify(mapper, never()).updateById(any(Resume.class));
        verifyNoInteractions(parser);
    }

    @Test
    void reanalysisRejectsFileAlreadyAssignedToAnotherResumeOfSameUser() {
        Resume existing = resume(7L, 5L);
        when(mapper.selectById(7L)).thenReturn(existing);
        when(mapper.selectOne(any())).thenReturn(resume(8L, 5L));
        assertThrows(BusinessException.class, () -> service.reanalyze(7L, file, 5L));
        assertEquals("original.md", existing.getFileName());
        verify(mapper, never()).updateById(any(Resume.class));
        verifyNoInteractions(parser);
    }

    private static Resume resume(long id, long userId) {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setUserId(userId);
        resume.setFileName("original.md");
        resume.setFileHash("old-hash");
        resume.setStatus("READY");
        return resume;
    }

    private static String hash(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
