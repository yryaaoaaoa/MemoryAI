package com.jobai.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.model.MasteryEntry;
import com.jobai.common.auth.AuthContext;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.knowledge.entity.KnowledgeBase;
import com.jobai.knowledge.mapper.ChunkMapper;
import com.jobai.knowledge.service.KnowledgeBaseService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Batch quiz generation for the independent quiz page.
 * Generates N questions by KB, backed by LLM + vector retrieval.
 */
@Slf4j
@Service
public class QuizService {

    private final LlmService llmService;
    private final VectorRetrievalService retrievalService;
    private final QuizQuestionMapper quizQuestionMapper;
    private final UserMasteryService userMasteryService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChunkMapper chunkMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QuizService(LlmService llmService,
                       @org.springframework.lang.Nullable VectorRetrievalService retrievalService,
                       QuizQuestionMapper quizQuestionMapper, UserMasteryService userMasteryService,
                       @org.springframework.lang.Nullable KnowledgeBaseService knowledgeBaseService,
                       ChunkMapper chunkMapper) {
        this.llmService = llmService;
        this.retrievalService = retrievalService;
        this.quizQuestionMapper = quizQuestionMapper;
        this.userMasteryService = userMasteryService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.chunkMapper = chunkMapper;
    }

    private static final String SYSTEM_PROMPT = """
            你是一个技术知识出题助手。根据提供的参考内容生成选择题。

            要求：
            1. 题目必须基于参考内容，不要编造
            2. 每题4个选项，只有1个正确答案
            3. 答案要准确，解析要详细
            4. 遵循用户指定的难度；混合难度时兼顾基础理解与实际应用
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
     * @param difficulty easy / medium / hard / mixed
     * @param topic      search keyword for vector retrieval (user-specified direction)
     * @return list of generated questions (persisted with IDs)
     */
    public List<QuizQuestionDTO> generateByKbs(List<Long> kbIds, int count, String difficulty, String topic) {
        if (count < 1 || count > 20) throw new IllegalArgumentException("题目数量应为 1 至 20 道");
        Long userId = AuthContext.get();
        if (userId == null) {
            throw new IllegalStateException("Quiz generation requires an authenticated user");
        }
        if (!kbIds.isEmpty() && knowledgeBaseService == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "知识库检索未启用，请填写出题方向进行练习");
        }
        // Validate every selected KB before reading content or calling a model.
        List<KnowledgeBase> knowledgeBases = kbIds.stream().distinct()
                .map(kbId -> knowledgeBaseService.getById(kbId, userId)).toList();
        boolean automatic = topic == null || topic.isBlank();
        String effectiveTopic = automatic ? null : topic.strip();
        if (automatic) {
            List<MasteryEntry> weak = userMasteryService.getWeakestTopics(userId, 1);
            if (!weak.isEmpty()) effectiveTopic = weak.get(0).topic();
        }
        if (knowledgeBases.isEmpty() && (effectiveTopic == null || effectiveTopic.isBlank())) {
            throw new IllegalArgumentException("暂无答题记录，请先填写出题方向");
        }
        if (effectiveTopic != null && effectiveTopic.length() > 100) {
            throw new IllegalArgumentException("出题方向不能超过100个字符");
        }
        try {
            return doGenerate(userId, knowledgeBases, count, difficulty, effectiveTopic, automatic);
        } catch (LlmService.OutputTruncatedException e) {
            log.warn("[quiz] model output truncated, requestedCount={}", count);
            throw new BusinessException(ErrorCode.QUIZ_GENERATE_FAILED,
                    "题目内容超出生成长度限制，请减少题量后重试");
        } catch (JsonProcessingException e) {
            log.warn("[quiz] invalid question JSON: type={}, requestedCount={}", e.getClass().getSimpleName(), count);
            throw new BusinessException(ErrorCode.QUIZ_GENERATE_FAILED,
                    "模型返回的题目格式不完整，请重试");
        } catch (IllegalArgumentException | BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[quiz] generation failed: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.QUIZ_GENERATE_FAILED,
                    "生成或保存题目失败，请稍后重试");
        }
    }

    private List<QuizQuestionDTO> doGenerate(Long userId, List<KnowledgeBase> knowledgeBases,
                                            int count, String difficulty, String topic,
                                            boolean automatic) throws JsonProcessingException {
        StringBuilder referenceBlock = new StringBuilder();
        if (topic != null) {
            for (KnowledgeBase kb : knowledgeBases) {
                var chunks = retrievalService.search(topic, List.of(kb.getId()), 5, 0.2, userId);
                for (var chunk : chunks) {
                    referenceBlock.append("【知识库 ").append(kb.getName()).append("】")
                            .append(chunk.content()).append("\n\n");
                }
            }
        }

        // A new user, or a weak topic unrelated to these KBs, can still practise their selected material.
        if (automatic && !knowledgeBases.isEmpty() && referenceBlock.isEmpty()) {
            String sampledTopic = null;
            for (KnowledgeBase kb : knowledgeBases) {
                for (var chunk : chunkMapper.selectReadySamples(kb.getId(), 5)) {
                    if (chunk.getContent() == null || chunk.getContent().isBlank()) continue;
                    if (sampledTopic == null) {
                        sampledTopic = chunk.getHeadingPath() != null && !chunk.getHeadingPath().isBlank()
                                ? chunk.getHeadingPath() : kb.getName();
                    }
                    referenceBlock.append("【知识库 ").append(kb.getName()).append("】")
                            .append(chunk.getHeadingPath() == null ? "" : chunk.getHeadingPath()).append("\n")
                            .append(chunk.getContent(), 0, Math.min(chunk.getContent().length(), 2000))
                            .append("\n\n");
                }
            }
            // The topic column is VARCHAR(100); do not label sampled material with an unrelated weak topic.
            topic = sampledTopic == null ? "综合练习" : sampledTopic.substring(0, Math.min(sampledTopic.length(), 100));
        }
        if (!knowledgeBases.isEmpty() && referenceBlock.isEmpty()) {
            throw new IllegalArgumentException(automatic
                    ? "所选知识库暂无可用内容，请先上传文档并等待解析完成"
                    : "提供的方向和知识库不相关，没找到匹配内容，请调整方向或知识库后再试");
        }
        String difficultyLabel = switch (difficulty) {
            case "easy" -> "简单，侧重基础概念和直接应用";
            case "medium" -> "中等，需要理解机制并进行分析";
            case "hard" -> "困难，侧重复杂场景、边界条件和综合推理";
            default -> "混合，覆盖简单、中等和困难";
        };

        // 2. Build prompt
        String userMessage = String.format("""
                参考内容：
                %s

