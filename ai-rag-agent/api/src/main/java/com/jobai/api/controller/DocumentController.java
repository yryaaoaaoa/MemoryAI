package com.jobai.api.controller;

import com.jobai.common.PageResult;
import com.jobai.common.R;
import com.jobai.common.auth.CurrentUserId;
import com.jobai.knowledge.dto.DocumentDTO;
import com.jobai.knowledge.entity.Chunk;
import com.jobai.knowledge.entity.Document;
import com.jobai.knowledge.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "文档管理")
@RestController
@RequiredArgsConstructor
@com.jobai.common.ConditionalOnRagEnabled
public class DocumentController {

    private final DocumentService documentService;

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
