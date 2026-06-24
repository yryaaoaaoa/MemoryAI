package com.jobai.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.model.MasteryEntry;
import com.jobai.common.auth.AuthContext;
import com.jobai.knowledge.entity.QuizQuestion;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.knowledge.mapper.QuizQuestionMapper;
import com.jobai.knowledge.retrieval.VectorRetrievalService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Batch quiz generation for the independent quiz page.
 * Generates N questions by KB, backed by LLM + vector retrieval.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizService {

    private final LlmService llmService;
    private final VectorRetrievalService retrievalService;
    private final QuizQuestionMapper quizQuestionMapper;
    private final UserMasteryService userMasteryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            你是一个Java求职出题助手。根据提供的参考内容生成选择题。

            要求：
            1. 题目必须基于参考内容，不要编造
            2. 每题4个选项，只有1个正确答案
            3. 答案要准确，解析要详细
            4. 难度分布：约1/3简单，2/3中等
            5. 严格按JSON数组格式输出，不要markdown标记

            JSON格式：
            [{
              "question": "题目内容",
              "options": {"A":"...","B":"...","C":"...","D":"..."},
              "answer": "A",
              "explanation": "答案解析"
            }]
            """;

    /**
     * Generate quiz questions by knowledge base IDs.
     *
     * @param kbIds      knowledge base IDs
     * @param count      total questions to generate
     * @param difficulty easy / medium / mixed
     * @param topic      search keyword for vector retrieval (user-specified direction)
     * @return list of generated questions (persisted with IDs)
     */
    public List<QuizQuestionDTO> generateByKbs(List<Long> kbIds, int count, String difficulty, String topic) {
        Long userId = AuthContext.get();
        if (userId == null) {
            throw new IllegalStateException("Quiz generation requires an authenticated user");
        }
        // 未指定出题方向时，自动按最薄弱知识点出题
        String effectiveTopic = (topic != null && !topic.isBlank()) ? topic.strip() : null;
        if (effectiveTopic == null) {
            List<MasteryEntry> weak = userMasteryService.getWeakestTopics(userId, 1);
            if (weak.isEmpty()) {
                throw new IllegalArgumentException("暂无答题记录，请先填写出题方向");
            }
            effectiveTopic = weak.get(0).topic();
            log.info("[quiz] auto-selected weak topic: {} (mastery={})",
                    effectiveTopic, weak.get(0).mastery());
        }
        try {
            return doGenerate(userId, kbIds, count, difficulty, effectiveTopic);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[quiz] generation failed, using fallback: {}", e.getMessage());
            return buildFallbackQuestions(count);
        }
    }

    private List<QuizQuestionDTO> doGenerate(Long userId, List<Long> kbIds, int count, String difficulty, String topic) throws JsonProcessingException {
        String query = (topic != null && !topic.isBlank()) ? topic.strip() : "";
        StringBuilder referenceBlock = new StringBuilder();
        if (kbIds != null && !kbIds.isEmpty()) {
            for (Long kbId : kbIds) {
                var chunks = retrievalService.search(query, List.of(kbId), 5, 0.2, userId);
                for (int i = 0; i < chunks.size(); i++) {
                    var c = chunks.get(i);
                    referenceBlock.append("【知识库 ").append(kbId).append("】")
                            .append("[").append(i + 1).append("] ")
                            .append(c.content()).append("\n\n");
                }
            }
        } else {
            var chunks = retrievalService.search(query, null, 5, 0.2, userId);
            for (int i = 0; i < chunks.size(); i++) {
                referenceBlock.append("[").append(i + 1).append("] ")
                        .append(chunks.get(i).content()).append("\n\n");
            }
        }

        if (referenceBlock.isEmpty()) {
            throw new IllegalArgumentException("提供的方向和知识库不相关，没找到匹配内容，请调整方向或知识库后再试");
        }

        // 2. Build prompt
        String userMessage = String.format("""
                参考内容：
                %s

                请生成 %d 道选择题，难度：%s。""",
                referenceBlock, count, difficulty);

        // 3. Call LLM
        String result = llmService.chat(SYSTEM_PROMPT, userMessage);
        result = result.replaceAll("```[a-z]*\\s*", "").replace("```", "").strip();

        // 4. Parse JSON array
        List<Map<String, Object>> rawQuestions = objectMapper.readValue(result, List.class);

        // 5. Convert and persist
        List<QuizQuestionDTO> questions = new ArrayList<>();
        for (Map<String, Object> raw : rawQuestions) {
            try {
                QuizQuestion entity = new QuizQuestion();
                entity.setTopic(topic != null ? topic.strip() : "");
                entity.setQuestionText((String) raw.get("question"));
                entity.setQuestionType("choice");
                entity.setOptionsJson(objectMapper.writeValueAsString(raw.get("options")));
                entity.setAnswer((String) raw.get("answer"));
                entity.setExplanation((String) raw.get("explanation"));
                entity.setDocId(0L);
                entity.setSourceChunkId(0L);
                quizQuestionMapper.insert(entity);

                @SuppressWarnings("unchecked")
                Map<String, String> options = (Map<String, String>) raw.get("options");
                questions.add(QuizQuestionDTO.builder()
                        .id(entity.getId())
                        .question((String) raw.get("question"))
                        .options(options)
                        .answer((String) raw.get("answer"))
                        .explanation((String) raw.get("explanation"))
                        .build());
            } catch (Exception e) {
                log.warn("[quiz] failed to persist question: {}", e.getMessage());
            }
        }

        if (questions.isEmpty()) {
            return buildFallbackQuestions(count);
        }
        return questions;
    }

    /**
     * Fallback: return template questions in-memory when LLM is unavailable.
     * Not persisted — callers must handle null IDs.
     */
    private List<QuizQuestionDTO> buildFallbackQuestions(int count) {
        List<QuizQuestionDTO> list = new ArrayList<>();
        String[][] fallbacks = {
                {"Java 中 HashMap 的默认初始容量是？", "8", "16", "32", "64", "B", "HashMap 默认初始容量为 16，负载因子 0.75。"},
                {"以下哪个不是 Java 基本数据类型？", "int", "double", "String", "boolean", "C", "String 是引用类型，不是基本数据类型。"},
                {"Spring 中 @Autowired 默认按什么注入？", "名称", "类型", "构造器", "接口", "B", "@Autowired 默认按类型（byType）注入。"},
                {"MySQL InnoDB 默认的隔离级别是？", "READ UNCOMMITTED", "READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE", "C", "InnoDB 默认隔离级别为 REPEATABLE READ。"},
        };
        for (int i = 0; i < count; i++) {
            String[] fb = fallbacks[i % fallbacks.length];
            list.add(QuizQuestionDTO.builder()
                    .id(null)
                    .question(fb[0])
                    .options(Map.of("A", fb[1], "B", fb[2], "C", fb[3], "D", fb[4]))
                    .answer(fb[5])
                    .explanation(fb[6])
                    .build());
        }
        return list;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class QuizQuestionDTO {
        private Long id;
        private String question;
        private Map<String, String> options;
        private String answer;
        private String explanation;
    }
}
