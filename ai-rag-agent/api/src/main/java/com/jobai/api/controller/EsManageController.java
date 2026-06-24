package com.jobai.api.controller;

import com.jobai.common.PageResult;
import com.jobai.common.R;
import com.jobai.rag.admin.EsAdminService;
import com.jobai.rag.admin.dto.EsDocumentVO;
import com.jobai.rag.admin.dto.EsIndexInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "ES 管理")
@RestController
@RequestMapping("/api/admin/es")
@RequiredArgsConstructor
public class EsManageController {

    private final EsAdminService esAdminService;

    @Operation(summary = "索引列表")
    @GetMapping("/indices")
    public R<List<EsIndexInfo>> listIndices() {
        return R.ok(esAdminService.listIndices());
    }

    @Operation(summary = "索引详情")
    @GetMapping("/indices/{index}")
    public R<EsIndexInfo> getIndexInfo(@PathVariable String index) {
        return R.ok(esAdminService.getIndexInfo(index));
    }

    @Operation(summary = "索引 Mapping")
    @GetMapping("/indices/{index}/mapping")
    public R<Map<String, Object>> getMapping(@PathVariable String index) {
        return R.ok(esAdminService.getMapping(index));
    }

    @Operation(summary = "文档列表", description = "分页浏览文档，query 为可选关键字搜索")
    @GetMapping("/indices/{index}/docs")
    public R<PageResult<EsDocumentVO>> listDocs(
            @PathVariable String index,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query) {
        return R.ok(esAdminService.listDocs(index, page, size, query));
    }

    @Operation(summary = "文档详情")
    @GetMapping("/indices/{index}/docs/{id}")
    public R<Map<String, Object>> getDoc(@PathVariable String index, @PathVariable String id) {
        return R.ok(esAdminService.getDoc(index, id));
    }

    @Operation(summary = "删除索引")
    @DeleteMapping("/indices/{index}")
    public R<Void> deleteIndex(@PathVariable String index) {
        esAdminService.deleteIndex(index);
        return R.ok();
    }

    @Operation(summary = "删除文档")
    @DeleteMapping("/indices/{index}/docs/{id}")
    public R<Void> deleteDoc(@PathVariable String index, @PathVariable String id) {
        esAdminService.deleteDoc(index, id);
        return R.ok();
    }

    @Operation(summary = "按查询批量删除", description = "body 传入 { \"query\": \"keyword\" }")
    @DeleteMapping("/indices/{index}/docs")
    public R<Long> deleteByQuery(@PathVariable String index, @RequestBody Map<String, String> body) {
        String query = body.get("query");
        long deleted = esAdminService.deleteByQuery(index, query);
        return R.ok(deleted);
    }
}
