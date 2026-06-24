package com.jobai.api.controller;

import com.jobai.api.dto.KnowledgeBaseCreateRequest;
import com.jobai.api.dto.KnowledgeBaseUpdateRequest;
import com.jobai.common.PageResult;
import com.jobai.common.R;
import com.jobai.common.auth.CurrentUserId;
import com.jobai.knowledge.dto.KnowledgeBaseDTO;
import com.jobai.knowledge.entity.KnowledgeBase;
import com.jobai.knowledge.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "知识库管理")
@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @Operation(summary = "知识库列表（?type=system|private|all，默认 all）")
    @GetMapping
    public R<PageResult<KnowledgeBaseDTO>> list(
            @CurrentUserId Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String type) {
        PageResult<KnowledgeBase> result = knowledgeBaseService.list(page, size, userId, type);
        PageResult<KnowledgeBaseDTO> dtoResult = PageResult.of(
                result.getRecords().stream().map(knowledgeBaseService::toDTO).toList(),
                result.getTotal(), page, size);
        return R.ok(dtoResult);
    }

    @Operation(summary = "知识库详情")
    @GetMapping("/{id}")
    public R<KnowledgeBaseDTO> getById(@CurrentUserId Long userId, @PathVariable Long id) {
        KnowledgeBase kb = knowledgeBaseService.getById(id, userId);
        return R.ok(knowledgeBaseService.toDTO(kb));
    }

    @Operation(summary = "浏览系统公共知识库（无需登录）")
    @GetMapping("/public")
    public R<PageResult<KnowledgeBaseDTO>> listPublic(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageResult<KnowledgeBase> result = knowledgeBaseService.list(page, size, 0L, "system");
        PageResult<KnowledgeBaseDTO> dtoResult = PageResult.of(
                result.getRecords().stream().map(knowledgeBaseService::toDTO).toList(),
                result.getTotal(), page, size);
        return R.ok(dtoResult);
    }

    @Operation(summary = "创建知识库")
    @PostMapping
    public R<KnowledgeBaseDTO> create(@CurrentUserId Long userId,
                                       @Valid @RequestBody KnowledgeBaseCreateRequest request) {
        KnowledgeBaseDTO dto = new KnowledgeBaseDTO();
        dto.setName(request.getName());
        dto.setDescription(request.getDescription());
        dto.setPriority(request.getPriority());
        KnowledgeBase kb = knowledgeBaseService.create(dto, userId, request.isSystem());
        return R.ok(knowledgeBaseService.toDTO(kb));
    }

    @Operation(summary = "更新知识库")
    @PutMapping("/{id}")
    public R<KnowledgeBaseDTO> update(@CurrentUserId Long userId,
                                       @PathVariable Long id,
                                       @Valid @RequestBody KnowledgeBaseUpdateRequest request) {
        KnowledgeBaseDTO dto = new KnowledgeBaseDTO();
        dto.setName(request.getName());
        dto.setDescription(request.getDescription());
        dto.setPriority(request.getPriority());
        KnowledgeBase kb = knowledgeBaseService.update(id, dto, userId);
        return R.ok(knowledgeBaseService.toDTO(kb));
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{id}")
    public R<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
        knowledgeBaseService.delete(id, userId);
        return R.ok();
    }
}
