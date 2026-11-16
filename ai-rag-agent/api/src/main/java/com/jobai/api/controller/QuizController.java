package com.jobai.api.controller;

import com.jobai.agent.service.QuizRecordService;
import com.jobai.agent.service.QuizRecordService.*;
import com.jobai.agent.service.WrongQuestionService;
import com.jobai.agent.service.WrongQuestionService.*;
import com.jobai.common.R;
import com.jobai.common.auth.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Tag(name = "错题本")
@RestController
@RequestMapping("/api/quiz")
@RequiredArgsConstructor
public class QuizController {

    private final WrongQuestionService wrongQuestionService;
    private final QuizRecordService quizRecordService;

    @Operation(summary = "错题列表（分页，最新在前）")
    @GetMapping("/wrong-questions")
    public R<WrongQuestionPage> listWrongQuestions(
            @CurrentUserId Long userId,
            @RequestParam(required = false) String topic,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        validatePage(page, size);
        return R.ok(wrongQuestionService.listQuestions(userId, topic, page, size));
    }

    @Operation(summary = "错题知识点分布统计")
    @GetMapping("/wrong-topics")
    public R<List<WrongTopicVO>> listWrongTopics(@CurrentUserId Long userId) {
        return R.ok(wrongQuestionService.listTopics(userId));
    }

    @Operation(summary = "从错题本移出（保留答题历史和掌握度）")
    @PostMapping("/wrong-questions/{questionId}/remove")
    public R<Void> removeWrongQuestion(
            @CurrentUserId Long userId,
            @PathVariable Long questionId) {
        wrongQuestionService.removeWrongQuestion(userId, questionId);
        return R.ok();
    }

    @Data
    public static class RetryRequest {
        private int count = 5;
    }

    @Operation(summary = "从错题本随机抽题重做")
    @PostMapping("/retry-wrong")
    public R<List<WrongQuestionVO>> retryWrongQuestions(
            @CurrentUserId Long userId,
            @RequestBody RetryRequest req) {
        if (req.getCount() < 1 || req.getCount() > 20) throw new IllegalArgumentException("重做数量应为 1 至 20 道");
        return R.ok(wrongQuestionService.getRetryQuestions(userId, req.getCount()));
    }

    @Data
    public static class SubmitRequest {
        private Long questionId;
        private String userAnswer;
        private int durationSec;
    }

    @Data
    public static class BatchStatusRequest {
        private List<Long> questionIds;
    }

    @Operation(summary = "批量查询题目答题状态")
    @PostMapping("/batch-status")
    public R<Map<Long, SubmitResult>> batchStatus(
            @CurrentUserId Long userId,
            @RequestBody BatchStatusRequest req) {
        return R.ok(wrongQuestionService.batchCheckStatus(userId, req.getQuestionIds()));
    }

    @Operation(summary = "提交答案 — 自动批改（选择题） + 写入错题记录")
    @PostMapping("/submit")
    public R<SubmitResult> submitAnswer(
            @CurrentUserId Long userId,
            @RequestBody SubmitRequest req) {
        if (req.getQuestionId() == null || req.getQuestionId() <= 0) throw new IllegalArgumentException("请选择有效题目");
        String answer = req.getUserAnswer() == null ? "" : req.getUserAnswer().strip().toUpperCase(Locale.ROOT);
        // Confirmed early submission may contain unanswered questions, recorded as incorrect.
        if (!answer.matches("[ABCD]?")) throw new IllegalArgumentException("答案应为 A、B、C、D 或留空");
        if (req.getDurationSec() < 0) throw new IllegalArgumentException("答题用时不能为负数");
        return R.ok(wrongQuestionService.submitAnswer(
                userId, req.getQuestionId(), answer, req.getDurationSec()));
    }

    @GetMapping("/record-topics")
    public R<List<String>> listRecordTopics(@CurrentUserId Long userId) {
        return R.ok(quizRecordService.listTopics(userId));
    }

    @Operation(summary = "刷题记录（分页，最新在前）")
    @GetMapping("/records")
    public R<QuizRecordPage> listRecords(
            @CurrentUserId Long userId,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) Boolean correct,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        return R.ok(quizRecordService.listRecords(userId, topic, correct, dateFrom, dateTo, page, size));
    }

    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("页码应大于 0，每页数量应为 1 至 100");
    }
}
