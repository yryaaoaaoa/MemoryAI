package com.jobai.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.interview.skill.InterviewSkillService;
import com.jobai.agent.service.UserMasteryService;
import com.jobai.agent.service.WrongQuestionService;
import com.jobai.common.R;
import com.jobai.common.auth.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "用户画像")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserProfileController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final UserMasteryService userMasteryService;
    private final WrongQuestionService wrongQuestionService;
    private final InterviewSkillService skillService;

    @Operation(summary = "获取用户画像（技能画像 + 刷题统计）")
    @GetMapping("/profile")
    public R<UserProfileVO> getProfile(@CurrentUserId Long userId) {
        // 刷题统计
        long wrongQuestionCount = wrongQuestionService.getWrongQuestionCount(userId);

        UserProfileVO profile = new UserProfileVO(
                userMasteryService.getTechStack(userId),
                false,
                userMasteryService.getTotalQuizzes(userId),
                userMasteryService.getCorrectRate(userId),
                userMasteryService.getMaxStreak(userId),
                wrongQuestionCount,
                loadSkillProfiles(userId)
        );

        return R.ok(profile);
    }

    private List<SkillProfileVO> loadSkillProfiles(Long userId) {
        try {
            return jdbc.query(
                    "SELECT skill_id, profile_json, interview_count FROM user_skill_profile WHERE user_id = ? ORDER BY updated_at DESC",
                    (rs, rowNum) -> {
                        String skillId = rs.getString("skill_id");
                        String profileJson = rs.getString("profile_json");
                        int interviewCount = rs.getInt("interview_count");

                        JsonNode profileData = null;
                        try {
                            profileData = objectMapper.readTree(profileJson);
                        } catch (Exception ignored) {}

                        // 获取 skill 展示信息
                        String skillName = skillId;
                        String icon = "Star";
                        String gradient = "linear-gradient(135deg, #38bdf8, #818cf8)";
                        try {
                            var skill = skillService.getSkill(skillId);
                            skillName = skill.name();
                            if (skill.display() != null) {
                                icon = skill.display().icon() != null ? skill.display().icon() : icon;
                                gradient = skill.display().gradient() != null ? skill.display().gradient() : gradient;
                            }
                        } catch (Exception ignored) {}

                        return new SkillProfileVO(skillId, skillName, profileData, interviewCount, icon, gradient);
                    },
                    userId);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ========== DTOs ==========

    public record UserProfileVO(
            List<String> techStack,
            boolean hasResume,
            int totalQuizzes,
            int correctRate,
            int streak,
            long wrongQuestionCount,
            List<SkillProfileVO> skillProfiles
    ) {}

    @Data
    public static class SkillProfileVO {
        private String skillId;
        private String skillName;
        private JsonNode profileJson;
        private int interviewCount;
        private String icon;
        private String gradient;

        public SkillProfileVO(String skillId, String skillName, JsonNode profileJson,
                              int interviewCount, String icon, String gradient) {
            this.skillId = skillId;
            this.skillName = skillName;
            this.profileJson = profileJson;
            this.interviewCount = interviewCount;
            this.icon = icon;
            this.gradient = gradient;
        }
    }
}
