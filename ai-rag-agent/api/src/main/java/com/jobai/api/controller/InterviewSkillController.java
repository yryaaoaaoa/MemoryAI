package com.jobai.api.controller;

import com.jobai.agent.interview.skill.InterviewSkillService;
import com.jobai.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "面试方向")
@RestController
@RequestMapping("/api/interview/skills")
@RequiredArgsConstructor
public class InterviewSkillController {

    private final InterviewSkillService skillService;

    @Operation(summary = "列出所有面试方向（含分类权重）")
    @GetMapping
    public R<List<InterviewSkillService.SkillDTO>> listSkills() {
        return R.ok(skillService.getAllSkills());
    }

    @Operation(summary = "获取单个面试方向详情")
    @GetMapping("/{id}")
    public R<InterviewSkillService.SkillDTO> getSkill(@PathVariable String id) {
        return R.ok(skillService.getSkill(id));
    }

    @Operation(summary = "解析JD为面试方向（用于自定义面试）")
    @PostMapping("/parse-jd")
    public R<List<InterviewSkillService.CategoryDTO>> parseJd(
            @Valid @RequestBody ParseJdRequest request) {
        return R.ok(skillService.parseJd(request.jdText()));
    }

    public record ParseJdRequest(
            @NotBlank(message = "JD 内容不能为空")
            String jdText) {
    }
}
