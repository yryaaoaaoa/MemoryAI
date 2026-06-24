package com.jobai.knowledge.service;

import com.jobai.common.JobAiProperties;
import com.jobai.knowledge.entity.Chunk;
import com.jobai.knowledge.entity.Document;
import com.jobai.knowledge.mapper.ChunkMapper;
import com.jobai.knowledge.mapper.DocumentMapper;
import com.jobai.knowledge.parser.DocumentParser;
import com.jobai.knowledge.parser.MdPostProcessor;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessor {

    private static final int DOC_PARSE_TIMEOUT_SECONDS = 180;

    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final DocumentParser documentParser;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final JobAiProperties properties;
    private final MdPostProcessor mdPostProcessor;

    @Qualifier("parseExecutor")
    private final Executor parseExecutor;

    @Async
    public void processDocument(Long docId, byte[] fileContent) {
        try {
            // 在隔离的 parseExecutor 上执行，支持超时取消
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    doProcess(docId, fileContent);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, parseExecutor);

            future.get(DOC_PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Document processing timed out after {}s: docId={}", DOC_PARSE_TIMEOUT_SECONDS, docId);
            markFailed(docId, "解析超时（" + DOC_PARSE_TIMEOUT_SECONDS + "s）");
        } catch (Exception e) {
            log.error("Document processing failed: docId={}", docId, e);
            markFailed(docId, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    private void markFailed(Long docId, String error) {
        Document doc = documentMapper.selectById(docId);
        if (doc != null) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(error);
            documentMapper.updateById(doc);
        }
    }

    private void doProcess(Long docId, byte[] fileContent) throws Exception {
        Document doc = documentMapper.selectById(docId);
        if (doc == null) return;

        // Step 1: Parse — .md 直接读 UTF-8 文本（避免 Tika 吞掉 ![] 图片引用）
        updateStatus(doc, DocumentStatus.PARSING);
        List<dev.langchain4j.data.document.Document> lc4jDocs;
        if (isMarkdown(doc.getFileName())) {
            String rawText = new String(fileContent, java.nio.charset.StandardCharsets.UTF_8);
            lc4jDocs = mdPostProcessor.splitByHeadings(rawText);
        } else {
            try (var in = new java.io.ByteArrayInputStream(fileContent)) {
                lc4jDocs = documentParser.parse(in, doc.getFileName());
            }
        }
        // Attach metadata for all file types
        lc4jDocs.forEach(d -> {
            d.metadata().put("kb_id", doc.getKbId());
            d.metadata().put("doc_id", doc.getId());
            d.metadata().put("user_id", doc.getUserId() != null ? doc.getUserId() : 0L);
        });

        // Step 2: Split using LangChain4j recursive splitter
        updateStatus(doc, DocumentStatus.CHUNKING);
        int maxChars = properties.getSplitter().getMaxChars();
        int overlapChars = properties.getSplitter().getOverlapChars();
        var splitter = DocumentSplitters.recursive(maxChars, overlapChars);

        List<TextSegment> allSegments = new ArrayList<>();
        for (var lc4jDoc : lc4jDocs) {
            allSegments.addAll(splitter.split(lc4jDoc));
        }

        // Step 3: Embeddable + Store in ES
        updateStatus(doc, DocumentStatus.EMBEDDING);
        var embeddingResponse = embeddingModel.embedAll(allSegments);
        List<Embedding> embeddings = embeddingResponse.content();

        List<String> ids = new ArrayList<>(embeddings.size());
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        embeddingStore.addAll(ids, embeddings, allSegments);

        // Step 4: Store chunks in MySQL
        List<Chunk> allChunks = new ArrayList<>(allSegments.size());
        for (int i = 0; i < allSegments.size(); i++) {
            TextSegment seg = allSegments.get(i);
            Chunk chunk = new Chunk();
            chunk.setDocId(doc.getId());
            chunk.setKbId(doc.getKbId());
            chunk.setContent(seg.text());
            chunk.setHeadingPath(seg.metadata().getString("heading_path"));
            chunk.setChunkIndex(i);
            chunk.setTokenCount(estimateTokens(seg.text()));
            chunk.setVectorId(ids.get(i));
            allChunks.add(chunk);
        }

        int mysqlBatchSize = Math.min(500, Math.max(1, allSegments.size()));
        for (int start = 0; start < allChunks.size(); start += mysqlBatchSize) {
            int end = Math.min(start + mysqlBatchSize, allChunks.size());
            chunkMapper.insertBatch(allChunks.subList(start, end));
        }

        // Step 5: Mark ready
        doc.setChunkCount(allChunks.size());
        doc.setStatus(DocumentStatus.READY);
        documentMapper.updateById(doc);

        log.info("Document processed: docId={}, chunks={}", docId, allChunks.size());
    }

    private void updateStatus(Document doc, String status) {
        doc.setStatus(status);
        documentMapper.updateById(doc);
    }

    private int estimateTokens(String text) {
        int len = text.length();
        return switch (properties.getSplitter().getTokenEstimation()) {
            case "char4" -> len / 4;
            case "raw" -> len;
            default -> len / 2; // "half"
        };
    }

    private static boolean isMarkdown(String fileName) {
        if (fileName == null) return false;
        String name = fileName.toLowerCase();
        return name.endsWith(".md") || name.endsWith(".markdown");
    }
}
