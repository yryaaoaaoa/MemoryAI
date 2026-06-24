package com.jobai.api.controller;

import com.jobai.agent.interview.model.*;
import com.jobai.agent.interview.service.InterviewSessionService;
import com.jobai.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
@Tag(name = "模拟面试", description = "面试会话创建、逐题问答、报告生成")
public class InterviewController {

    private final InterviewSessionService sessionService;

    @Operation(summary = "列出所有面试会话")
    @GetMapping("/sessions")
    public R<List<Map<String, Object>>> listSessions() {
        return R.ok(sessionService.listSessions());
    }

    @Operation(summary = "创建面试会话（指定方向/难度/题数/简历）")
    @PostMapping("/sessions")
    public R<InterviewSessionDTO> createSession(@Valid @RequestBody CreateInterviewRequest request) {
        log.info("Creating interview: skill={}, count={}, resumeId={}",
                request.skillId(), request.questionCount(), request.resumeId());
        return R.ok(sessionService.createSession(request));
    }

    @Operation(summary = "获取会话信息（含题目列表和进度）")
    @GetMapping("/sessions/{sessionId}")
    public R<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
        return R.ok(sessionService.getSession(sessionId));
    }

    @Operation(summary = "获取当前问题")
    @GetMapping("/sessions/{sessionId}/question")
    public R<Map<String, Object>> getCurrentQuestion(@PathVariable String sessionId) {
        return R.ok(sessionService.getCurrentQuestionResponse(sessionId));
    }

    @Operation(summary = "提交答案并进入下一题")
    @PostMapping("/sessions/{sessionId}/answers")
    public R<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        int questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("Submit answer: sessionId={}, index={}", sessionId, questionIndex);
        var req = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        return R.ok(sessionService.submitAnswer(req));
    }

    @Operation(summary = "暂存答案（不进入下一题）")
    @PutMapping("/sessions/{sessionId}/answers")
    public R<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        int questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        var req = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        sessionService.saveAnswer(req);
        return R.ok();
    }

    @Operation(summary = "提前交卷")
    @PostMapping("/sessions/{sessionId}/complete")
    public R<Void> completeInterview(@PathVariable String sessionId) {
        log.info("Complete interview early: sessionId={}", sessionId);
        sessionService.completeInterview(sessionId);
        return R.ok();
    }

    @Operation(summary = "获取面试报告（全部答完且评估完成后可用）")
    @GetMapping("/sessions/{sessionId}/report")
    public R<?> getReport(@PathVariable String sessionId) {
        InterviewReportDTO report = sessionService.getReport(sessionId);
        if (report == null) {
            return R.ok(Map.of("ready", false, "message", "评估进行中，请稍后刷新"));
        }
        return R.ok(report);
    }

    @Operation(summary = "查找未完成的面试（用于断点续传）")
    @GetMapping("/sessions/unfinished/{resumeId}")
    public R<InterviewSessionDTO> findUnfinished(@PathVariable Long resumeId) {
        return R.ok(sessionService.findUnfinishedOrThrow(resumeId));
    }

    @Operation(summary = "删除面试会话")
    @DeleteMapping("/sessions/{sessionId}")
    public R<Void> deleteInterview(@PathVariable String sessionId) {
        log.info("Delete interview: sessionId={}", sessionId);
        sessionService.deleteSession(sessionId);
        return R.ok();
    }
}