                请生成 %d 道选择题，难度：%s。""",
                referenceBlock, count, difficultyLabel);
        if (knowledgeBases.isEmpty()) {
            userMessage = String.format("出题方向：%s\n请生成 %d 道选择题，难度：%s。", topic, count, difficultyLabel);
        }

        // 3. Call LLM
        int outputBudget = Math.max(4096, 2048 + count * 1024);
        String systemPrompt = knowledgeBases.isEmpty()
                ? SYSTEM_PROMPT.replace("根据提供的参考内容生成选择题", "根据指定的技术方向生成选择题")
                        .replace("题目必须基于参考内容，不要编造", "题目应考察准确、通用的技术知识，不要编造特定版本或产品行为")
                : SYSTEM_PROMPT;
        String result = llmService.chat(systemPrompt, userMessage, outputBudget).strip();
        // Only remove an enclosing Markdown fence; code fences inside questions are content.
        if (result.startsWith("```") && result.endsWith("```") && result.indexOf('\n') >= 0) {
            result = result.substring(result.indexOf('\n') + 1, result.length() - 3).strip();
        }

        // 4. Validate the complete model response before saving any question.
        List<GeneratedQuestion> rawQuestions = objectMapper.readerForListOf(GeneratedQuestion.class).readValue(result);
        if (rawQuestions == null || rawQuestions.size() != count) {
            throw new BusinessException(ErrorCode.QUIZ_GENERATE_FAILED, "模型未返回指定数量的完整题目，请重试");
        }
        for (GeneratedQuestion raw : rawQuestions) {
            if (raw == null || raw.question() == null || raw.question().isBlank()
                    || raw.explanation() == null || raw.explanation().isBlank()
                    || raw.options() == null || !raw.options().keySet().equals(Set.of("A", "B", "C", "D"))
                    || raw.options().values().stream().anyMatch(value -> value == null || value.isBlank())
                    || raw.answer() == null || !raw.options().containsKey(raw.answer().strip().toUpperCase(Locale.ROOT))) {
                throw new BusinessException(ErrorCode.QUIZ_GENERATE_FAILED, "模型返回的题目缺少有效题干、选项或答案，请重试");
            }
        }

        // 5. Convert and persist
        List<QuizQuestionDTO> questions = new ArrayList<>();
        for (GeneratedQuestion raw : rawQuestions) {
            QuizQuestion entity = new QuizQuestion();
            entity.setUserId(userId);
            entity.setKbId(knowledgeBases.size() == 1 ? knowledgeBases.get(0).getId() : 0L);
            entity.setTopic(topic != null ? topic.strip() : "");
            entity.setQuestionText(raw.question());
            entity.setQuestionType("choice");
            entity.setOptionsJson(objectMapper.writeValueAsString(raw.options()));
            entity.setAnswer(raw.answer().strip().toUpperCase(Locale.ROOT));
            entity.setExplanation(raw.explanation());
            entity.setDocId(0L);
            entity.setSourceChunkId(0L);
            quizQuestionMapper.insert(entity);

            questions.add(QuizQuestionDTO.builder()
                    .id(entity.getId())
                    .question(raw.question())
                    .options(raw.options())
                    .answer(entity.getAnswer())
                    .explanation(raw.explanation())
                    .build());
        }

        return questions;
    }

    private record GeneratedQuestion(String question, Map<String, String> options, String answer, String explanation) {}

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
