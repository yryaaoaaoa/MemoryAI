package com.jobai.api.controller;

import com.jobai.common.R;
import com.jobai.common.auth.CurrentUserId;
import com.jobai.rag.entity.LearningNote;
import com.jobai.rag.service.LearningNoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning-notes")
@RequiredArgsConstructor
public class LearningNoteController {
    private final LearningNoteService notes;

    @GetMapping
    public R<LearningNoteService.NotePage> list(@CurrentUserId Long userId,
            @RequestParam(required = false) String query, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return R.ok(notes.list(userId, query, status, page, size));
    }

    @PostMapping
    public R<LearningNote> create(@CurrentUserId Long userId, @RequestBody LearningNoteService.NoteInput input) {
        return R.ok(notes.create(userId, input));
    }

    @GetMapping("/{id}")
    public R<LearningNote> get(@CurrentUserId Long userId, @PathVariable Long id) {
        return R.ok(notes.get(id, userId));
    }

    @PutMapping("/{id}")
    public R<LearningNote> update(@CurrentUserId Long userId, @PathVariable Long id,
                                @RequestBody LearningNoteService.NoteInput input) {
        return R.ok(notes.update(id, userId, input));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
        notes.delete(id, userId);
        return R.ok();
    }

    @PostMapping("/draft")
    public R<LearningNoteService.Draft> draft(@CurrentUserId Long userId,
                                             @RequestBody LearningNoteService.DraftInput input) {
        return R.ok(notes.draft(userId, input));
    }

    @PostMapping("/{id}/sessions")
    public R<Long> createSession(@CurrentUserId Long userId, @PathVariable Long id) {
        return R.ok(notes.createSession(id, userId));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> invalidBody() {
        return R.fail(400, "请求内容格式无效，请检查笔记字段和消息编号");
    }
}
