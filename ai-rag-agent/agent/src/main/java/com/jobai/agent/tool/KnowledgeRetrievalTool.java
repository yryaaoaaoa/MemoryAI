package com.jobai.agent.tool;

import com.jobai.common.JobAiProperties.RetrievalProperties;
import com.jobai.common.JobAiProperties.RetrievalProperties.RetrievalTier;
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

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KnowledgeRetrievalTool extends AgentTool<KnowledgeRetrievalTool.Params, String> {

    private static final int RERANK_TOP_N = 10;
    private static final int RERANK_FINAL_K = 5;

    private final VectorRetrievalService retrievalService;
    private final RetrievalTier defaults;
    private final LlmService llmService;
    private final RerankService rerankService;

    public KnowledgeRetrievalTool(VectorRetrievalService retrievalService,
                                   RetrievalProperties retrievalConfig,
                                   LlmService llmService,
                                   RerankService rerankService) {
        super("knowledgeRetrieval", "搜索知识库获取相关内容。当用户询问具体技术问题时使用");
        this.retrievalService = retrievalService;
        this.defaults = retrievalConfig.getDefaults();
        this.llmService = llmService;
        this.rerankService = rerankService;
    }

    @Tool("搜索知识库获取相关内容，返回文档片段列表")
    public String search(
            @P("搜索查询，如 'Java 线程池参数'") String query,
            @P("返回结果数量") Integer topK) {
        return executeWithRetry(new Params(query, topK));
    }

    @Override
    protected String execute(Params p) {
        long t0 = System.currentTimeMillis();
        boolean rewrittenFlag = false;

        // 1. Query rewrite
        String rewritten = p.query;
        if (llmService.isAvailable()) {
            try {
                String r = llmService.queryRewrite(p.query);
                if (r != null && !r.isBlank()) { rewritten = r; rewrittenFlag = true; }
            } catch (Exception e) {
                log.warn("[knowledgeRetrieval] rewrite failed, using original: {}", e.getMessage());
            }
        }
        long t1 = System.currentTimeMillis();

        // 2. Dynamic retrieval params
        RetrievalParams rp = retrievalService.computeParams(rewritten);
        int k = p.topK != null && p.topK > 0 ? p.topK : rp.topK();
        double minScore = rp.minScore();

        // 3. Dual-path retrieval (hybrid search + relevance check + RRF fusion)
        List<String> candidates = rewritten.equals(p.query)
                ? List.of(p.query)
                : List.of(rewritten, p.query);
        Long currentUserId = AuthContext.get();
        var results = retrievalService.search(candidates, null, k, minScore, currentUserId);
        var chunks = results;
        int retrievalCount = chunks.size();
        long t2 = System.currentTimeMillis();

        // 4. Rerank
        boolean reranked = false;
        if (rerankService.isAvailable() && chunks.size() > 1) {
            int rerankSize = Math.min(chunks.size(), RERANK_TOP_N);
            List<String> texts = chunks.subList(0, rerankSize).stream()
                    .map(RetrievedChunk::content)
                    .toList();
            try {
                List<Integer> indices = rerankService.rerank(p.query, texts, rerankSize);
                if (indices != null && !indices.isEmpty()) {
                    List<RetrievedChunk> rerankedList = indices.stream()
                            .map(chunks::get)
                            .limit(RERANK_FINAL_K)
                            .toList();
                    if (!rerankedList.isEmpty()) {
                        chunks = rerankedList;
                        reranked = true;
                    }
                }
            } catch (Exception e) {
                log.warn("[knowledgeRetrieval] rerank failed, keeping RRF order: {}", e.getMessage());
            }
        }
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

    public record Params(String query, Integer topK) {}

    private static String formatChunks(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk r = chunks.get(i);
            String source = r.headingPath() != null && !r.headingPath().isBlank()
                    ? r.headingPath() : "(未知来源)";
            sb.append("[%d] 来源：%s (相关度: %.2f)\n%s"
                    .formatted(i + 1, source, r.score(), r.content()));
            if (i < chunks.size() - 1) sb.append("\n\n");
        }
        return sb.toString();
    }
}
