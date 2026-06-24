package com.jobai.api.controller;

import com.jobai.common.PageResult;
import com.jobai.common.R;
import com.jobai.common.auth.CurrentUserId;
import com.jobai.knowledge.dto.DocumentDTO;
import com.jobai.knowledge.entity.Chunk;
import com.jobai.knowledge.entity.Document;
import com.jobai.knowledge.parser.DocumentParser;
import com.jobai.knowledge.parser.MdPostProcessor;
import com.jobai.knowledge.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "文档管理")
@RestController
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentParser documentParser;
    private final MdPostProcessor mdPostProcessor;

    @Operation(summary = "文档列表")
    @GetMapping("/api/knowledge-bases/{kbId}/documents")
    public R<PageResult<DocumentDTO>> list(
            @CurrentUserId Long userId,
            @PathVariable Long kbId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageResult<Document> docPage = documentService.listByKb(kbId, page, size, userId);
        PageResult<DocumentDTO> result = PageResult.of(
                docPage.getRecords().stream().map(this::toDTO).toList(),
                docPage.getTotal(), page, size);
        return R.ok(result);
    }

    @Operation(summary = "上传文档")
    @PostMapping("/api/knowledge-bases/{kbId}/documents")
    public R<DocumentDTO> upload(@CurrentUserId Long userId,
                                  @PathVariable Long kbId,
                                  @RequestParam("file") MultipartFile file) {
        Document doc = documentService.createDocument(file, kbId, userId);
        return R.ok(toDTO(doc));
    }

    @Operation(summary = "文档状态")
    @GetMapping("/api/documents/{docId}/status")
    public R<DocumentDTO> getStatus(@CurrentUserId Long userId, @PathVariable Long docId) {
        Document doc = documentService.getDocument(docId, userId);
        return R.ok(toDTO(doc));
    }

    @Operation(summary = "文档切片列表")
    @GetMapping("/api/documents/{docId}/chunks")
    public R<List<Chunk>> getChunks(@CurrentUserId Long userId, @PathVariable Long docId) {
        return R.ok(documentService.getChunks(docId, userId));
    }

    @Operation(summary = "删除文档")
    @DeleteMapping("/api/documents/{docId}")
    public R<Void> delete(@CurrentUserId Long userId, @PathVariable Long docId) {
        documentService.deleteDocument(docId, userId);
        return R.ok();
    }

    @Operation(summary = "OCR预览（解析MD中的图片并查看切片结果，不入库）")
    @PostMapping("/api/test/ocr-preview")
    public R<Map<String, Object>> ocrPreview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "basePath", required = false) String basePath) {
        try {
            String fileName = file.getOriginalFilename();
            byte[] content = file.getBytes();

            // Step 1: Parse — .md 直接读 UTF-8 文本（避免 Tika 吞掉 ![] 图片引用）
            String rawText;
            boolean isMd = fileName != null && (fileName.endsWith(".md") || fileName.endsWith(".markdown"));
            if (isMd) {
                rawText = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                try (var in = new ByteArrayInputStream(content)) {
                    rawText = documentParser.parse(in, fileName).stream()
                            .map(dev.langchain4j.data.document.Document::text)
                            .collect(Collectors.joining("\n"));
                }
            }

            // Step 2: MD post-process (OCR + heading split)
            List<dev.langchain4j.data.document.Document> sections;
            if (isMd) {
                if (basePath != null && !basePath.isBlank()) {
                    sections = mdPostProcessor.process(rawText, basePath);
                } else {
                    sections = mdPostProcessor.splitByHeadings(rawText);
                }
            } else {
                sections = List.of(dev.langchain4j.data.document.Document.from(rawText));
            }

            Map<String, Object> result = new HashMap<>();
            result.put("fileName", fileName);
            result.put("totalChars", rawText.length());
            result.put("sectionCount", sections.size());
            result.put("sections", sections.stream().map(d -> {
                Map<String, Object> s = new HashMap<>();
                s.put("headingPath", d.metadata().getString("heading_path"));
                s.put("text", d.text());
                s.put("textLength", d.text().length());
                return s;
            }).toList());

            return R.ok(result);
        } catch (Exception e) {
            return R.fail(500, "预览失败: " + e.getMessage());
        }
    }

    private DocumentDTO toDTO(Document doc) {
        DocumentDTO dto = new DocumentDTO();
        dto.setId(doc.getId());
        dto.setKbId(doc.getKbId());
        dto.setFileName(doc.getFileName());
        dto.setFileType(doc.getFileType());
        dto.setFileSize(doc.getFileSize());
        dto.setStatus(doc.getStatus());
        dto.setChunkCount(doc.getChunkCount());
        dto.setErrorMessage(doc.getErrorMessage());
        dto.setCreatedAt(doc.getCreatedAt());
        return dto;
    }
}
