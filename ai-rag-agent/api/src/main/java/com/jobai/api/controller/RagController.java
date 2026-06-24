package com.jobai.api.controller;

import com.jobai.agent.service.QuizService;
import com.jobai.api.service.StreamingChatService;
import com.jobai.common.ErrorCode;
import com.jobai.common.R;
import com.jobai.common.auth.AuthContext;
import com.jobai.common.auth.CurrentUserId;
import com.jobai.rag.entity.ChatMessage;
import com.jobai.rag.entity.ChatSession;
import com.jobai.rag.service.ChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "AI 对话")
@RestController
@RequiredArgsConstructor
public class RagController {

    private final ChatSessionService chatSessionService;
    private final QuizService quizService;
    private final StreamingChatService streamingChatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 会话 ====================

    @Operation(summary = "创建会话")
    @PostMapping("/api/chat/session")
    public R<Long> createSession(@CurrentUserId Long userId,
                                 @RequestBody(required = false) List<Long> kbIds) {
        ChatSession session = chatSessionService.createSession(kbIds, userId);
        return R.ok(session.getId());
    }

    @Operation(summary = "会话列表")
    @GetMapping("/api/sessions")
    public R<List<ChatSession>> listSessions(@CurrentUserId Long userId) {
        return R.ok(chatSessionService.listSessions(userId));
    }

    @Operation(summary = "会话消息")
    @GetMapping("/api/sessions/{id}/messages")
    public R<List<ChatMessage>> getMessages(@CurrentUserId Long userId, @PathVariable Long id) {
        return R.ok(chatSessionService.getMessages(id, userId));
    }

    @Data
    public static class CreateSessionBody {
        private String title;
    }

    @Operation(summary = "创建会话 (带标题)")
    @PostMapping("/api/sessions")
    public R<ChatSession> createSessionTitled(@CurrentUserId Long userId,
                                              @RequestBody(required = false) CreateSessionBody body) {
        ChatSession session = chatSessionService.createSession(null, userId);
        if (body != null && body.getTitle() != null) {
            chatSessionService.updateTitle(session.getId(), body.getTitle());
        }
        return R.ok(session);
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/api/sessions/{id}")
    public R<Void> deleteSession(@CurrentUserId Long userId, @PathVariable Long id) {
        chatSessionService.deleteSession(id, userId);
        return R.ok();
    }

    @Operation(summary = "关联简历到会话")
    @PutMapping("/api/sessions/{id}/resume")
    public R<Void> setSessionResume(@CurrentUserId Long userId,
                                     @PathVariable Long id,
                                     @RequestBody Map<String, Object> body) {
        Long resumeId = body.get("resumeId") != null ? Long.valueOf(body.get("resumeId").toString()) : null;
        chatSessionService.setSessionResume(id, resumeId, userId);
        return R.ok();
    }


    @Operation(summary = "Agent SSE 流式对话")
    @PostMapping("/api/agent/chat/stream")
    public SseEmitter agentChatStream(@CurrentUserId Long userId,
                                       @RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(300_000L);
        String message = (String) body.getOrDefault("message", "");
        Object sid = body.get("sessionId");

        Long sessionId = null;
        List<Map<String, Object>> history = new ArrayList<>();
        String summariesText = "";
        String resumeText = "";
        if (sid != null) {
            sessionId = Long.valueOf(sid.toString());
            chatSessionService.appendMessage(sessionId, "user", message,
                    chatSessionService.nextOrder(sessionId));
            ChatSessionService.ChatContext ctx = chatSessionService.loadHistoryWithContext(sessionId);
            for (ChatMessage msg : ctx.windowMessages()) {
                history.add(buildHistoryMessage(msg));
            }
            summariesText = ctx.summariesText();
            resumeText = ctx.resumeText();
        }

        streamingChatService.streamChat(message, history, emitter, sessionId, userId, summariesText, resumeText);
        return emitter;
    }

    // ==================== 测验 ====================

    @Data
    public static class QuizGenerateRequest {
        private List<Long> kbIds;
        private int count = 10;
        private String difficulty = "mixed";
        private String topic;
    }

    @Operation(summary = "批量出题（按知识库）")
    @PostMapping("/api/quiz/generate")
    public R<List<QuizService.QuizQuestionDTO>> generateQuiz(
            @CurrentUserId Long userId,
            @RequestBody QuizGenerateRequest req) {
        if (req.getKbIds() == null || req.getKbIds().isEmpty()) {
            return R.fail("请选择至少一个知识库");
        }
        AuthContext.set(userId, "user");
        try {
            return R.ok(quizService.generateByKbs(req.getKbIds(), req.getCount(), req.getDifficulty(), req.getTopic()));
        } catch (IllegalArgumentException e) {
            return R.fail(ErrorCode.QUIZ_GENERATE_FAILED.getCode(), e.getMessage());
        } finally {
            AuthContext.clear();
        }
    }

    private Map<String, Object> buildHistoryMessage(ChatMessage msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", msg.getRole());
        result.put("content", msg.getContent());

        if (msg.getMetadata() != null && !msg.getMetadata().isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = objectMapper.readValue(msg.getMetadata(), Map.class);
                if (meta.containsKey("tool_calls")) {
                    result.put("tool_calls", meta.get("tool_calls"));
                }
                if (meta.containsKey("tool_call_id")) {
                    result.put("tool_call_id", meta.get("tool_call_id"));
                }
                if (meta.containsKey("reasoning_content")) {
                    result.put("reasoning_content", meta.get("reasoning_content"));
                }
            } catch (Exception e) {
                log.warn("Failed to parse message metadata for msg id={}", msg.getId(), e);
            }
        }

        return result;
    }

}
