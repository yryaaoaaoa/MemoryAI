package com.jobai.knowledge.store;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import org.springframework.stereotype.Repository;

import java.io.IOException;

@Repository
public class KnowledgeStore {

    private static final String INDEX_NAME = "knowledge_chunk";

    private final ElasticsearchClient client;

    public KnowledgeStore(ElasticsearchClient client) {
        this.client = client;
    }

    public void deleteByDocId(Long documentId) {
        try {
            client.deleteByQuery(d -> d
                    .index(INDEX_NAME)
                    .query(q -> q.term(t -> t
                            .field("doc_id")
                            .value(v -> v.longValue(documentId))
                    ))
                    .refresh(true)
            );
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RETRIEVAL_FAILED, "ES 删除失败: " + e.getMessage());
        }
    }

    public void deleteByKbId(Long kbId) {
        try {
            client.deleteByQuery(d -> d
                    .index(INDEX_NAME)
                    .query(q -> q.term(t -> t
                            .field("kb_id")
                            .value(v -> v.longValue(kbId))
                    ))
                    .refresh(true)
            );
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RETRIEVAL_FAILED, "ES 删除失败: " + e.getMessage());
        }
    }
}
