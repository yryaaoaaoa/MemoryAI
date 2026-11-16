package com.jobai.agent.tool;

import com.jobai.common.JobAiProperties.RetrievalProperties;
import com.jobai.common.auth.AuthContext;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.knowledge.retrieval.RerankService;
import com.jobai.knowledge.retrieval.VectorRetrievalService;
import com.jobai.knowledge.retrieval.VectorRetrievalService.RetrievalParams;
import com.jobai.knowledge.retrieval.VectorRetrievalService.RetrievedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalToolTest {
    private final VectorRetrievalService retrieval = mock(VectorRetrievalService.class);
    private final LlmService llm = mock(LlmService.class);
    private final RerankService reranker = mock(RerankService.class);
    private final KnowledgeRetrievalTool tool = new KnowledgeRetrievalTool(
            retrieval, new RetrievalProperties(), llm, reranker);

    @BeforeEach
    void setup() {
        AuthContext.set(42L, "user");
        when(retrieval.computeParams(anyString())).thenReturn(new RetrievalParams(8, 0.28));
        when(retrieval.search(anyList(), isNull(), anyInt(), anyDouble(), eq(42L)))
                .thenReturn(chunks(12));
    }

    @AfterEach void clearAuth() { AuthContext.clear(); }

    @Test
    void rerankCanPromoteCandidateBeyondOriginalTopFiveAndUsesResolvedQuestion() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.queryRewrite("那它有什么缺点", "用户：什么是 Redis"))
                .thenReturn("Redis 的缺点");
        when(reranker.isAvailable()).thenReturn(true);
        when(reranker.rerank(eq("Redis 的缺点"), anyList(), eq(12)))
                .thenReturn(List.of(11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0));

        String result = tool.search("那它有什么缺点", 5, "用户：什么是 Redis");

        verify(retrieval).search(List.of("Redis 的缺点", "那它有什么缺点"), null, 10, 0.28, 42L);
        verify(reranker).rerank("Redis 的缺点", chunks(12).stream().map(RetrievedChunk::content).toList(), 12);
        assertTrue(result.startsWith("[1] 来源：章节 11"));
        assertTrue(result.contains("fragment-11-end"));
        assertFalse(result.contains("fragment-0-end"));
        assertEquals(5, sourceCount(result));
    }

    @Test
    void rerankFailureAndUnavailableRerankerBothRespectRequestedFinalLimit() {
        when(reranker.isAvailable()).thenReturn(true);
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenThrow(new RuntimeException("timeout"));
        String result = tool.search("Redis", 5);
        assertEquals(5, sourceCount(result));
        assertTrue(result.startsWith("[1] 来源：章节 0"));
        when(reranker.isAvailable()).thenReturn(false);
        assertEquals(2, sourceCount(tool.search("Redis", 2)));
    }

    @Test
    void malformedRankingIndicesAreIgnoredAndMissingPositionsUseOriginalOrder() {
        when(reranker.isAvailable()).thenReturn(true);
        when(reranker.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(Arrays.asList(10, 10, -1, 12, null, 9));
        String result = tool.search("Redis", 5);
        assertEquals(5, sourceCount(result));
        assertTrue(result.startsWith("[1] 来源：章节 10"));
        assertTrue(result.contains("[2] 来源：章节 9"));
        assertTrue(result.contains("[3] 来源：章节 0"));
        assertEquals(1, result.split("fragment-10-end", -1).length - 1);
    }

    @Test
    void sourceContainsStableDocumentMetadataAndDoesNotPresentRankingAsConfidence() {
        String result = tool.search("Redis", 1);
        assertTrue(result.contains("[1] 来源：章节 0；知识库 ID：3；文档 ID：100"));
        assertTrue(result.contains("片段 ID：chunk-0"));
        assertFalse(result.contains("相关度"));
        assertFalse(result.contains("可信度"));
        assertFalse(result.contains("0.03"));
    }

    @Test
    void legacyEntryPointUsesNoHistoryAndDynamicCandidateLimitHasUpperBound() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.queryRewrite("Redis", null)).thenReturn("Redis");
        when(retrieval.computeParams("Redis")).thenReturn(new RetrievalParams(200, 0.2));
        tool.search("Redis", 5);
        verify(llm).queryRewrite("Redis", null);
        verify(retrieval).search(List.of("Redis"), null, 50, 0.2, 42L);
    }

    private static List<RetrievedChunk> chunks(int count) {
        return IntStream.range(0, count).mapToObj(i ->
                new RetrievedChunk("fragment-" + i + "-end", "章节 " + i, 0.03, 3L, 100L + i, "chunk-" + i)).toList();
    }

    private static long sourceCount(String result) {
        return result.lines().filter(line -> line.matches("\\[\\d+] 来源：.*")).count();
    }
}
