package com.jobai.agent.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.interview.model.InterviewQuestionDTO;
import com.jobai.agent.interview.skill.InterviewSkillService;
import com.jobai.agent.interview.skill.InterviewSkillService.SkillDTO;
import com.jobai.common.evaluation.EvaluationReport;
import com.jobai.knowledge.llm.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 面试结束后异步生成用户画像（按 skill 维度）。
 * LLM 对照 skill 的分类定义和面试评分数据，生成结构化语义画像，
 * 替代旧的 user_mastery 数值方案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileGenerationService {

    private static final int MAX_PROFILE_CHARS = 2000;

    private final JdbcTemplate jdbc;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final InterviewSkillService skillService;

    @Async("taskExecutor")
    public void generateOrUpdateProfileAsync(Long userId, String skillId,
                                              EvaluationReport report,
                                              List<InterviewQuestionDTO> questions) {
        try {
            SkillDTO skill = skillService.getSkill(skillId);

            // 读旧画像
            String oldProfileJson = null;
            try {
                oldProfileJson = jdbc.queryForObject(
                        "SELECT profile_json FROM user_skill_profile WHERE user_id = ? AND skill_id = ?",
                        String.class, userId, skillId);
            } catch (Exception ignored) {}

            // 构建 LLM prompt
            String systemPrompt = buildSystemPrompt(skill);
            String userPrompt = buildUserPrompt(skill, oldProfileJson, report, questions);

            String raw = llmService.chat(systemPrompt, userPrompt);
            raw = raw.replaceAll("```[a-z]*\\s*", "").replace("```", "").strip();

            // 验证是合法 JSON
            objectMapper.readTree(raw);

            // 落库
            jdbc.update(
                    "INSERT INTO user_skill_profile (user_id, skill_id, profile_json, interview_count) VALUES (?, ?, ?, 1) " +
                    "ON DUPLICATE KEY UPDATE profile_json = VALUES(profile_json), interview_count = interview_count + 1, updated_at = NOW()",
                    userId, skillId, raw);

            log.info("Profile generated: userId={}, skillId={}", userId, skillId);
        } catch (Exception e) {
            log.warn("Profile generation failed for userId={}, skillId={}: {}",
                    userId, skillId, e.getMessage());
        }
    }

    private String buildSystemPrompt(SkillDTO skill) {
        String categoryDefs = skill.categories().stream()
                .map(c -> String.format("  - %s (%s, 权重 %s)", c.key(), c.label(), c.priority()))
                .collect(Collectors.joining("\n"));

        return String.format("""
                你是一个面试教练。根据用户的面试表现，更新其在某个技术方向的画像。

                ## 画像 JSON 格式
                {
                  "summary": "一句话总体评价（50字以内）",
                  "categories": [
                    {
                      "category": "分类ID",
                      "level": "strong | adequate | weak | untouched",
                      "text": "具体评估（60字以内，要指出具体掌握了什么或缺什么，不要空话）"
                    }
                  ],
                  "nextAdvice": "下一步学习建议（50字以内）"
                }

                ## level 的含义
                - strong:    多次（>=2次）表现优秀，可减少此类出题
                - adequate:  基本掌握但仍需巩固
                - weak:      覆盖过但表现不理想，需加强
                - untouched: 从未覆盖过（由系统自动标记，LLM 不需要主动设这个值）

                ## 这个 skill 的分类定义
                %s

                ## 规则
                1. 只对本次面试实际覆盖到的分类写 text，未覆盖的保留旧数据或忽略
                2. 如果该分类有旧画像，结合新旧数据做判断，不要丢失已有信息
                3. 每条 text 要有实质内容，写"对XX理解深入"或"对XX缺乏了解"这种具体的话
                4. 输出纯 JSON，不要 markdown 标记
                """, categoryDefs);
    }

    private String buildUserPrompt(SkillDTO skill, String oldProfileJson,
                                    EvaluationReport report,
                                    List<InterviewQuestionDTO> questions) {
        String oldSection = oldProfileJson != null
                ? "## 旧画像\n" + oldProfileJson
                : "## 旧画像\n（无，首次生成）";

        String qaDetails = report.questionDetails().stream()
                .map(q -> String.format("  [%s] %s → 得分: %d, 反馈: %s",
                        q.category(), truncate(q.question(), 40), q.score(),
                        q.feedback() != null ? truncate(q.feedback(), 50) : "无"))
                .collect(Collectors.joining("\n"));

        return String.format("""
                %s

                ## 本次面试数据（skill: %s）
                总分：%d
                优势：%s
                改进：%s

                各题详情：
                %s

                请生成或更新该用户的 skill 画像。
                """,
                oldSection,
                skill.name(),
                report.overallScore(),
                String.join("; ", report.strengths()),
                String.join("; ", report.improvements()),
                qaDetails);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
