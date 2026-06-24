package com.jobai.api.controller;

import com.jobai.common.R;
import com.jobai.common.auth.CurrentUserId;
import com.jobai.knowledge.entity.Resume;
import com.jobai.knowledge.service.ResumeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "简历管理")
@RestController
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    @Operation(summary = "上传简历")
    @PostMapping("/api/resume/upload")
    public R<Resume> upload(@CurrentUserId Long userId, @RequestParam("file") MultipartFile file) {
        return R.ok(resumeService.upload(file, userId));
    }

    @Operation(summary = "简历列表")
    @GetMapping("/api/resume/list")
    public R<List<Resume>> list(@CurrentUserId Long userId) {
        return R.ok(resumeService.list(userId));
    }

    @Operation(summary = "简历详情")
    @GetMapping("/api/resume/{id}")
    public R<Resume> detail(@CurrentUserId Long userId, @PathVariable Long id) {
        return R.ok(resumeService.getById(id, userId));
    }

    @Operation(summary = "重新分析")
    @PostMapping("/api/resume/{id}/reanalyze")
    public R<Resume> reanalyze(@CurrentUserId Long userId,
                                @PathVariable Long id,
                                @RequestParam("file") MultipartFile file) {
        resumeService.reanalyze(id, file, userId);
        return R.ok(resumeService.getById(id, userId));
    }

    @Operation(summary = "删除简历")
    @DeleteMapping("/api/resume/{id}")
    public R<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
        resumeService.delete(id, userId);
        return R.ok();
    }
}
