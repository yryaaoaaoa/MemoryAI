package com.jobai.agent.interview.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

public final class InterviewSkillProperties {

    private InterviewSkillProperties() {
    }

    /** Parsed from SKILL.md front matter. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillFrontMatter {
        private String name;
        private String description;
    }

    /** Parsed from skill.meta.yml. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillMeta {
        private String displayName;
        private DisplayDef display;
        private List<CategoryDef> categories = new ArrayList<>();
    }

    /** Runtime aggregate: front matter + meta yml. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillDefinition {
        private String name;
        private String description;
        private String persona;
        private String displayName;
        private DisplayDef display;
        private List<CategoryDef> categories = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DisplayDef {
        private String icon;
        private String gradient;
        private String iconBg;
        private String iconColor;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryDef {
        private String key;
        private String label;
        /** ALWAYS_ONE / CORE / NORMAL */
        private String priority;
        private String ref;
        private Boolean shared;
    }
}
