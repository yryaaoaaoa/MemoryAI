package com.jobai.agent.tool;

import com.jobai.common.JobAiProperties.RetrievalProperties;
import com.jobai.common.auth.AuthContext;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.knowledge.retrieval.RerankService;
import com.jobai.knowledge.retrieval.VectorRetrievalService;
import com.jobai.knowledge.retrieval.VectorRetrievalService.RetrievalParams;
import com.jobai.knowledge.retrieval.VectorRetrievalService.RetrievedChunk;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Slf4j
@Component
@com.jobai.common.ConditionalOnRagEnabled
public class KnowledgeRetrievalTool extends AgentTool<KnowledgeRetrievalTool.Params, String> {

    private static final int MIN_CANDIDATES = 10;
    private static final int MAX_CANDIDATES = 50;

    private final VectorRetrievalService retrievalService;
    private final LlmService llmService;
    private final RerankService rerankService;

    public KnowledgeRetrievalTool(VectorRetrievalService retrievalService,
                                   RetrievalProperties retrievalConfig,
                                   LlmService llmService,
                                   RerankService rerankService) {
        super("knowledgeRetrieval", "搜索知识库获取相关内容。当用户询问具体技术问题时使用");
        this.retrievalService = retrievalService;
        this.llmService = llmService;
        this.rerankService = rerankService;
    }

    @Tool("搜索知识库获取相关内容，返回文档片段列表")
    public String search(
            @P("搜索查询，如 'Java 线程池参数'") String query,
            @P("返回结果数量") Integer topK) {
        return executeWithRetry(new Params(query, topK));
    }

    /** Chat callers supply recent plain-text history for resolving follow-up questions. */
    public String search(String query, Integer topK, String history) {
        return executeWithRetry(new Params(query, topK, history));
    }

    @Override
    protected String execute(Params p) {
        long t0 = System.currentTimeMillis();
        boolean rewrittenFlag = false;

        // 1. Query rewrite
        String rewritten = p.query;
        if (llmService.isAvailable()) {
            try {
                String r = llmService.queryRewrite(p.query, p.history);
                if (r != null && !r.isBlank()) {
                    rewritten = r.strip();
                    rewrittenFlag = !rewritten.equals(p.query);
                }
            } catch (Exception e) {
                log.warn("[knowledgeRetrieval] rewrite failed, using original: {}", e.getMessage());
            }
        }
        long t1 = System.currentTimeMillis();

        // 2. Dynamic retrieval params
        RetrievalParams rp = retrievalService.computeParams(rewritten);
        int finalK = Math.max(1, Math.min(MAX_CANDIDATES,
                p.topK != null && p.topK > 0 ? p.topK : rp.topK()));
        int candidateK = Math.min(MAX_CANDIDATES,
                Math.max(MIN_CANDIDATES, Math.max(rp.topK(), finalK * 2)));
        double minScore = rp.minScore();

        // 3. Dual-path retrieval (hybrid search + relevance check + RRF fusion)
        List<String> candidates = rewritten.equals(p.query)
                ? List.of(p.query)
                : List.of(rewritten, p.query);
        Long currentUserId = AuthContext.get();
        var results = retrievalService.search(candidates, null, candidateK, minScore, currentUserId);
        var chunks = results;
        int retrievalCount = chunks.size();
        long t2 = System.currentTimeMillis();

        // 4. Rerank
        boolean reranked = false;
        if (rerankService.isAvailable() && chunks.size() > 1) {
            int rerankSize = Math.min(chunks.size(), MAX_CANDIDATES);
            List<String> texts = chunks.subList(0, rerankSize).stream()
                    .map(RetrievedChunk::content)
                    .toList();
            try {
                List<Integer> indices = rerankService.rerank(rewritten, texts, rerankSize);
                if (indices != null && !indices.isEmpty()) {
                    // Remote ranking responses may be partial or contain malformed indices.
                    var order = new LinkedHashSet<Integer>();
                    for (Integer index : indices) {
                        if (index != null && index >= 0 && index < rerankSize) order.add(index);
                    }
                    if (!order.isEmpty()) {
                        for (int i = 0; i < chunks.size(); i++) order.add(i);
                        List<RetrievedChunk> rerankedList = new ArrayList<>();
                        for (Integer index : order) rerankedList.add(chunks.get(index));
                        chunks = rerankedList;
                        reranked = true;
                    }
                }
            } catch (Exception e) {
                log.warn("[knowledgeRetrieval] rerank failed, keeping RRF order: {}", e.getMessage());
            }
        }
        // Final context budget applies equally to successful reranking and fallback paths.
        chunks = chunks.stream().limit(finalK).toList();
        int finalCount = chunks.size();
        long t3 = System.currentTimeMillis();

        // 5. Metrics logging
        if (chunks.isEmpty()) {
            log.info("[RAG] q=\"{}\" rewritten=\"{}\" rewrite={} hits={} rerank={} t_rewrite={}ms t_search={}ms t_rerank={}ms t_total={}ms result=empty",
                    p.query, rewritten, rewrittenFlag, retrievalCount, reranked,
                    t1 - t0, t2 - t1, t3 - t2, t3 - t0);
            return "知识库中未找到与「" + p.query + "」相关的内容。";
        }

        int totalChars = chunks.stream().mapToInt(c -> c.content().length()).sum();
        double avgScore = chunks.stream().mapToDouble(RetrievedChunk::score).average().orElse(0);
        log.info("[RAG] q=\"{}\" rewritten=\"{}\" rewrite={} hits={} rerank={} final={} chars={} avg_score={} t_rewrite={}ms t_search={}ms t_rerank={}ms t_total={}ms",
                p.query, rewritten, rewrittenFlag, retrievalCount, reranked, finalCount, totalChars,
                String.format("%.3f", avgScore),
                t1 - t0, t2 - t1, t3 - t2, t3 - t0);

        // 6. Format chunks
        return formatChunks(chunks);
    }

    @Override
    protected String fallback(KnowledgeRetrievalTool.Params p) {
        return "知识检索暂时不可用，请稍后再试。";
    }

    public record Params(String query, Integer topK, String history) {
        public Params(String query, Integer topK) { this(query, topK, null); }
    }

    private static String formatChunks(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk r = chunks.get(i);
            String source = r.headingPath() != null && !r.headingPath().isBlank()
                    ? r.headingPath() : "未提供标题";
            sb.append("[%d] 来源：%s".formatted(i + 1, source));
            if (r.kbId() != null) sb.append("；知识库 ID：").append(r.kbId());
            if (r.docId() != null) sb.append("；文档 ID：").append(r.docId());
            if (r.chunkId() != null && !r.chunkId().isBlank()) sb.append("；片段 ID：").append(r.chunkId());
            sb.append('\n').append(r.content());
            if (i < chunks.size() - 1) sb.append("\n\n");
        }
        return sb.toString();
    }
}
