package com.jobai.knowledge.store;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.util.ObjectBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.common.JobAiProperties.RetrievalProperties;
import com.jobai.knowledge.config.RetrievalConfigService;
import dev.langchain4j.data.embedding.Embedding;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElasticsearchEmbeddingStoreAdapterTest {
    private final Embedding vector = Embedding.from(new float[]{1, 0});

    @Test
    void vectorThresholdUsesCosineWhileBm25RemainsIndependent() {
        var response = response(List.of(hit("weak", 0.6), hit("strong", 0.9)));
        var knn = ElasticsearchEmbeddingStoreAdapter.extractMatches(response, vector, 0.5, true);
        assertEquals(1, knn.size());
        assertEquals("strong", knn.get(0).embeddingId());
        assertEquals(0.8, knn.get(0).score(), 1e-10);
        var bm25 = ElasticsearchEmbeddingStoreAdapter.extractMatches(response, vector, 0.95, false);
        assertEquals(2, bm25.size());
        assertEquals(0.6, bm25.get(0).score());
    }

    @Test
    void preservesSourceMetadataAndExcludesEmbeddingPayload() {
        var matches = ElasticsearchEmbeddingStoreAdapter.extractMatches(response(List.of(hit("chunk", 0.9))), vector, 0.5, true);
        var metadata = matches.get(0).embedded().metadata().toMap();
        assertEquals("事务 > 隔离级别", metadata.get("heading_path"));
        assertEquals(12L, metadata.get("kb_id"));
        assertEquals("34", metadata.get("doc_id"));
        assertEquals(0L, metadata.get("user_id"));
        assertFalse(metadata.containsKey("embedding"));
        assertFalse(metadata.containsKey("content"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsCosineSimilarityToKnnOnly() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        List<SearchRequest> requests = new ArrayList<>();
        when(client.search(any(Function.class), eq(Map.class))).thenAnswer(invocation -> {
            Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> builder = invocation.getArgument(0);
            requests.add(builder.apply(new SearchRequest.Builder()).build());
            return response(List.of());
        });
        var store = new ElasticsearchEmbeddingStoreAdapter(client, mock(RestClient.class), new ObjectMapper(),
                new RetrievalProperties(), mock(RetrievalConfigService.class));
        store.hybridSearch(vector, "Redis", 10, 0.65, null);
        assertEquals(2, requests.size());
        assertEquals(0.65f, requests.get(0).knn().get(0).similarity());
        assertTrue(requests.get(1).knn().isEmpty());
        assertNull(requests.get(1).minScore());
        assertTrue(requests.get(1).query().bool().must().get(0).isMultiMatch());
    }

    @Test
    @SuppressWarnings("unchecked")
    void bulkItemFailureFailsAndDeletesEveryAttemptId() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var rest = mock(RestClient.class);
        var response = mock(org.elasticsearch.client.Response.class);
        when(response.getEntity()).thenReturn(new org.apache.http.entity.StringEntity(
                "{\"errors\":true,\"items\":[{\"index\":{\"status\":201}},{\"index\":{\"status\":400}}]}",
                java.nio.charset.StandardCharsets.UTF_8));
        when(rest.performRequest(any(org.elasticsearch.client.Request.class))).thenReturn(response);
        var deleted = new ArrayList<String>();
        when(client.bulk(any(Function.class))).thenAnswer(invocation -> {
            Function<co.elastic.clients.elasticsearch.core.BulkRequest.Builder,
                    ObjectBuilder<co.elastic.clients.elasticsearch.core.BulkRequest>> builder = invocation.getArgument(0);
            var request = builder.apply(new co.elastic.clients.elasticsearch.core.BulkRequest.Builder()).build();
            request.operations().forEach(op -> deleted.add(op.delete().id()));
            return co.elastic.clients.elasticsearch.core.BulkResponse.of(b -> b.errors(false).took(1).items(List.of()));
        });
        var store = new ElasticsearchEmbeddingStoreAdapter(client, rest, new ObjectMapper(),
                new RetrievalProperties(), mock(RetrievalConfigService.class));
        assertThrows(com.jobai.common.BusinessException.class,
                () -> store.addAll(List.of("first", "second"), List.of(vector, vector), null));
        assertEquals(List.of("first", "second"), deleted);
    }

    private Hit<Map> hit(String id, double score) {
        return Hit.<Map>of(h -> h.index("knowledge_chunk").id(id).score(score).source(Map.of(
                "content", "事务隔离级别", "heading_path", "事务 > 隔离级别", "kb_id", 12L,
                "doc_id", "34", "user_id", 0L, "embedding", List.of(1.0, 0.0))));
    }

    @SuppressWarnings("unchecked")
    private SearchResponse<Map> response(List<Hit<Map>> hits) {
        SearchResponse<Map> response = mock(SearchResponse.class);
        when(response.hits()).thenReturn(HitsMetadata.<Map>of(h -> h.hits(hits)));
        return response;
    }
}
