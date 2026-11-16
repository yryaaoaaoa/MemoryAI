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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@com.jobai.common.ConditionalOnRagEnabled
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

    private final java.util.concurrent.ConcurrentMap<Long, ProcessingTask> tasks = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class ProcessingTask {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DOC_PARSE_TIMEOUT_SECONDS);
        final List<String> vectorIds = new ArrayList<>();
        boolean finished;
    }

    // Cancellation must remain retryable even if the caller's deletion transaction rolls back.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancel(Long docId) {
        ProcessingTask task = tasks.get(docId);
        if (task == null) {
            documentMapper.markCancelledProcessing(docId);
            return;
        }
        synchronized (task) {
            documentMapper.markCancelledProcessing(docId);
            tasks.remove(docId, task);
        }
    }

    @Async
    public void processDocument(Long docId, byte[] fileContent) {
        Document pending = documentMapper.selectProcessable(docId);
        if (pending == null || !DocumentStatus.UPLOADING.equals(pending.getStatus())) return;
        ProcessingTask task = new ProcessingTask();
        tasks.put(docId, task);
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    doProcess(docId, fileContent, task);
                } catch (Exception e) {
                    // IDs belong only to this attempt, so a late worker cannot erase a retry.
                    try {
                        if (!task.vectorIds.isEmpty()) embeddingStore.removeAll(task.vectorIds);
                    } catch (Exception cleanup) {
                        e.addSuppressed(cleanup);
                    }
                    try {
                        if (!task.vectorIds.isEmpty()) chunkMapper.delete(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                                .in(Chunk::getVectorId, task.vectorIds));
                    } catch (Exception cleanup) {
                        e.addSuppressed(cleanup);
                    }
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, parseExecutor);
            future.get(DOC_PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            synchronized (task) {
                if (tasks.get(docId) == task && !task.finished) {
                    tasks.remove(docId, task);
                    Document doc = documentMapper.selectById(docId);
                    if (doc != null) {
                        doc.setStatus(DocumentStatus.FAILED);
                        doc.setErrorMessage(e instanceof java.util.concurrent.TimeoutException
                            ? "解析超时（180s），请重新上传重试"
                            : "文档处理失败，请重新上传重试");
                        documentMapper.updateById(doc);
                    }
                }
            }
            log.error("Document processing failed: docId={}", docId, e);
        } finally {
            tasks.remove(docId, task);
        }
    }

    private void checkActive(Long docId, ProcessingTask task) {
        if (tasks.get(docId) != task || System.nanoTime() >= task.deadline
                || documentMapper.selectProcessable(docId) == null) {
            throw new IllegalStateException("文档处理已取消或超时");
        }
    }

    private void doProcess(Long docId, byte[] fileContent, ProcessingTask task) throws Exception {
        checkActive(docId, task);
        Document doc = documentMapper.selectProcessable(docId);
        if (doc == null) return;

        // Step 1: Parse — .md 直接读 UTF-8 文本（避免 Tika 吞掉 ![] 图片引用）
        updateStatus(doc, task, DocumentStatus.PARSING);
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
        updateStatus(doc, task, DocumentStatus.CHUNKING);
        int maxChars = properties.getSplitter().getMaxChars();
        int overlapChars = properties.getSplitter().getOverlapChars();
        var splitter = DocumentSplitters.recursive(maxChars, overlapChars);

        List<TextSegment> allSegments = new ArrayList<>();
        for (var lc4jDoc : lc4jDocs) {
            allSegments.addAll(splitter.split(lc4jDoc));
        }

        // Step 3: Embeddable + Store in ES
        updateStatus(doc, task, DocumentStatus.EMBEDDING);
        if (allSegments.isEmpty()) throw new IllegalArgumentException("文档没有可检索内容");
        var embeddingResponse = embeddingModel.embedAll(allSegments);
        List<Embedding> embeddings = embeddingResponse.content();

        checkActive(docId, task);
        if (embeddings.size() != allSegments.size()) throw new IllegalStateException("向量数量与切片数量不一致");
        List<String> ids = task.vectorIds;
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        checkActive(docId, task);
        embeddingStore.addAll(ids, embeddings, allSegments);
        checkActive(docId, task);

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
            synchronized (task) {
                checkActive(docId, task);
                chunkMapper.insertBatch(allChunks.subList(start, end));
            }
        }

        // Step 5: Mark ready
        synchronized (task) {
            checkActive(docId, task);
            doc.setChunkCount(allChunks.size());
            doc.setStatus(DocumentStatus.READY);
            if (documentMapper.updateById(doc) != 1) throw new IllegalStateException("文档已删除");
            task.finished = true;
        }

        log.info("Document processed: docId={}, chunks={}", docId, allChunks.size());
    }

    private void updateStatus(Document doc, ProcessingTask task, String status) {
        synchronized (task) {
            checkActive(doc.getId(), task);
            doc.setStatus(status);
            if (documentMapper.updateById(doc) != 1) throw new IllegalStateException("文档已删除");
        }
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
