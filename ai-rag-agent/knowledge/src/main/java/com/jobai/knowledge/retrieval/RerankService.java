package com.jobai.knowledge.retrieval;

import com.jobai.common.JobAiProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 对检索结果进行重排序（rerank），使用 cross-encoder 模型打分。
 * 调用 SiliconFlow 的 rerank API。
 */
@Slf4j
@Service
public class RerankService {

    private static final String RERANK_MODEL = "BAAI/bge-reranker-v2-m3";

    private final JobAiProperties properties;
    private RestClient restClient;
    private boolean available;

    public RerankService(JobAiProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        String apiKey = properties.getSiliconflow().getApiKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("sk-your-key-here")) {
            log.warn("SiliconFlow API key not configured — rerank disabled");
            this.available = false;
            return;
        }
        this.restClient = RestClient.builder()
                .baseUrl(properties.getSiliconflow().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.available = true;
        log.info("RerankService initialized with model: {}", RERANK_MODEL);
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * 对查询和文档列表进行重排序，返回原始索引的重排序结果。
     *
     * @param query     用户原始查询
     * @param documents 待重排序的文档列表
     * @param topN      返回前 N 条
     * @return 原始索引列表，按 rerank 相关性降序排列
     */
    @SuppressWarnings("unchecked")
    public List<Integer> rerank(String query, List<String> documents, int topN) {
        if (!available || documents == null || documents.size() <= 1) {
            return null;
        }

        try {
            Map<String, Object> request = Map.of(
                    "model", RERANK_MODEL,
                    "query", query,
                    "documents", documents,
                    "top_n", Math.min(topN, documents.size())
            );

            Map<String, Object> response = restClient.post()
                    .uri("/rerank")
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            if (response == null || response.get("results") == null) {
                log.warn("Rerank returned unexpected response");
                return null;
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");

            // 返回按分数降序排列的原始索引
            return results.stream()
                    .sorted((a, b) -> Double.compare(
                            ((Number) b.get("relevance_score")).doubleValue(),
                            ((Number) a.get("relevance_score")).doubleValue()))
                    .map(r -> ((Number) r.get("index")).intValue())
                    .toList();

        } catch (Exception e) {
            log.warn("Rerank failed, falling back to original order: {}", e.getMessage());
            return null;
        }
    }
}
