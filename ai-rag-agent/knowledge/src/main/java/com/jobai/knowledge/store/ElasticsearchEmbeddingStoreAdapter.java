package com.jobai.knowledge.store;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.JobAiProperties.RetrievalProperties;
import com.jobai.knowledge.config.RetrievalConfigService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.*;

@Slf4j
@Repository
public class ElasticsearchEmbeddingStoreAdapter implements EmbeddingStore<TextSegment> {

    private static final String INDEX_NAME = "knowledge_chunk";

    private final ElasticsearchClient client;
    private final RestClient lowLevelClient;
    private final ObjectMapper objectMapper;
    private final RetrievalProperties retrievalConfig;
    private final RetrievalConfigService dynamicConfig;

    public ElasticsearchEmbeddingStoreAdapter(ElasticsearchClient client, RestClient lowLevelClient,
                                              ObjectMapper objectMapper, RetrievalProperties retrievalConfig,
                                              RetrievalConfigService dynamicConfig) {
        this.client = client;
        this.lowLevelClient = lowLevelClient;
        this.objectMapper = objectMapper;
        this.retrievalConfig = retrievalConfig;
        this.dynamicConfig = dynamicConfig;
    }

    @Override
    public String add(Embedding embedding) {
        return add(embedding, null);
    }

    @Override
    public void add(String id, Embedding embedding) {
        indexDoc(id, embedding, null);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        String id = UUID.randomUUID().toString();
        indexDoc(id, embedding, textSegment);
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = new ArrayList<>(embeddings.size());
        for (Embedding embedding : embeddings) {
            ids.add(add(embedding));
        }
        return ids;
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> textSegments) {
        if (ids == null || ids.isEmpty()) return;

        // Use low-level RestClient to bypass 7.x client / ES 8.x response compatibility
        StringBuilder ndjson = new StringBuilder(ids.size() * 1024);
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Embedding embedding = embeddings.get(i);
            TextSegment segment = textSegments != null && i < textSegments.size() ? textSegments.get(i) : null;

            // action metadata line
            ndjson.append("{\"index\":{\"_index\":\"")
                    .append(INDEX_NAME)
                    .append("\",\"_id\":\"")
                    .append(id)
                    .append("\"}}\n");
            // source document line
            try {
                ndjson.append(objectMapper.writeValueAsString(toDoc(embedding, segment))).append("\n");
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.EMBEDDING_FAILED, "序列化文档失败: " + e.getMessage());
            }
        }

        try {
            Request request = new Request("POST", "/_bulk");
            request.setJsonEntity(ndjson.toString());
            Response response = lowLevelClient.performRequest(request);
            String body = EntityUtils.toString(response.getEntity());
            JsonNode root = objectMapper.readTree(body);
            if (root.has("errors") && root.get("errors").asBoolean()) {
                log.warn("ES bulk index completed with item-level errors");
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.EMBEDDING_FAILED, "ES 批量写入失败: " + e.getMessage());
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        Embedding queryEmbedding = request.queryEmbedding();
        int topK = request.maxResults();
        double minScore = request.minScore();
        Filter filter = request.filter();

        // Build base query: bool filter or match_all, wrapped in script_score
        Query filterQuery = buildFilter8(filter);

        java.util.Map<String, JsonData> scriptParams = Collections.singletonMap(
                "queryVector", JsonData.of(queryEmbedding.vectorAsList()));

        try {
            SearchResponse<Map> response = client.search(s -> {
                // script_score query with cosineSimilarity
                s.index(INDEX_NAME)
                        .size(topK)
                        .source(src -> src.fetch(true));

                if (filterQuery != null) {
                    s.query(q -> q.scriptScore(ss -> ss
                            .query(qInner -> qInner.bool(b -> b.filter(filterQuery)))
                            .script(sc -> sc
                                    .lang("painless")
                                    .source("cosineSimilarity(params.queryVector, 'embedding') + 1.0")
                                    .params(scriptParams)
                            )
                    ));
                } else {
                    s.query(q -> q.scriptScore(ss -> ss
                            .query(qInner -> qInner.matchAll(ma -> ma))
                            .script(sc -> sc
                                    .lang("painless")
                                    .source("cosineSimilarity(params.queryVector, 'embedding') + 1.0")
                                    .params(scriptParams)
                            )
                    ));
                }
                return s;
            }, Map.class);

            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();

            for (Hit<Map> hit : response.hits().hits()) {
                double esScore = hit.score();
                // convert from [0, 2] range back to cosine similarity
                double similarity = (esScore - 1.0);
                if (similarity < minScore) continue;

                Map<String, Object> source = hit.source();
                if (source == null) continue;

                String content = (String) source.get("content");
                if (content == null || content.isBlank()) continue;

                TextSegment segment = TextSegment.from(content);
                EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(
                        similarity, hit.id(), queryEmbedding, segment
                );
                matches.add(match);
            }

            return new EmbeddingSearchResult<>(matches);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RETRIEVAL_FAILED, "ES 检索失败: " + e.getMessage());
        }
    }

    /**
     * Hybrid search result: separate kNN (vector) and BM25 (keyword) ranked lists.
     * Caller fuses them via RRF — avoids ES Enterprise license requirement for rank API.
     */
    public record HybridResult(List<EmbeddingMatch<TextSegment>> knnMatches,
                                List<EmbeddingMatch<TextSegment>> bm25Matches) {}

    /**
     * Hybrid search: kNN (vector) + BM25 (keyword) as two separate queries.
     * Fusion via RRF is done on the application side (see VectorRetrievalService.fuseByRrf).
     */
    public HybridResult hybridSearch(Embedding queryEmbedding, String queryText,
                                     int topK, double minScore, Filter filter) {
        RetrievalProperties cfg = resolveConfig();
        Query filterQuery = buildFilter8(filter);
        List<Float> queryVector = queryEmbedding.vectorAsList();
        int numCandidates = topK * cfg.getNumCandidatesFactor();
        List<String> bm25Fields = List.of(cfg.getBm25Fields().split(","));

        try {
            // 1. kNN vector search
            SearchResponse<Map> knnResponse = client.search(s -> {
                s.index(INDEX_NAME).size(topK).source(src -> src.fetch(true));
                s.knn(k -> {
                    k.field("embedding").queryVector(queryVector).k(topK)
                            .numCandidates(numCandidates).similarity((float) minScore);
                    if (filterQuery != null) k.filter(filterQuery);
                    return k;
                });
                return s;
            }, Map.class);

            // 2. BM25 keyword search
            SearchResponse<Map> bm25Response = client.search(s -> {
                s.index(INDEX_NAME).size(topK).source(src -> src.fetch(true));
                s.query(q -> q.bool(b -> {
                    b.must(m -> m.multiMatch(mm -> mm
                            .fields(bm25Fields).query(queryText).type(TextQueryType.BestFields)));
                    if (filterQuery != null) b.filter(filterQuery);
                    return b;
                }));
                return s;
            }, Map.class);

            List<EmbeddingMatch<TextSegment>> knnMatches = extractMatches(knnResponse, queryEmbedding, minScore);
            List<EmbeddingMatch<TextSegment>> bm25Matches = extractMatches(bm25Response, queryEmbedding, minScore);

            return new HybridResult(knnMatches, bm25Matches);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RETRIEVAL_FAILED, "ES 混合检索失败: " + e.getMessage());
        }
    }

    private List<EmbeddingMatch<TextSegment>> extractMatches(SearchResponse<Map> response,
                                                              Embedding queryEmbedding, double minScore) {
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> source = hit.source();
            if (source == null) continue;
            String content = (String) source.get("content");
            if (content == null || content.isBlank()) continue;

            double score = hit.score();
            EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(
                    score, hit.id(), queryEmbedding, TextSegment.from(content)
            );
            matches.add(match);
        }
        return matches;
    }

    private RetrievalProperties resolveConfig() {
        RetrievalProperties active = dynamicConfig.getActiveConfig();
        return active != null ? active : retrievalConfig;
    }

    private Query buildFilter8(Filter filter) {
        if (filter == null) return null;
        try {
            if (filter instanceof dev.langchain4j.store.embedding.filter.comparison.IsEqualTo eq) {
                Object val = eq.comparisonValue();
                return Query.of(q -> q.term(t -> {
                    t.field(eq.key());
                    if (val instanceof Number n) {
                        t.value(n.longValue());
                    } else {
                        t.value(String.valueOf(val));
                    }
                    return t;
                }));
            }
            if (filter instanceof dev.langchain4j.store.embedding.filter.comparison.IsIn in) {
                Collection<?> values = in.comparisonValues();
                if (values != null && !values.isEmpty()) {
                    List<FieldValue> fvList = values.stream()
                            .map(v -> FieldValue.of(String.valueOf(v)))
                            .toList();
                    return Query.of(q -> q.terms(t -> t
                            .field(in.key())
                            .terms(t2 -> t2.value(fvList))
                    ));
                }
            }
            if (filter instanceof dev.langchain4j.store.embedding.filter.logical.And and) {
                Query left = buildFilter8(and.left());
                Query right = buildFilter8(and.right());
                if (left != null && right != null) {
                    return Query.of(q -> q.bool(b -> b.must(List.of(left, right))));
                }
                return left != null ? left : right;
            }
            if (filter instanceof dev.langchain4j.store.embedding.filter.logical.Or or) {
                Query left = buildFilter8(or.left());
                Query right = buildFilter8(or.right());
                if (left != null && right != null) {
                    return Query.of(q -> q.bool(b -> b.should(List.of(left, right))));
                }
                return left != null ? left : right;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    @Override
    public void removeAll(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        try {
            client.bulk(b -> {
                for (String id : ids) {
                    b.operations(op -> op.delete(d -> d.index(INDEX_NAME).id(id)));
                }
                return b;
            });
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RETRIEVAL_FAILED, "ES 批量删除失败: " + e.getMessage());
        }
    }

    @Override
    public void removeAll(Filter filter) {
        Query filterQuery = buildFilter8(filter);
        if (filterQuery == null) {
            removeAll();
            return;
        }
        try {
            client.deleteByQuery(d -> d
                    .index(INDEX_NAME)
                    .query(q -> q.bool(b -> b.filter(filterQuery)))
            );
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RETRIEVAL_FAILED, "ES 删除失败: " + e.getMessage());
        }
    }

    @Override
    public void removeAll() {
        try {
            client.deleteByQuery(d -> d
                    .index(INDEX_NAME)
                    .query(q -> q.matchAll(ma -> ma))
            );
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RETRIEVAL_FAILED, "ES 清空失败: " + e.getMessage());
        }
    }

    private void indexDoc(String id, Embedding embedding, TextSegment segment) {
        try {
            client.index(i -> i
                    .index(INDEX_NAME)
                    .id(id)
                    .document(toDoc(embedding, segment))
            );
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.EMBEDDING_FAILED, "ES 写入失败: " + e.getMessage());
        }
    }

    private Map<String, Object> toDoc(Embedding embedding, TextSegment segment) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("embedding", embedding.vectorAsList());
        if (segment != null) {
            doc.put("content", segment.text());
            if (segment.metadata() != null) {
                segment.metadata().toMap().forEach((k, v) -> {
                    if (!"index".equals(k)) {
                        doc.put(k, v);
                    }
                });
            }
        }
        return doc;
    }
}
