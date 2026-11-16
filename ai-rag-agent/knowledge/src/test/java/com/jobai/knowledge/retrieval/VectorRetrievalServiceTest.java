package com.jobai.knowledge.retrieval;

import com.jobai.common.JobAiProperties.RetrievalProperties;
import com.jobai.knowledge.config.RetrievalConfigService;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.knowledge.store.ElasticsearchEmbeddingStoreAdapter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VectorRetrievalServiceTest {
    private final ElasticsearchEmbeddingStoreAdapter store = mock(ElasticsearchEmbeddingStoreAdapter.class);
    private final EmbeddingModel embedding = mock(EmbeddingModel.class);
    private final com.jobai.knowledge.mapper.ChunkMapper chunks = mock(com.jobai.knowledge.mapper.ChunkMapper.class);
    private final LlmService llm = mock(LlmService.class);
    private final VectorRetrievalService service = new VectorRetrievalService(store, embedding,
            new RetrievalProperties(), mock(RetrievalConfigService.class), llm, chunks);
    private final Embedding vector = Embedding.from(new float[]{1, 0});

    @BeforeEach
    void setUp() {
        when(chunks.selectReadyVectorIds(anyCollection(), any())).thenReturn(List.of("a", "b"));
        when(embedding.embedAll(anyList())).thenReturn(Response.from(List.of(vector)));
        when(store.hybridSearch(any(), anyString(), anyInt(), anyDouble(), any())).thenReturn(
                new ElasticsearchEmbeddingStoreAdapter.HybridResult(
                        List.of(match("a", "Redis 持久化"), match("b", "蛋糕做法")), List.of()));
    }

    @Test
    void forwardsCosineThresholdAndKeepsSingleBranchWhenLlmUnavailable() {
        var result = service.search("Redis", List.of(12L), 10, 0.65, 9L);
        assertEquals(2, result.size());
        verify(store).hybridSearch(eq(vector), eq("Redis"), eq(10), eq(0.65), notNull());
        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test
    void batchJudgementsFilterEachChunkAndPreserveOrderAndSource() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"judgements\":[{\"id\":1,\"relevant\":true},{\"id\":0,\"relevant\":false}]}");
        var result = service.search("蛋糕", List.of(), 10, 0.3, 9L);
        assertEquals(1, result.size());
        assertEquals("b", result.get(0).chunkId());
        assertEquals("章节", result.get(0).headingPath());
        assertEquals(12L, result.get(0).kbId());
        assertEquals(34L, result.get(0).docId());
        verify(llm, times(1)).chat(anyString(), anyString());
    }

    @Test
    void validAllNegativeJudgementsReturnNoEvidence() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"judgements\":[{\"id\":0,\"relevant\":false},{\"id\":1,\"relevant\":false}]}");
        assertTrue(service.search("火星", List.of(), 10, 0.3, 9L).isEmpty());
    }

    @Test
    void invalidOrIncompleteJudgementsFallBackWithoutDiscardingCandidates() {
        when(llm.isAvailable()).thenReturn(true);
        for (String malformed : List.of("是", "{\"judgements\":[]}",
                "{\"judgements\":[{\"id\":0,\"relevant\":false},{\"id\":0,\"relevant\":false}]}",
                "{\"judgements\":[{\"id\":0,\"relevant\":\"false\"},{\"id\":1,\"relevant\":false}]}",
                "{\"judgements\":[{\"id\":0,\"relevant\":false},{\"id\":2,\"relevant\":false}]}")) {
            when(llm.chat(anyString(), anyString())).thenReturn(malformed);
            assertEquals(2, service.search("Redis", List.of(), 10, 0.3, 9L).size(), malformed);
        }
    }

    @Test
    void modelFailureFallsBackWithoutDiscardingCandidates() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chat(anyString(), anyString())).thenThrow(new IllegalStateException("timeout"));
        assertEquals(2, service.search("Redis", List.of(), 10, 0.3, 9L).size());
    }

    @Test
    void multipleQueriesRunOneRelevanceBatchAfterFusionUsingResolvedFirstQuery() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"judgements\":[{\"id\":0,\"relevant\":true},{\"id\":1,\"relevant\":false}]}");
        var result = service.search(List.of("Redis 持久化的缺点", "那它有什么缺点"), List.of(), 10, 0.3, 9L);
        assertEquals(1, result.size());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm, times(1)).chat(anyString(), prompt.capture());
        assertTrue(prompt.getValue().contains("Redis 持久化的缺点"));
        assertFalse(prompt.getValue().contains("那它有什么缺点"));
        verify(store, times(2)).hybridSearch(any(), anyString(), eq(10), eq(0.3), any());
    }

    @Test
    void rrfMergesSameChunkButPreservesIdenticalTextFromDifferentSources() {
        var a = new VectorRetrievalService.RetrievedChunk("同文", "A", 0.9, 1L, 10L, "a");
        var b = new VectorRetrievalService.RetrievedChunk("同文", "B", 0.8, 2L, 20L, "b");
        var result = VectorRetrievalService.fuseByRrf(List.of(List.of(a, b), List.of(b)), 10, 60);
        assertEquals(2, result.size());
        assertEquals("b", result.get(0).chunkId());
        assertEquals(1.0 / 62 + 1.0 / 61, result.get(0).score(), 1e-10);
        assertEquals(20L, result.get(0).docId());
    }

    @Test
    void legacyFiveFieldChunksStillDeduplicateWithinSourceOnly() {
        var a = new VectorRetrievalService.RetrievedChunk("同文", "A", 0.9, 1L, 10L);
        var b = new VectorRetrievalService.RetrievedChunk("同文", "A", 0.8, 2L, 20L);
        var result = VectorRetrievalService.fuseByRrf(List.of(List.of(a, b), List.of(a)), 10, 60);
        assertEquals(2, result.size());
        assertEquals(10L, result.get(0).docId());
    }

    @Test
    void filtersStaleElasticHitsThroughOneDatabaseBatchBeforeModelJudgement() {
        when(chunks.selectReadyVectorIds(anyCollection(), eq(9L))).thenReturn(List.of("a"));
        var result = service.search("Redis", List.of(12L), 10, 0.3, 9L);
        assertEquals(List.of("a"), result.stream().map(VectorRetrievalService.RetrievedChunk::chunkId).toList());
        verify(chunks, times(1)).selectReadyVectorIds(eq(java.util.Set.of("a", "b")), eq(9L));
    }

    @Test
    void noCommittedChunksReturnsNoEvidenceEvenWhenElasticContainsHits() {
        when(chunks.selectReadyVectorIds(anyCollection(), any())).thenReturn(List.of());
        assertTrue(service.search("Redis", List.of(), 10, 0.3, 9L).isEmpty());
        verifyNoInteractions(llm);
    }

    private EmbeddingMatch<TextSegment> match(String id, String text) {
        return new EmbeddingMatch<>(0.8, id, vector, TextSegment.from(text,
                Metadata.from(Map.of("heading_path", "章节", "kb_id", "12", "doc_id", 34L))));
    }
}
