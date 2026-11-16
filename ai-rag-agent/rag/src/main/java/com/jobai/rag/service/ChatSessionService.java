package com.jobai.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.knowledge.entity.Resume;
import com.jobai.knowledge.mapper.ResumeMapper;
import com.jobai.rag.entity.ChatMessage;
import com.jobai.rag.entity.ChatSession;
import com.jobai.rag.mapper.ChatMessageMapper;
import com.jobai.rag.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int N_PLUS_1_LIMIT = 11;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatCompressionService chatCompressionService;
    private final ResumeMapper resumeMapper;

    public ChatSession createSession(List<Long> kbIds, Long userId) {
        ChatSession session = new ChatSession();
        if (kbIds != null && !kbIds.isEmpty()) {
            session.setKbIds(String.join(",", kbIds.stream().map(String::valueOf).toList()));
        }
        session.setUserId(userId);
        chatSessionMapper.insert(session);
        return session;
    }

    public List<ChatMessage> loadHistory(Long sessionId, Long userId) {
        assertOwnership(sessionId, userId);
        List<ChatMessage> recent = chatMessageMapper.findRecentBySessionId(sessionId, N_PLUS_1_LIMIT);
        if (recent.size() <= 1) return List.of();
        List<ChatMessage> history = recent.subList(1, recent.size());
        java.util.Collections.reverse(history);
        return history;
    }

    private ChatMessage appendMessage(Long sessionId, String role, String content, int order) {
        return appendMessage(sessionId, role, content, order, null);
    }

    private ChatMessage appendMessage(Long sessionId, String role, String content, int order, String metadata) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMessageOrder(order);
        msg.setMetadata(metadata);
        chatMessageMapper.insert(msg);
        return msg;
    }

    /**
     * 加载最近对话历史，使用滑动窗口 + Redis 摘要。
     * <p>
     * 将最近的 {@code WINDOW_ROUNDS} 完整轮次保留为完整消息，
     * 并将较早轮次的 LLM 压缩摘要注入系统提示中。
     */
    @Transactional
    public ChatContext beginUserTurn(Long sessionId, Long userId, String message) {
        // Lock before allocating message_order; reject foreign sessions before any write or history read.
        assertOwner(chatSessionMapper.selectForUpdate(sessionId), userId);
        Integer reserved = chatMessageMapper.selectMaxReservedOrder(sessionId);
        int userOrder = reserved == null ? 0 : reserved + 1;
        appendMessage(sessionId, "user", message, userOrder);
        ChatContext context = loadHistoryWithContext(sessionId);
        return new ChatContext(context.summariesText(), context.windowMessages(), context.resumeText(), userOrder + 1);
    }

    private ChatContext loadHistoryWithContext(Long sessionId) {
        int totalUserMsgs = chatMessageMapper.countUserMessages(sessionId);
        int effectiveUserMsgs = totalUserMsgs - 1; // exclude the just-saved current user message

        if (effectiveUserMsgs <= 0) {
            return new ChatContext("", List.of(), getResumeText(sessionId));
        }

        // 从 Redis 构建窗口之外轮次的摘要文本
        String summariesText = "";
        if (effectiveUserMsgs > ChatCompressionService.WINDOW_ROUNDS) {
            summariesText = chatCompressionService.buildSummariesText(sessionId);
        }

        // 加载窗口消息：最后 WINDOW_ROUNDS 条用户消息（及其之间的所有消息）
        int offset = Math.max(0, effectiveUserMsgs - ChatCompressionService.WINDOW_ROUNDS);
        int startOrder = 0;
        if (offset > 0) {
            Integer order = chatMessageMapper.findNthUserMessageOrder(sessionId, offset);
            startOrder = order != null ? order : 0;
        }

        List<ChatMessage> messages = chatMessageMapper.findByMinOrder(sessionId, startOrder);
        // 丢弃最后一条消息（刚刚保存的当前用户消息）
        if (!messages.isEmpty()) {
            messages = messages.subList(0, messages.size() - 1);
        }

        return new ChatContext(summariesText, messages, getResumeText(sessionId));
    }

    /**
     * 异步触发已滑出窗口轮次的压缩。
     * 在流完成后调用，以避免阻塞响应。
     */
    @Async("taskExecutor")
    public void compressIfNeeded(Long sessionId) {
        chatCompressionService.compressIfNeeded(sessionId);
    }

    /**
     * 获取指定会话关联的简历原始文本。
     */
    private String getResumeText(Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null || session.getResumeId() == null) return "";
        Resume resume = resumeMapper.selectById(session.getResumeId());
        // Ignore legacy associations created before ownership validation was introduced.
        if (resume == null || !java.util.Objects.equals(session.getUserId(), resume.getUserId())
                || resume.getRawText() == null) return "";
        return resume.getRawText();
    }

    public record ChatContext(String summariesText, List<ChatMessage> windowMessages, String resumeText,
                              Integer replyOrder) {

        public ChatContext(String summariesText, List<ChatMessage> windowMessages, String resumeText) {
            this(summariesText, windowMessages, resumeText, null);
        }

        public ChatContext(String summariesText, List<ChatMessage> windowMessages) {
            this(summariesText, windowMessages, "");
        }
    }

    /**
     * 设置会话关联的简历。
     */
    public void setSessionResume(Long sessionId, Long resumeId, Long userId) {
        assertOwnership(sessionId, userId);
        if (resumeId != null) {
            Resume resume = resumeMapper.selectById(resumeId);
            if (resume == null) throw new BusinessException(ErrorCode.NOT_FOUND, "简历不存在");
            if (!userId.equals(resume.getUserId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权关联此简历");
            }
        }
        // Explicit SET also permits clearing a previously associated resume.
        chatSessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId).eq(ChatSession::getUserId, userId)
                .set(ChatSession::getResumeId, resumeId));
    }

    /**
     * 在单个事务中批量持久化新消息（assistant + tool）。
     * 防止多轮流产生多条消息时的部分持久化问题。
     */
    @Transactional
    public void batchAppend(Long sessionId, Long userId, List<Map<String, Object>> messages) {
        assertOwner(chatSessionMapper.selectForUpdate(sessionId), userId);
        if (messages.isEmpty()) return;
        int order = nextOrder(sessionId);
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            String content = (String) msg.getOrDefault("content", "");
            String metadata = buildMetadata(msg);
            ChatMessage entity = new ChatMessage();
            entity.setSessionId(sessionId);
            entity.setRole(role);
            entity.setContent(content != null ? content : "");
            entity.setMessageOrder(order++);
            entity.setMetadata(metadata);
            chatMessageMapper.insert(entity);
        }
    }

    /** Save into the slot allocated with the user message, even if a later turn finished first. */
    @Transactional
    public Long appendReply(Long sessionId, Long userId, int replyOrder, Map<String, Object> reply) {
        assertOwner(chatSessionMapper.selectForUpdate(sessionId), userId);
        return appendMessage(sessionId, "assistant", (String) reply.getOrDefault("content", ""),
                replyOrder, buildMetadata(reply)).getId();
    }

    private static String buildMetadata(Map<String, Object> msg) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (msg.containsKey("response_status")) meta.put("response_status", msg.get("response_status"));
        if (msg.containsKey("error_message")) meta.put("error_message", msg.get("error_message"));
        if (msg.containsKey("tool_calls")) {
            meta.put("tool_calls", msg.get("tool_calls"));
        }
        if (msg.containsKey("tool_call_id")) {
            meta.put("tool_call_id", msg.get("tool_call_id"));
        }
        if (msg.containsKey("reasoning_content")) {
            meta.put("reasoning_content", msg.get("reasoning_content"));
        }
        if (meta.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            log.error("Failed to serialize metadata", e);
            return null;
        }
    }

    public int nextOrder(Long sessionId) {
        Integer max = chatMessageMapper.selectMaxOrder(sessionId);
        return max == null ? 0 : max + 1;
    }

    public List<ChatSession> listSessions(Long userId) {
        return chatSessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getUpdatedAt));
    }

    public List<ChatMessage> getMessages(Long sessionId, Long userId) {
        assertOwnership(sessionId, userId);
        return chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getMessageOrder));
    }

    public void updateTitle(Long sessionId, String title, Long userId) {
        assertOwnership(sessionId, userId);
        ChatSession s = chatSessionMapper.selectById(sessionId);
        if (s != null) {
            s.setTitle(title);
            chatSessionMapper.updateById(s);
        }
    }

    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        assertOwnership(sessionId, userId);
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId));
        chatSessionMapper.deleteById(sessionId);
    }

    /**
     * 原子性地累加会话的 token 使用量。
     */
    public void addTokenUsage(Long sessionId, int promptDelta, int completionDelta) {
        chatSessionMapper.addTokenUsage(sessionId, promptDelta, completionDelta);
    }

    private void assertOwnership(Long sessionId, Long userId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        assertOwner(session, userId);
    }

    private void assertOwner(ChatSession session, Long userId) {
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        if (userId == null || !userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问此会话");
        }
    }
}
