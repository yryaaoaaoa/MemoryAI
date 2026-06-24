package com.jobai.knowledge.retrieval;

import com.jobai.common.JobAiProperties.RetrievalProperties;
import com.jobai.common.JobAiProperties.RetrievalProperties.RetrievalTier;
import com.jobai.knowledge.config.RetrievalConfigService;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.knowledge.store.ElasticsearchEmbeddingStoreAdapter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.logical.And;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Stream;


@Slf4j
@Service
@RequiredArgsConstructor
public class VectorRetrievalService {

    private final ElasticsearchEmbeddingStoreAdapter embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final RetrievalProperties retrievalConfig;
    private final RetrievalConfigService dynamicConfig;
    private final LlmService llmService;

    /**
     * Dynamic retrieval parameters based on query length.
     */
    public RetrievalParams computeParams(String query) {
        RetrievalProperties cfg = resolveConfig();
        int len = query.replaceAll("\\s+", "").length();
        RetrievalTier tier;
        if (len <= cfg.getShortQueryMaxLen()) {
            tier = cfg.getShortQuery();
        } else if (len <= cfg.getMediumQueryMaxLen()) {
            tier = cfg.getMediumQuery();
        } else {
            tier = cfg.getLongQuery();
        }
        return new RetrievalParams(tier.getTopK(), tier.getMinScore());
    }

    private RetrievalProperties resolveConfig() {
        RetrievalProperties active = dynamicConfig.getActiveConfig();
        return active != null ? active : retrievalConfig;
    }

    /**
     * Dual-path retrieval with user visibility filter.
     * When userId is provided, only returns chunks from user's own documents
     * or system-public documents (user_id = 0).
     */
    public List<RetrievedChunk> search(List<String> candidates, Collection<Long> kbIds,
                                       int topK, double minScore, Long userId) {
        if (candidates.isEmpty()) return List.of();
        if (candidates.size() == 1) {
            return search(candidates.get(0), kbIds, topK, minScore, userId);
        }

        int rankConstant = resolveConfig().getRankConstant();
        List<List<RetrievedChunk>> allResults = candidates.parallelStream()
                .map(q -> search(q, kbIds, topK, minScore, userId))
                .toList();

        return fuseByRrf(allResults, topK, rankConstant);
    }

    /** Backward-compatible: search without user filter */
    public List<RetrievedChunk> search(List<String> candidates, Collection<Long> kbIds,
                                       int topK, double minScore) {
        return search(candidates, kbIds, topK, minScore, null);
    }

    /**
     * RRF fusion across multiple ranked result lists.
     * Deduplicates by content; final sort by RRF score descending.
     */
    static List<RetrievedChunk> fuseByRrf(List<List<RetrievedChunk>> rankedLists,
                                           int topK, int rankConstant) {
        // content -> (cumulative RRF score, first-seen chunk)
        Map<String, double[]> fused = new LinkedHashMap<>();

        for (List<RetrievedChunk> list : rankedLists) {
            if (list == null || list.isEmpty()) continue;
            for (int i = 0; i < list.size(); i++) {
                RetrievedChunk chunk = list.get(i);
                double rrfScore = 1.0 / ((i + 1) + rankConstant);
                fused.compute(chunk.content(), (k, v) -> {
                    if (v == null) return new double[]{rrfScore};
                    v[0] += rrfScore;
                    return v;
                });
            }
        }

        // Sort by RRF score desc, trim to topK
        return fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(topK)
                .map(e -> {
                    // Find the original chunk that matches this content to preserve metadata
                    for (List<RetrievedChunk> list : rankedLists) {
                        for (RetrievedChunk c : list) {
                            if (c.content().equals(e.getKey())) {
                                return new RetrievedChunk(c.content(), c.headingPath(),
                                        e.getValue()[0], c.kbId(), c.docId());
                            }
                        }
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Single-query hybrid search with optional user visibility filter.
     * When userId is provided, adds user_id IN (0, userId) constraint.
     */
    public List<RetrievedChunk> search(String query, Collection<Long> kbIds,
                                       int topK, double minScore, Long userId) {
        // 1. embed query
        Embedding queryEmbedding = embeddingModel.embedAll(List.of(TextSegment.from(query)))
                .content().get(0);

        // 2. build filter: kb filter + user visibility filter
        Filter filter = null;
        if (kbIds != null && !kbIds.isEmpty()) {
            filter = new IsIn("kb_id", kbIds.stream().map(String::valueOf).toList());
        }
        if (userId != null) {
            Filter userFilter = new IsIn("user_id", List.of("0", String.valueOf(userId)));
            filter = (filter != null) ? new And(filter, userFilter) : userFilter;
        }

        // 3. dual sub-queries: kNN + BM25 (minScore=0: no ES-side filtering)
        var hybridResult = embeddingStore.hybridSearch(
                queryEmbedding, query, topK, 0, filter
        );

        if (hybridResult.knnMatches().isEmpty() && hybridResult.bm25Matches().isEmpty()) {
            return List.of();
        }

        // 4. Relevance check: log only — hybrid search + rerank + final LLM prompt handle relevance
        if (llmService.isAvailable()) {
            checkRelevance(query, hybridResult);
        } else if (hybridResult.knnMatches().isEmpty() || hybridResult.bm25Matches().isEmpty()) {
            return List.of();
        }

        // 5. convert both result sets to RetrievedChunk lists
        int rankConstant = resolveConfig().getRankConstant();
        List<RetrievedChunk> knnChunks = hybridResult.knnMatches().stream()
                .map(RetrievedChunk::from).toList();
        List<RetrievedChunk> bm25Chunks = hybridResult.bm25Matches().stream()
                .map(RetrievedChunk::from).toList();

        // 6. fuse via application-side RRF
        return fuseByRrf(List.of(knnChunks, bm25Chunks), topK, rankConstant);
    }

    /**
     * LLM relevance check: uses the top result from kNN or BM25 (whichever has better content).
     * Returns false if LLM is unavailable, content is empty, or LLM says not relevant.
     */
    private boolean checkRelevance(String query,
                                    ElasticsearchEmbeddingStoreAdapter.HybridResult hybridResult) {
        String content = Stream.concat(
                        hybridResult.knnMatches().stream(),
                        hybridResult.bm25Matches().stream())
                .map(m -> m.embedded() != null ? m.embedded().text() : "")
                .filter(c -> !c.isBlank())
                .findFirst().orElse(null);
        if (content == null) return false;

        try {
            String prompt = String.format(
                    "判断以下内容是否与「%s」相关。只需回复一个字：是或否\n\n内容：\n%s",
                    query, content.length() > 500 ? content.substring(0, 500) : content);
            String result = llmService.chat("你是一个相关性判断助手，只回答是或否。", prompt);
            return result.contains("是");
        } catch (Exception e) {
            log.warn("LLM relevance check failed for query '{}': {}", query, e.getMessage());
            return false;
        }
    }

    /** Backward-compatible: search without user filter */
    public List<RetrievedChunk> search(String query, Collection<Long> kbIds,
                                       int topK, double minScore) {
        return search(query, kbIds, topK, minScore, null);
    }

    public record RetrievalParams(int topK, double minScore) {}

    public record RetrievedChunk(String content, String headingPath, double score,
                                  Long kbId, Long docId) {
        static RetrievedChunk from(EmbeddingMatch<TextSegment> match) {
            String content = match.embedded() != null ? match.embedded().text() : "";
            return new RetrievedChunk(content, "", match.score(), null, null);
        }
    }
}
