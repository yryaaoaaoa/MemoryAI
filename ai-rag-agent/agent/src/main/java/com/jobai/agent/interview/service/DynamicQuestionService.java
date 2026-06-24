package com.jobai.agent.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.interview.model.InterviewQuestionDTO;
import com.jobai.agent.interview.skill.InterviewSkillService;
import com.jobai.agent.interview.skill.InterviewSkillService.SkillDTO;
import com.jobai.knowledge.llm.LangChain4jConfig;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import static dev.langchain4j.model.chat.request.ResponseFormatType.JSON;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates ONE question/action at a time based on conversation history.
 * Uses tiered context: L2 (summary), L3 (recent window), L4 (reference).
 */
@Slf4j
@Service
public class DynamicQuestionService {

    private static final String TIERED_SYSTEM_PROMPT = """
            你是一位资深技术面试官。根据面试方向和对话记录，决定下一步行动。

            # 面试方向
            {skillDescription}

            # 答题难度
            {difficultyDescription}
            {contextSummarySection}
            {recentConversationSection}
            {referenceSection}
            {structuredResumeSection}

            # 分类进度
            {remainingCategories}

            {currentTopicInfo}
            {resumeSection}

            出题建议：
            1. 如果有简历，优先结合简历中的项目经历、技术栈来提问
            2. 如果简历中提到了某些技术但候选人回答较浅，适当深入追问
            3. 如果简历中有项目经验，可以问项目中的技术选型、问题排查等

            根据以上信息，决定下一步行动，严格按 JSON 格式输出：

            - "action": "follow_up" 继续深挖当前话题（候选人的回答还有可以追问的地方）
            - "action": "next_topic" 当前话题足够，切换到下一个分类
            - "action": "complete"   所有核心分类都已覆盖足够深度，结束面试

            如果是 follow_up 或 next_topic，还需要提供下一个问题：

            {
              "action": "follow_up",
              "question": "追问的具体问题",
              "type": "JAVA",
              "category": "Java",
              "topicSummary": "HashMap树化阈值调整",
              "why": "候选人提到了红黑树但没有深入退化条件"
            }

            注意：
            - 只输出 JSON，不要 markdown 标记
            - follow_up：type/category 与当前问题一致
            - next_topic：type/category 分配到的下一类
            - 候选人回答**质量高**、**深度够**→ 用 next_topic
            - 候选人回答**较浅**、**概念不清**→ 用 follow_up 追问
            - 所有分类都已覆盖且深度合理 → complete
            """;

    private static final String L2_COMPRESS_SYSTEM = """
            你是一位面试记录摘要助手。根据已有的面试摘要和新完成的对话，生成更新后的摘要。

            要求：
            1. 保留所有已考察方向的信息，包括候选人的掌握深度判断
            2. 新增的对话信息要融入对应方向，有冲突时以新信息为准
            3. 摘要长度控制在 200 字以内
            4. 输出纯文本，不要 markdown 格式

            格式示例：
            已考察方向：JVM(深入，GC算法和调优参数都准确)、MySQL(中等，MVCC理解正确但gap lock没提到)
            整体判断：候选人Java基础扎实，数据库方面需加强
            """;

    private static final String L2_COMPRESS_USER = """
            已有摘要：
            {oldSummary}

            新完成的对话（需要并入摘要的早期轮次）：
            {newConversations}

            请生成更新后的摘要。
            """;

    private static final Map<String, String> DIFFICULTY_MAP = Map.of(
            "junior", "校招/0-1年经验。考察基础概念和简单应用。",
            "mid", "1-3年经验。考察原理理解和实战经验。",
            "senior", "3年+经验。考察架构设计和深度调优。"
    );

    /** L3 window: max estimated tokens for recent conversation text. */
    private static final int L3_MAX_TOKENS = 3000;

    /** ResponseFormat for structured LLM output — guarantees valid JSON. */
    private static final ResponseFormat DECISION_RESPONSE_FORMAT = ResponseFormat.builder()
            .type(JSON)
            .jsonSchema(JsonSchema.builder()
                    .name("Decision")
                    .rootElement(JsonObjectSchema.builder()
                            .addStringProperty("action")
                            .addStringProperty("question")
                            .addStringProperty("type")
                            .addStringProperty("category")
                            .addStringProperty("topicSummary")
                            .addStringProperty("why")
                            .required("action")
                            .build())
                    .build())
            .build();

    private final LangChain4jConfig langChain4jConfig;
    private final InterviewSkillService skillService;
    private final ObjectMapper objectMapper;

    public DynamicQuestionService(LangChain4jConfig langChain4jConfig,
                                   InterviewSkillService skillService) {
        this.langChain4jConfig = langChain4jConfig;
        this.skillService = skillService;
        this.objectMapper = new ObjectMapper();
    }

