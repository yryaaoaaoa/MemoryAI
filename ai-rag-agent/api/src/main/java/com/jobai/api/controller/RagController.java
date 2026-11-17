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
import com.jobai.rag.service.LearningNoteService;
import com.jobai.rag.service.LearningTreeService;
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
    private final LearningNoteService learningNoteService;
    private final LearningTreeService learningTreeService;
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

    @GetMapping("/api/sessions/{id}/learning-tree")
    public R<LearningTreeService.TreeState> learningTree(@CurrentUserId Long userId, @PathVariable Long id) {
        return R.ok(learningTreeService.state(id, userId));
    }

    @PostMapping("/api/sessions/{id}/learning-tree/retry")
    public R<LearningTreeService.TreeState> retryLearningTree(@CurrentUserId Long userId, @PathVariable Long id) {
        return R.ok(learningTreeService.retry(id, userId));
    }

    @GetMapping("/api/sessions/{id}/learning-tree/nodes/{nodeId}")
    public R<LearningTreeService.NodeDetail> learningTreeNode(@CurrentUserId Long userId, @PathVariable Long id,
                                                            @PathVariable String nodeId) {
        return R.ok(learningTreeService.nodeDetail(id, userId, nodeId));
    }

    @PostMapping("/api/sessions/{id}/learning-tree/nodes/{nodeId}/suggestions")
    public R<LearningTreeService.Suggestions> learningTreeSuggestions(@CurrentUserId Long userId, @PathVariable Long id,
                                                                     @PathVariable String nodeId) {
        return R.ok(learningTreeService.suggestions(id, userId, nodeId));
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
            chatSessionService.updateTitle(session.getId(), body.getTitle(), userId);
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
        Object knowledgeBaseOption = body.get("useKnowledgeBase");
        if (body.containsKey("useKnowledgeBase") && !(knowledgeBaseOption instanceof Boolean))
            throw new IllegalArgumentException("useKnowledgeBase 必须为布尔值 true 或 false");
        boolean useKnowledgeBase = Boolean.TRUE.equals(knowledgeBaseOption);
        Object organizeOption = body.get("organizeNotes");
        if (body.containsKey("organizeNotes") && !(organizeOption instanceof Boolean))
            throw new IllegalArgumentException("organizeNotes 必须为布尔值 true 或 false");
        boolean organizeNotes = Boolean.TRUE.equals(organizeOption);
        Object nodeOption = body.get("noteNodeId");
        if (nodeOption != null && (!(nodeOption instanceof String node) || node.isBlank() || node.length() > 128))
            throw new IllegalArgumentException("知识点编号无效");
        String noteNodeId = (String) nodeOption;
        if (useKnowledgeBase) streamingChatService.requireKnowledgeBaseAvailable();
        SseEmitter emitter = new SseEmitter(300_000L);
        String message = (String) body.getOrDefault("message", "");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("消息不能为空");
        Object sid = body.get("sessionId");

        Long sessionId = null;
        List<Map<String, Object>> history = new ArrayList<>();
        String summariesText = "";
        String resumeText = "";
        Integer replyOrder = null;
        if (sid != null) {
            sessionId = Long.valueOf(sid.toString());
            String noteContext = noteNodeId == null ? learningNoteService.contextForSession(sessionId, userId)
                    : learningTreeService.contextForNode(sessionId, userId, noteNodeId);
            ChatSessionService.ChatContext ctx = chatSessionService.beginUserTurn(sessionId, userId, message);
            replyOrder = ctx.replyOrder();
            for (ChatMessage msg : ctx.windowMessages()) {
                history.add(buildHistoryMessage(msg));
            }
            summariesText = ctx.summariesText();
            summariesText += noteContext;
            resumeText = ctx.resumeText();
        }

        var response = streamingChatService.streamChat(message, history, emitter, sessionId, userId, summariesText, resumeText, replyOrder, useKnowledgeBase);
        if (organizeNotes && sessionId != null) {
            Long currentSession = sessionId;
            int currentReply = replyOrder;
            response.thenRun(() -> learningTreeService.afterReply(currentSession, userId, currentReply, noteNodeId));
        }
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
        AuthContext.set(userId, "user");
        try {
            return R.ok(quizService.generateByKbs(req.getKbIds() == null ? List.of() : req.getKbIds(), req.getCount(), req.getDifficulty(), req.getTopic()));
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
