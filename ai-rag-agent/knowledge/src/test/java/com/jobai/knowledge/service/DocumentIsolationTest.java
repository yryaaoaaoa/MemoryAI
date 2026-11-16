package com.jobai.knowledge.service;

import com.jobai.common.BusinessException;
import com.jobai.common.auth.AuthContext;
import com.jobai.knowledge.entity.Document;
import com.jobai.knowledge.entity.KnowledgeBase;
import com.jobai.knowledge.mapper.ChunkMapper;
import com.jobai.knowledge.mapper.DocumentMapper;
import com.jobai.knowledge.mapper.KnowledgeBaseMapper;
import com.jobai.knowledge.store.KnowledgeStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentIsolationTest {
    private DocumentMapper documents;
    private ChunkMapper chunks;
    private KnowledgeBaseMapper knowledgeBases;
    private KnowledgeStore store;
    private DocumentProcessor processor;
    private DocumentService service;
    private final MockMultipartFile file = new MockMultipartFile("file", "notes.md", "text/markdown", "hello".getBytes());

    @BeforeEach
    void setUp() {
        documents = mock(DocumentMapper.class);
        chunks = mock(ChunkMapper.class);
        knowledgeBases = mock(KnowledgeBaseMapper.class);
        store = mock(KnowledgeStore.class);
        processor = mock(DocumentProcessor.class);
        service = new DocumentService(documents, chunks, processor, store, knowledgeBases);
        when(documents.insert(any(Document.class))).thenAnswer(invocation -> {
            invocation.<Document>getArgument(0).setId(7L);
            return 1;
        });
    }

    @AfterEach
    void clearIdentity() { AuthContext.clear(); }

    @Test
    void adminUploadToPublicKbStoresPublicVisibility() throws Exception {
        kb(10L, 0L);
        AuthContext.set(5L, "admin");
        Document result = service.createDocument(file, 10L, 5L);
        assertEquals(0L, result.getUserId());
        assertEquals(10L, result.getKbId());
        verify(documents).selectByKbAndFileHash(eq(10L), anyString());
        verify(processor).processDocument(7L, file.getBytes());
    }

    @Test
    void ordinaryUserCannotUploadToPublicKb() {
        kb(10L, 0L);
        AuthContext.set(6L, "user");
        assertThrows(BusinessException.class, () -> service.createDocument(file, 10L, 6L));
        verifyNoInteractions(documents, processor, store);
    }

    @Test
    void publicReadUsesKbOwnerButDeleteStillRequiresAdmin() {
        kb(10L, 0L);
        Document oldDocument = document(7L, 10L, 5L, "READY", 0);
        when(documents.selectById(7L)).thenReturn(oldDocument);
        AuthContext.set(6L, "user");
        assertSame(oldDocument, service.getDocument(7L, 6L));
        assertThrows(BusinessException.class, () -> service.deleteDocument(7L, 6L));
        verifyNoInteractions(store, chunks);
        verify(documents, never()).deleteById(anyLong());
    }

    @Test
    void privateKbRejectsOtherUsersEvenIfDocumentMetadataSaysPublic() {
        kb(10L, 5L);
        when(documents.selectById(7L)).thenReturn(document(7L, 10L, 0L, "READY", 0));
        assertThrows(BusinessException.class, () -> service.getDocument(7L, 6L));
    }

    @Test
    void deletedDocumentIsExplicitlyRestoredBeforeProcessing() {
        kb(10L, 5L);
        Document deleted = document(7L, 10L, 5L, "READY", 1);
        when(documents.selectByKbAndFileHash(eq(10L), anyString())).thenReturn(deleted);
        when(documents.restartDocument(deleted)).thenReturn(1);
        Document result = service.createDocument(file, 10L, 5L);
        assertEquals(0, result.getIsDelete());
        assertEquals("UPLOADING", result.getStatus());
        var order = inOrder(documents, store, processor);
        order.verify(documents).restartDocument(deleted);
        order.verify(store).deleteByDocId(7L);
        order.verify(processor).processDocument(eq(7L), any(byte[].class));
        verify(documents, never()).updateById(any(Document.class));
    }

    @Test
    void lostRetryClaimDoesNotDeleteVectorsOrLaunchSecondJob() {
        kb(10L, 5L);
        Document failed = document(7L, 10L, 5L, "FAILED", 0);
        when(documents.selectByKbAndFileHash(eq(10L), anyString())).thenReturn(failed);
        when(documents.restartDocument(failed)).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.createDocument(file, 10L, 5L));
        verifyNoInteractions(store, chunks, processor);
    }

    @Test
    void sameBytesInAnotherKbGetAnIndependentDocument() {
        kb(10L, 5L);
        kb(11L, 6L);
        Document first = service.createDocument(file, 10L, 5L);
        Document second = service.createDocument(file, 11L, 6L);
        assertEquals(first.getFileHash(), second.getFileHash());
        assertNotEquals(first.getKbId(), second.getKbId());
        assertNotEquals(first.getUserId(), second.getUserId());
        verify(documents).selectByKbAndFileHash(10L, first.getFileHash());
        verify(documents).selectByKbAndFileHash(11L, second.getFileHash());
    }

    private void kb(long id, long owner) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setUserId(owner);
        kb.setIsDelete(0);
        when(knowledgeBases.selectById(id)).thenReturn(kb);
    }

    private Document document(long id, long kbId, long owner, String status, int deleted) {
        Document doc = new Document();
        doc.setId(id);
        doc.setKbId(kbId);
        doc.setUserId(owner);
        doc.setStatus(status);
        doc.setIsDelete(deleted);
        doc.setFileHash("hash");
        return doc;
    }
}
