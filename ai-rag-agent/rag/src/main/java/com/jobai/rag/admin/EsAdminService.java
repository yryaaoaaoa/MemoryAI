package com.jobai.rag.admin;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.PageResult;
import com.jobai.rag.admin.dto.EsDocumentVO;
import com.jobai.rag.admin.dto.EsIndexInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class EsAdminService {

    private static final List<String> SYSTEM_INDEX_PREFIXES = List.of(
            ".ds-", ".elastic-", ".ilm-", ".monitoring-",
            ".watcher-", ".security-", "ilm-history-", ".apm-",
            ".async-search", ".geoip_databases", ".ml-", ".transform-");

    private final ElasticsearchClient client;
    private final RestClient lowLevelClient;
    private final ObjectMapper objectMapper;

    public List<EsIndexInfo> listIndices() {
        try {
            Request request = new Request("GET", "/_cat/indices?format=json&bytes=b");
            Response response = lowLevelClient.performRequest(request);
            String json = EntityUtils.toString(response.getEntity());
            JsonNode[] nodes = objectMapper.readValue(json, JsonNode[].class);
            List<EsIndexInfo> result = new ArrayList<>();
            for (JsonNode node : nodes) {
                String name = node.get("index").asText();
                if (isSystemIndex(name)) continue;
                result.add(EsIndexInfo.builder()
                        .name(name)
                        .health(node.get("health").asText())
                        .docCount(node.get("docs.count").asLong())
                        .storageSizeBytes(node.get("store.size").asLong())
                        .numOfShards(node.get("pri").asInt())
                        .numOfReplicas(node.get("rep").asInt())
                        .build());
            }
            result.sort(Comparator.comparing(EsIndexInfo::getName));
            return result;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取 ES 索引列表失败: " + e.getMessage());
        }
    }

    public Map<String, Object> getMapping(String index) {
        assertNotSystemIndex(index);
        try {
            GetMappingResponse response = client.indices().getMapping(g -> g.index(index));
            var state = response.get(index);
            if (state == null || state.mappings() == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "索引不存在: " + index);
            }
            return flattenMapping(state.mappings().properties());
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "索引不存在: " + index);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取 mapping 失败: " + e.getMessage());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取 mapping 失败: " + e.getMessage());
        }
    }

    private Map<String, Object> flattenMapping(
            Map<String, co.elastic.clients.elasticsearch._types.mapping.Property> properties) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (properties == null) return result;
        for (var entry : properties.entrySet()) {
            result.put(entry.getKey(), entry.getValue()._kind().jsonValue());
        }
        return result;
    }

    public EsIndexInfo getIndexInfo(String index) {
        assertNotSystemIndex(index);
        List<EsIndexInfo> all = listIndices();
        return all.stream()
                .filter(i -> i.getName().equals(index))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "索引不存在: " + index));
    }

    public PageResult<EsDocumentVO> listDocs(String index, int page, int size, String query) {
        assertNotSystemIndex(index);
        try {
            SearchResponse<Map> searchResponse = client.search(s -> {
                s.index(index)
                        .from((page - 1) * size)
                        .size(size)
                        .sort(o -> o.field(f -> f.field("_doc").order(SortOrder.Asc)));

                if (query != null && !query.isBlank()) {
                    s.query(q -> q.queryString(qs -> qs.query(query)));
                }

                return s;
            }, Map.class);

            List<EsDocumentVO> records = new ArrayList<>();
            for (Hit<Map> hit : searchResponse.hits().hits()) {
                records.add(EsDocumentVO.builder()
                        .id(hit.id())
                        .source(hit.source())
                        .score(hit.score().floatValue())
                        .build());
            }

            return PageResult.of(records, searchResponse.hits().total().value(), page, size);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "查询文档失败: " + e.getMessage());
        }
    }

    public Map<String, Object> getDoc(String index, String id) {
        assertNotSystemIndex(index);
        try {
            GetResponse<Map> getResponse = client.get(g -> g
                    .index(index)
                    .id(id), Map.class);

            if (!getResponse.found()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在: " + id);
            }
            Map<String, Object> result = new LinkedHashMap<>(
                    getResponse.source() != null ? getResponse.source() : Map.of());
            result.put("_id", id);
            result.put("_index", index);
            return result;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在: " + id);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取文档失败: " + e.getMessage());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取文档失败: " + e.getMessage());
        }
    }

    public void deleteIndex(String index) {
        assertNotSystemIndex(index);
        try {
            client.indices().delete(d -> d.index(index));
            log.warn("ES index deleted: {}", index);
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "索引不存在: " + index);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "删除索引失败: " + e.getMessage());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "删除索引失败: " + e.getMessage());
        }
    }

    public void deleteDoc(String index, String id) {
        assertNotSystemIndex(index);
        try {
            client.delete(d -> d.index(index).id(id));
            log.warn("ES document deleted: index={}, id={}", index, id);
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在: " + id);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "删除文档失败: " + e.getMessage());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "删除文档失败: " + e.getMessage());
        }
    }

    public long deleteByQuery(String index, String query) {
        assertNotSystemIndex(index);
        try {
            Long deleted = client.deleteByQuery(d -> d
                    .index(index)
                    .query(q -> q.queryString(qs -> qs.query(query)))
                    .refresh(true)
            ).deleted();
            long count = deleted != null ? deleted : 0;
            log.warn("ES documents deleted by query: index={}, query={}, count={}", index, query, count);
            return count;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "索引不存在: " + index);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "批量删除失败: " + e.getMessage());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "批量删除失败: " + e.getMessage());
        }
    }

    private void assertNotSystemIndex(String name) {
        if (isSystemIndex(name)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "系统索引不允许操作: " + name);
        }
    }

    private boolean isSystemIndex(String name) {
        if (name.startsWith(".")) return true;
        for (String prefix : SYSTEM_INDEX_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
