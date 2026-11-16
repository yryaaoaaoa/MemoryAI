package com.jobai.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jobai.common.JobAiProperties;
import com.jobai.knowledge.entity.Chunk;
import com.jobai.knowledge.entity.Document;
import com.jobai.knowledge.entity.KnowledgeBase;
import com.jobai.knowledge.mapper.KnowledgeBaseMapper;
import com.jobai.knowledge.store.KnowledgeStore;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.knowledge.mapper.ChunkMapper;
import com.jobai.knowledge.mapper.DocumentMapper;
import com.jobai.knowledge.parser.DocumentParser;
import com.jobai.knowledge.parser.MdPostProcessor;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentProcessingTest {
    DocumentMapper documents = mock(DocumentMapper.class);
    ChunkMapper chunks = mock(ChunkMapper.class);
    EmbeddingModel model = mock(EmbeddingModel.class);
    EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
    MdPostProcessor markdown = mock(MdPostProcessor.class);
    Document doc = new Document();
    DocumentProcessor processor;

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Chunk.class);
        doc.setId(7L); doc.setKbId(2L); doc.setFileName("a.md"); doc.setStatus("UPLOADING");
        when(documents.selectById(7L)).thenReturn(doc);
        when(documents.selectProcessable(7L)).thenReturn(doc);
        when(documents.updateById(any(Document.class))).thenReturn(1);
        when(documents.markCancelledProcessing(7L)).thenAnswer(i -> {
            if (!List.of("UPLOADING", "PARSING", "CHUNKING", "EMBEDDING").contains(doc.getStatus())) return 0;
            doc.setStatus("FAILED");
            return 1;
        });
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(new float[]{1,0}))));
        processor = new DocumentProcessor(documents, chunks, mock(DocumentParser.class), model, store,
                new JobAiProperties(), markdown, Runnable::run);
    }
    @Test void normalDocumentBecomesReady() {
        processor.processDocument(7L, "Java 文档内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("READY", doc.getStatus()); assertEquals(1, doc.getChunkCount());
        verify(store).addAll(anyList(), anyList(), anyList()); verify(chunks).insertBatch(anyList());
        verify(store, never()).removeAll(anyCollection());
    }
    @Test void mysqlFailureRemovesAttemptVectorsAndPartialChunks() {
        doThrow(new IllegalStateException("database failed")).when(chunks).insertBatch(anyList());
        processor.processDocument(7L, "Java 文档内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("FAILED", doc.getStatus());
        verify(store).removeAll(anyCollection()); verify(chunks).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
    @Test void elasticFailureRemovesAttemptDataAndCannotMarkReady() {
        doThrow(new IllegalStateException("bulk failed")).when(store).addAll(anyList(), anyList(), anyList());
        processor.processDocument(7L, "Java 文档内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("FAILED", doc.getStatus());
        verify(store).removeAll(anyCollection()); verify(chunks, never()).insertBatch(anyList());
    }
    @Test void cancelWhileEmbeddingCannotWriteOrReviveDeletedDocument() {
        when(model.embedAll(anyList())).thenAnswer(i -> {
            processor.cancel(7L);
            return Response.from(List.of(Embedding.from(new float[]{1,0})));
        });
        processor.processDocument(7L, "Java 文档内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        verify(store, never()).addAll(anyList(), anyList(), anyList());
        verify(chunks, never()).insertBatch(anyList()); assertEquals("FAILED", doc.getStatus());
    }
    @Test void expiredDeadlineCannotCommitEvenWhenEmbeddingIgnoresInterrupts() {
        when(model.embedAll(anyList())).thenAnswer(i -> {
            var tasksField = DocumentProcessor.class.getDeclaredField("tasks"); tasksField.setAccessible(true);
            var task = ((java.util.Map<?, ?>) tasksField.get(processor)).get(7L);
            var deadlineField = task.getClass().getDeclaredField("deadline"); deadlineField.setAccessible(true);
            deadlineField.setLong(task, System.nanoTime() - 1);
            return Response.from(List.of(Embedding.from(new float[]{1,0})));
        });
        processor.processDocument(7L, "Java 文档内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("FAILED", doc.getStatus());
        verify(store, never()).addAll(anyList(), anyList(), anyList());
        verify(chunks, never()).insertBatch(anyList());
    }
    @Test void cancelDuringElasticWriteRemovesLateVectors() {
        doAnswer(i -> { processor.cancel(7L); return null; }).when(store).addAll(anyList(), anyList(), anyList());
        processor.processDocument(7L, "Java 文档内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        verify(store).removeAll(anyCollection()); verify(chunks, never()).insertBatch(anyList());
        assertNotEquals("READY", doc.getStatus());
    }

    @Test void failedDeletionCanRetryBeforeTheCancelledWorkerReturns() {
        var kbMapper = mock(KnowledgeBaseMapper.class);
        var kb = new KnowledgeBase(); kb.setId(2L); kb.setUserId(42L); kb.setIsDelete(0);
        when(kbMapper.selectById(2L)).thenReturn(kb);
        var knowledgeStore = mock(KnowledgeStore.class);
        doThrow(new BusinessException(ErrorCode.RETRIEVAL_FAILED, "ES unavailable"))
                .doNothing().when(knowledgeStore).deleteByDocId(7L);
        var service = new DocumentService(documents, chunks, processor, knowledgeStore, kbMapper);
        when(documents.selectByKbAndFileHash(eq(2L), anyString())).thenReturn(doc);
        when(documents.restartDocument(doc)).thenAnswer(i -> {
            assertEquals("FAILED", doc.getStatus());
            doc.setStatus("UPLOADING");
            return 1;
        });
        var firstAttempt = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(model.embedAll(anyList())).thenAnswer(i -> {
            if (firstAttempt.getAndSet(false)) {
                assertThrows(BusinessException.class, () -> service.deleteDocument(7L, 42L));
                assertEquals("FAILED", doc.getStatus());
                service.createDocument(new MockMultipartFile("file", "a.md", "text/markdown",
                        "Java 文档内容".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 2L, 42L);
                assertEquals("READY", doc.getStatus());
            }
            return Response.from(List.of(Embedding.from(new float[]{1,0})));
        });
        processor.processDocument(7L, "Java 文档内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("READY", doc.getStatus());
        verify(documents, never()).deleteById(anyLong());
        verify(store, times(1)).addAll(anyList(), anyList(), anyList());
        verify(chunks, times(1)).insertBatch(anyList());
    }

    @Test void cancelledQueuedTaskDoesNotStartAndReadyDocumentsAreNotDowngraded() {
        processor.cancel(7L);
        processor.processDocument(7L, "Java 文档内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("FAILED", doc.getStatus());
        verifyNoInteractions(model, store);
        doc.setStatus("READY");
        processor.cancel(7L);
        assertEquals("READY", doc.getStatus());
    }
}