    // ==================== Main Entry ====================

    /**
     * Decide the next action with tiered context (L2 summary + L3 window + L4 reference).
     *
     * @param skillId          interview skill id
     * @param difficulty       difficulty level
     * @param resumeId         resume id (may be null, for loading structured resume)
     * @param resumeText       resume text (may be empty)
     * @param questions        all questions so far (with userAnswer filled for answered ones)
     * @param categories       skill categories with remaining allocation
     * @param contextSummary   L2 summary of early rounds (may be null/blank)
     * @param referenceContent L4 reference content for current category (may be null/blank)
     * @return next action
     */
    public NextAction decide(String skillId, String difficulty,
                              Long resumeId,
                              String resumeText,
                              List<InterviewQuestionDTO> questions,
                              List<RemainingCategory> categories,
                              String contextSummary,
                              String referenceContent) {
        if (questions.isEmpty()) {
            return generateFirstQuestion(skillId, difficulty, resumeId, resumeText, categories);
        }

        SkillDTO skill = skillService.getSkill(skillId);
        String difficultyDesc = DIFFICULTY_MAP.getOrDefault(
                difficulty != null ? difficulty : "mid", DIFFICULTY_MAP.get("mid"));

        // Build L0 + L2 + L3 + L4 + state tiers
        String skillDesc = buildSkillDescription(skill);
        String contextSummarySection = buildContextSummarySection(contextSummary);
        String recentSection = buildRecentWindow(questions).formattedText();
        String referenceSection = buildReferenceSection(referenceContent);
        String structuredResumeSection = buildStructuredResumeSection(resumeId);
        String remainingCats = buildRemainingCategories(categories);
        String currentTopicInfo = buildCurrentTopicInfo(questions);
        String resumeSection = buildResumeSection(resumeText);

        String systemPrompt = TIERED_SYSTEM_PROMPT
                .replace("{skillDescription}", skillDesc)
                .replace("{difficultyDescription}", difficultyDesc)
                .replace("{contextSummarySection}", contextSummarySection)
                .replace("{recentConversationSection}", recentSection)
                .replace("{referenceSection}", referenceSection)
                .replace("{structuredResumeSection}", structuredResumeSection)
                .replace("{remainingCategories}", remainingCats)
                .replace("{currentTopicInfo}", currentTopicInfo)
                .replace("{resumeSection}", resumeSection);

        var chatModel = langChain4jConfig.createChatModel(0.5);
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt),
                        UserMessage.from("请输出下一步行动的 JSON。"))
                .build();
        String raw = chatModel.chat(chatRequest).aiMessage().text();

        raw = raw.replaceAll("```[a-z]*\\s*", "").replace("```", "").strip();

        try {
            Map<String, Object> map = objectMapper.readValue(raw, Map.class);
            String action = map.get("action") instanceof String a ? a : "next_topic";
            String question = map.get("question") instanceof String q ? q : null;
            String type = map.get("type") instanceof String t ? t : "GENERAL";
            String category = map.get("category") instanceof String c ? c : "综合";
            String summary = map.get("topicSummary") instanceof String s ? s : null;
            String why = map.get("why") instanceof String w ? w : null;

            if (question == null && !"complete".equals(action)) {
                action = "complete";
            }

            return new NextAction(action, question, type, category, summary, why);
        } catch (Exception e) {
            log.warn("Failed to parse next action from LLM, assuming complete: {}", e.getMessage());
            return new NextAction("complete", null, "GENERAL", "综合", null,
                    "LLM output parsing failed");
        }
    }

    // ==================== L2 Summary Compression ====================

    /**
     * Compress early conversation turns into an L2 summary, merging with existing summary.
     *
     * @param oldSummary   existing L2 summary (may be null/blank)
     * @param questions    ALL questions — those before the L3 window will be compressed
     * @param l3StartIndex index at which the L3 window begins
     * @return new L2 summary text, or oldSummary unchanged if there's nothing to compress
     */
    public String compressSummary(String oldSummary,
                                   List<InterviewQuestionDTO> questions,
                                   int l3StartIndex) {
        if (l3StartIndex <= 0 || questions.isEmpty()) {
            return oldSummary != null ? oldSummary : "";
        }

        // Build the text for early rounds that are being evicted from L3
        StringBuilder sb = new StringBuilder();
        int mainIndex = 0;
        for (int i = 0; i < l3StartIndex && i < questions.size(); i++) {
            InterviewQuestionDTO q = questions.get(i);
            if (q.userAnswer() == null || q.userAnswer().isBlank()) continue;
            if (!q.isFollowUp()) {
                mainIndex++;
                sb.append("\n--- 主问题 #").append(mainIndex).append(" ---\n");
            } else {
                sb.append("\n-[追问]--\n");
            }
            sb.append("[").append(q.category()).append("] ").append(q.question()).append("\n");
            sb.append("回答: ").append(q.userAnswer()).append("\n");
        }

        if (sb.isEmpty()) return oldSummary != null ? oldSummary : "";

        String userPrompt = L2_COMPRESS_USER
                .replace("{oldSummary}", oldSummary != null && !oldSummary.isBlank() ? oldSummary : "（无已有摘要）")
                .replace("{newConversations}", sb.toString());

        try {
            var chatModel = langChain4jConfig.createChatModel(0.3);
            String result = chatModel.chat(
                    SystemMessage.from(L2_COMPRESS_SYSTEM),
                    UserMessage.from(userPrompt)
            ).aiMessage().text();
            result = result.strip();
            log.info("L2 summary compressed: {} chars → {} chars",
                    sb.length(), result.length());
            return result;
        } catch (Exception e) {
            log.warn("L2 compression failed, keeping old summary: {}", e.getMessage());
            return oldSummary != null ? oldSummary : "";
        }
    }

    // ==================== Tiered Context Builders ====================

    /**
     * Build the L3 window: recent conversation turns within token budget.
     * Returns the formatted conversation text AND the index where early rounds begin
     * (for L2 compression trigger).
     *
     * @return pair: [formattedText, l3StartIndex]
     */
    public WindowResult buildRecentWindow(List<InterviewQuestionDTO> questions) {
        if (questions == null || questions.isEmpty()) {
            return new WindowResult("", 0);
        }

        // Walk backwards to fit within L3_MAX_TOKENS
        int totalTokens = 0;
        int windowStart = questions.size();
        List<String> lines = new ArrayList<>();

        int mainIndex = countMainQuestions(questions);

        for (int i = questions.size() - 1; i >= 0; i--) {
            InterviewQuestionDTO q = questions.get(i);
            String line = formatSingleQuestion(q, mainIndex);
            int tokens = estimateTokens(line);

            if (totalTokens + tokens > L3_MAX_TOKENS && !lines.isEmpty()) {
                break; // stop before this entry
            }

            totalTokens += tokens;
            lines.add(line);
            windowStart = i;
            if (!q.isFollowUp()) mainIndex--;
        }

        // Reverse back to chronological order
        Collections.reverse(lines);

        StringBuilder sb = new StringBuilder();
        sb.append("## 最近对话记录\n");
        for (String line : lines) {
            sb.append(line);
        }

        return new WindowResult(sb.toString(), windowStart);
    }

    public record WindowResult(String formattedText, int windowStartIndex) {
    }

    private String formatSingleQuestion(InterviewQuestionDTO q, int mainNumber) {
        StringBuilder sb = new StringBuilder();
        if (!q.isFollowUp()) {
            sb.append("\n--- 主问题 #").append(mainNumber).append(" ---\n");
        } else {
            sb.append("\n-[追问]--\n");
        }
        sb.append("[").append(q.category()).append("] ").append(q.question()).append("\n");
        if (q.userAnswer() != null && !q.userAnswer().isBlank()) {
            sb.append("候选人回答: ").append(q.userAnswer()).append("\n");
        }
        return sb.toString();
    }

    private int countMainQuestions(List<InterviewQuestionDTO> questions) {
        return (int) questions.stream().filter(q -> !q.isFollowUp()).count();
    }

    private String buildContextSummarySection(String contextSummary) {
        if (contextSummary == null || contextSummary.isBlank()) return "";
        return "\n# 已考察方向摘要\n" + contextSummary + "\n";
    }

    private String buildReferenceSection(String referenceContent) {
        if (referenceContent == null || referenceContent.isBlank()) return "";
        return "\n# 参考资料（当前分类）\n" + referenceContent + "\n";
    }

    private String buildResumeSection(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) return "\n# 候选人简历\n（本次面试无简历）\n";
        return "\n# 候选人简历\n" + truncate(resumeText, 1500) + "\n";
    }

    private String buildStructuredResumeSection(Long resumeId) {
        if (resumeId == null) return "";
        String content = skillService.buildStructuredResumeContext(resumeId);
        if (content.isBlank()) return "";
        return "\n" + content + "\n";
    }

    // ==================== First Question ====================

    private NextAction generateFirstQuestion(String skillId, String difficulty,
                                              Long resumeId,
                                              String resumeText,
                                              List<RemainingCategory> categories) {
        RemainingCategory first = categories.stream()
                .filter(c -> c.remaining > 0)
                .findFirst()
                .orElse(new RemainingCategory("GENERAL", "综合", "NORMAL", 1, 1));

        SkillDTO skill = skillService.getSkill(skillId);
        String difficultyDesc = DIFFICULTY_MAP.getOrDefault(
                difficulty != null ? difficulty : "mid", DIFFICULTY_MAP.get("mid"));
        String structuredResume = skillService.buildStructuredResumeContext(resumeId);

        StringBuilder promptSb = new StringBuilder();
        promptSb.append("你是一位专业的技术面试官。请出第一道技术面试题。\n\n");
        promptSb.append("# 面试方向\n").append(buildSkillDescription(skill)).append("\n");
        promptSb.append("# 难度\n").append(difficultyDesc).append("\n");
        promptSb.append("\n# 第一题应从以下分类中出\n");
        promptSb.append(first.key).append(" (").append(first.label).append(")\n");

        if (!structuredResume.isBlank()) {
            promptSb.append("\n").append(structuredResume).append("\n");
        } else if (resumeText != null && !resumeText.isBlank()) {
            promptSb.append("\n# 候选人简历\n").append(truncate(resumeText, 1500)).append("\n");
        } else {
            promptSb.append("\n# 本次面试无简历\n");
        }

        promptSb.append("\n出题建议：");
        promptSb.append("\n1. 如果有简历，优先结合简历中的项目经历、技术栈来提问");
        promptSb.append("\n2. 如果简历中提到了某些技术，从该技术入手提问");
        promptSb.append("\n3. 如果没有简历，从该方向的基础概念入手\n");

        promptSb.append("\n严格按 JSON 输出：\n");
        promptSb.append("{\n  \"question\": \"第一道面试题目\",\n");
        promptSb.append("  \"type\": \"").append(first.key).append("\",\n");
        promptSb.append("  \"category\": \"").append(first.label).append("\",\n");
        promptSb.append("  \"topicSummary\": \"知识点摘要\"\n}");

        var chatModel = langChain4jConfig.createChatModel(0.5);
        try {
            String raw = chatModel.chat(SystemMessage.from(promptSb.toString()), UserMessage.from("请出题。")).aiMessage().text();
            raw = raw.replaceAll("```[a-z]*\\s*", "").replace("```", "").strip();
            Map<String, Object> map = objectMapper.readValue(raw, Map.class);

            String question = map.get("question") instanceof String q ? q : null;
            String type = map.get("type") instanceof String t ? t : first.key;
            String category = map.get("category") instanceof String c ? c : first.label;
            String summary = map.get("topicSummary") instanceof String s ? s : null;

            if (question == null) {
                question = "请谈谈你在" + first.label + "方向的技术理解。";
            }

            return new NextAction("next_topic", question, type, category, summary, "第一题");
        } catch (Exception e) {
            log.warn("Failed to generate first question, using template: {}", e.getMessage());
            return new NextAction("next_topic",
                    "请谈谈你在" + first.label + "方向的技术理解。",
                    first.key, first.label, null, "template fallback");
        }
    }

    // ==================== Prompt Helpers ====================

    private String buildSkillDescription(SkillDTO skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("名称: ").append(skill.name()).append("\n");
        if (skill.description() != null && !skill.description().isBlank()) {
            sb.append("描述: ").append(skill.description()).append("\n");
        }
        if (skill.persona() != null && !skill.persona().isBlank()) {
            sb.append("\n面试官角色/风格约束:\n").append(skill.persona());
        }
        return sb.toString();
    }

    private String buildRemainingCategories(List<RemainingCategory> categories) {
        StringBuilder sb = new StringBuilder();
        for (RemainingCategory c : categories) {
            sb.append("- ").append(c.key).append(" (").append(c.label).append(")")
                    .append(" 优先级=").append(c.priority)
                    .append(" 计划=").append(c.planned).append(" 已完成=")
                    .append(c.planned - c.remaining)
                    .append("\n");
        }
        return sb.toString();
    }

    private String buildCurrentTopicInfo(List<InterviewQuestionDTO> questions) {
        for (int i = questions.size() - 1; i >= 0; i--) {
            InterviewQuestionDTO q = questions.get(i);
            if (!q.isFollowUp() && q.userAnswer() != null && !q.userAnswer().isBlank()) {
                return "当前话题: [" + q.category() + "] " + q.question()
                        + "\n候选人回答: " + q.userAnswer();
            }
        }
        return "当前话题: 尚未开始考察。";
    }

    // ==================== Utilities ====================

    /** Rough token estimation: ~1.5 chars per token for mixed Chinese/English. */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // Chinese chars count more: roughly 1 token per 1.5 chars
        return (int) Math.ceil(text.length() / 1.5);
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "\n...(truncated)" : text;
    }

    // ==================== DTOs ====================

    public record NextAction(String action, String question, String type,
                             String category, String topicSummary, String why) {
        public boolean isFollowUp() { return "follow_up".equals(action); }
        public boolean isNextTopic() { return "next_topic".equals(action); }
        public boolean isComplete() { return "complete".equals(action); }
    }

    public record RemainingCategory(String key, String label, String priority,
                                    int planned, int remaining) {
    }
}
