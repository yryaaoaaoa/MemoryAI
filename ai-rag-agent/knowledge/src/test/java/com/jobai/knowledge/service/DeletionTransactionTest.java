package com.jobai.knowledge.service;

import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.JobAiProperties;
import com.jobai.infrastructure.mapper.UserMapper;
import com.jobai.knowledge.entity.Document;
import com.jobai.knowledge.entity.KnowledgeBase;
import com.jobai.knowledge.mapper.ChunkMapper;
import com.jobai.knowledge.mapper.DocumentMapper;
import com.jobai.knowledge.mapper.KnowledgeBaseMapper;
import com.jobai.knowledge.parser.DocumentParser;
import com.jobai.knowledge.parser.MdPostProcessor;
import com.jobai.knowledge.store.KnowledgeStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeletionTransactionTest {
    @Test
    void failedDocumentDeletionRollsBackOuterTransactionButCommitsCancellation() {
        var tx = mock(PlatformTransactionManager.class);
        var outer = new SimpleTransactionStatus();
        var cancellation = new SimpleTransactionStatus();
        when(tx.getTransaction(any())).thenReturn(outer, cancellation);
        var documents = mock(DocumentMapper.class);
        var chunks = mock(ChunkMapper.class);
        var knowledgeBases = mock(KnowledgeBaseMapper.class);
        var store = mock(KnowledgeStore.class);
        var doc = new Document(); doc.setId(7L); doc.setKbId(2L); doc.setStatus("UPLOADING"); doc.setIsDelete(0);
        var kb = new KnowledgeBase(); kb.setId(2L); kb.setUserId(42L); kb.setIsDelete(0);
        when(documents.selectById(7L)).thenReturn(doc);
        when(knowledgeBases.selectById(2L)).thenReturn(kb);
        when(documents.markCancelledProcessing(7L)).thenAnswer(i -> { doc.setStatus("FAILED"); return 1; });
        var processor = transactional(new DocumentProcessor(documents, chunks, mock(DocumentParser.class),
                mock(EmbeddingModel.class), mock(EmbeddingStore.class), new JobAiProperties(),
                mock(MdPostProcessor.class), Runnable::run), tx);
        var service = transactional(new DocumentService(documents, chunks, processor, store, knowledgeBases), tx);
        doThrow(new BusinessException(ErrorCode.RETRIEVAL_FAILED, "ES unavailable")).when(store).deleteByDocId(7L);

        assertThrows(BusinessException.class, () -> service.deleteDocument(7L, 42L));
        var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(tx, times(2)).getTransaction(definitions.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, definitions.getAllValues().get(0).getPropagationBehavior());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definitions.getAllValues().get(1).getPropagationBehavior());
        var order = inOrder(tx, store);
        order.verify(tx).commit(cancellation);
        order.verify(store).deleteByDocId(7L);
        order.verify(tx).rollback(outer);
        assertEquals("FAILED", doc.getStatus());
        verify(documents, never()).deleteById(anyLong());
    }

    @Test
    void knowledgeBaseDeletionFailureRollsBackAndSuccessfulRetryCommits() {
        var tx = mock(PlatformTransactionManager.class);
        var failed = new SimpleTransactionStatus();
        var retried = new SimpleTransactionStatus();
        when(tx.getTransaction(any())).thenReturn(failed, retried);
        var knowledgeBases = mock(KnowledgeBaseMapper.class);
        var kb = new KnowledgeBase(); kb.setId(2L); kb.setUserId(42L); kb.setIsDelete(0);
        when(knowledgeBases.selectById(2L)).thenReturn(kb);
        when(knowledgeBases.deleteById(2L)).thenAnswer(i -> { kb.setIsDelete(1); return 1; });
        // Model the row being restored only when Spring actually asks for a rollback.
        doAnswer(i -> { kb.setIsDelete(0); return null; }).when(tx).rollback(failed);
        var store = mock(KnowledgeStore.class);
        doThrow(new BusinessException(ErrorCode.RETRIEVAL_FAILED, "ES unavailable"))
                .doNothing().when(store).deleteByKbId(2L);
        var service = transactional(new KnowledgeBaseService(knowledgeBases, mock(DocumentMapper.class),
                mock(ChunkMapper.class), store, mock(UserMapper.class)), tx);

        assertThrows(BusinessException.class, () -> service.delete(2L, 42L));
        assertSame(kb, service.getById(2L, 42L));
        verify(tx).rollback(failed);
        verify(tx, never()).commit(failed);
        service.delete(2L, 42L);
        verify(tx).commit(retried);
        assertEquals(1, kb.getIsDelete());
        verify(store, times(2)).deleteByKbId(2L);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, PlatformTransactionManager tx) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
