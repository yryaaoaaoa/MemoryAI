package com.jobai.rag.admin;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.common.BusinessException;
import com.jobai.common.auth.AuthContext;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EsAdminAuthorizationTest {
    @AfterEach void clear() { AuthContext.clear(); }

    @Test @SuppressWarnings("unchecked")
    void fieldSortedDocumentsMayHaveNoScore() throws Exception {
        var client = mock(ElasticsearchClient.class);
        SearchResponse<Map> response = SearchResponse.of(b -> b.took(0).timedOut(false)
                .shards(sh -> sh.total(1).successful(1).failed(0))
                .hits(h -> h.total(t -> t.value(1).relation(co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Eq))
                        .hits(hit -> hit.index("knowledge_chunk").id("sample").source(Map.of("content", "text")))));
        when(client.search(any(Function.class), eq(Map.class))).thenReturn(response);
        AuthContext.set(1L, "admin");
        var result = new EsAdminService(client, mock(RestClient.class), new ObjectMapper())
                .listDocs("knowledge_chunk", 1, 10, null);
        assertEquals(1, result.getTotal());
        assertEquals("sample", result.getRecords().get(0).getId());
        assertNull(result.getRecords().get(0).getScore());
    }

    @Test
    void regularUserCannotReachAnyElasticsearchOperation() {
        var client = mock(ElasticsearchClient.class);
        var rest = mock(RestClient.class);
        var service = new EsAdminService(client, rest, new ObjectMapper());
        AuthContext.set(2L, "user");
        assertThrows(BusinessException.class, service::listIndices);
        assertThrows(BusinessException.class, () -> service.getMapping("knowledge"));
        assertThrows(BusinessException.class, () -> service.getIndexInfo("knowledge"));
        assertThrows(BusinessException.class, () -> service.listDocs("knowledge", 1, 20, null));
        assertThrows(BusinessException.class, () -> service.getDoc("knowledge", "1"));
        assertThrows(BusinessException.class, () -> service.deleteIndex("knowledge"));
        assertThrows(BusinessException.class, () -> service.deleteDoc("knowledge", "1"));
        assertThrows(BusinessException.class, () -> service.deleteByQuery("knowledge", "*"));
        verifyNoInteractions(client, rest);
    }
}
