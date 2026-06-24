package com.jobai.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.rag.entity.ChatMessage;
import com.jobai.rag.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将较早的对话轮次压缩为摘要并存储在 Redis 中。
 * <p>
 * 超出滑动窗口（N=10）的轮次以每批 4 轮分组，
 * 通过 LLM 压缩为简短摘要。每次请求时，
 * 摘要会与完整窗口消息一起注入系统提示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCompressionService {

    public static final int WINDOW_ROUNDS = 10;
    private static final int COMPRESS_BATCH = 4;
    private static final String SUMMARIES_KEY = "chat:summaries:%s";
    private static final long TTL_HOURS = 168; // 7 天

    private static final String COMPRESS_SYSTEM_PROMPT = """
            你是一个对话摘要助手。将以下多轮技术面试/学习对话压缩成简洁的中文摘要。

            要求：
            1. 保留关键的技术概念、用户的问题意图、助手的回答要点
            2. 每轮对话用一句话概括，保持轮次顺序
            3. 输出纯文本，不要 Markdown 格式，不要额外评论
            4. 如果对话涉及技术题目，记录题目和用户回答质量
            """;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final LlmService llmService;

    // ==================== 公开 API ====================

    /**
     * 构建格式化摘要文本，用于注入系统提示。
     * 如果不存在摘要则返回空字符串。
     */
    public String buildSummariesText(Long sessionId) {
        List<ChatSummary> summaries = getSummaries(sessionId);
        if (summaries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## 历史对话摘要\n");
        for (ChatSummary s : summaries) {
            sb.append("[").append(s.startRound()).append("-").append(s.endRound()).append("轮] ")
                    .append(s.summary()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 检查流完成后是否需要压缩，并以异步方式执行。
     */
    public void compressIfNeeded(Long sessionId) {
        try {
            int totalUserMsgs = chatMessageMapper.countUserMessages(sessionId);
            int slidOut = totalUserMsgs - WINDOW_ROUNDS;
            if (slidOut <= 0) return;

            int compressedRounds = getCompressedRoundCount(sessionId);
            int pending = slidOut - compressedRounds;
            if (pending <= 0) return;

            log.info("Compressing chat history: sessionId={}, slidOut={}, compressed={}, pending={}",
                    sessionId, slidOut, compressedRounds, pending);

            // 从第一个未压缩的滑出用户消息加载到窗口边界
            int startOrder = chatMessageMapper.findNthUserMessageOrder(sessionId, compressedRounds);
            int windowStartUserIdx = totalUserMsgs - WINDOW_ROUNDS;
            int endOrder = chatMessageMapper.findNthUserMessageOrder(sessionId, windowStartUserIdx);

            List<ChatMessage> messages = chatMessageMapper.findByOrderRange(
                    sessionId, startOrder, endOrder);

            if (messages.isEmpty()) return;

            // 构建按轮次分组的文本供 LLM 压缩
            String conversationText = buildConversationText(messages);

            // 调用 LLM
            String summary;
            try {
                summary = llmService.chat(COMPRESS_SYSTEM_PROMPT, conversationText);
                summary = summary.strip();
            } catch (Exception e) {
                log.warn("LLM compression failed for sessionId={}, fallback to simple summary", sessionId, e);
                summary = buildFallbackSummary(messages);
            }

            // 存储到 Redis — 追加到现有摘要
            int newBatchIndex = compressedRounds / COMPRESS_BATCH;
            int startRound = compressedRounds + 1;
            int endRound = slidOut;
            ChatSummary chatSummary = new ChatSummary(newBatchIndex, startRound, endRound, summary);
            stringRedisTemplate.opsForZSet().add(key(sessionId), objectMapper.writeValueAsString(chatSummary),
                    (double) newBatchIndex);
            stringRedisTemplate.expire(key(sessionId), Duration.ofHours(TTL_HOURS));

            log.info("Compressed rounds {}-{} for sessionId={} (batch={})",
                    startRound, endRound, sessionId, newBatchIndex);
        } catch (Exception e) {
            log.error("Failed to compress chat history for sessionId={}", sessionId, e);
        }
    }

    // ==================== 内部方法 ====================

    private List<ChatSummary> getSummaries(Long sessionId) {
        Set<String> jsonSet = stringRedisTemplate.opsForZSet().range(key(sessionId), 0, -1);
        if (jsonSet == null || jsonSet.isEmpty()) return List.of();
        return jsonSet.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, ChatSummary.class);
                    } catch (Exception e) {
                        log.warn("Failed to parse chat summary: {}", json, e);
                        return null;
                    }
                })
                .filter(s -> s != null)
                .sorted(Comparator.comparingInt(ChatSummary::batchIndex))
                .toList();
    }

    private int getCompressedRoundCount(Long sessionId) {
        List<ChatSummary> summaries = getSummaries(sessionId);
        if (summaries.isEmpty()) return 0;
        return summaries.stream()
                .mapToInt(ChatSummary::endRound)
                .max()
                .orElse(0);
    }

    private String buildConversationText(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int roundNum = 1;
        for (ChatMessage msg : messages) {
            String role = msg.getRole();
            String content = msg.getContent() != null ? msg.getContent() : "";
            if ("user".equals(role)) {
                sb.append("---\n");
                sb.append("第").append(roundNum).append("轮用户: ").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                sb.append("助手: ").append(content).append("\n");
            } else if ("tool".equals(role)) {
                // 在压缩摘要中跳过工具结果以节省 token
                continue;
            }
            roundNum++;
        }
        return sb.toString();
    }

    private String buildFallbackSummary(List<ChatMessage> messages) {
        String text = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(m -> m.getContent() != null ? m.getContent() : "")
                .limit(3)
                .collect(Collectors.joining("；"));
        return text.length() > 100 ? text.substring(0, 100) + "…" : text;
    }

    private String key(Long sessionId) {
        return String.format(SUMMARIES_KEY, sessionId);
    }

    // ==================== DTO ====================

    public record ChatSummary(int batchIndex, int startRound, int endRound, String summary) {}
}
