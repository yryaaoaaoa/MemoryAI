package com.jobai.agent.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.interview.skill.InterviewSkillService;
import com.jobai.agent.interview.skill.InterviewSkillService.SkillDTO;
import com.jobai.common.evaluation.EvaluationReport;
import com.jobai.knowledge.llm.LlmService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProfileGenerationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LlmService llm = mock(LlmService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProfileGenerationService service = new ProfileGenerationService(
            jdbc, llm, mapper, mock(InterviewSkillService.class));
    private final SkillDTO skill = new SkillDTO("custom", "JD", "JD", List.of(), false, "JD", null, null);
    private final EvaluationReport report = new EvaluationReport("session", 1, 80, List.of(), List.of(),
            "反馈", List.of(), List.of(), List.of());
    private static final String VALID = """
            {"summary":"理解基础概念","categories":[{"category":"JAVA","level":"adequate","text":"能解释线程互斥"}],"nextAdvice":"练习锁竞争分析"}
            """;

    @Test void malformedModelProfilesCannotOverwriteStoredProfileOrIncrementCount() {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(42L), eq("custom"))).thenReturn(VALID);
        for (String raw : List.of("null", "[]", "{}", "not json",
                "{\"categories\":[null]}",
                VALID.replace("\"summary\":\"理解基础概念\"", "\"summary\":42"),
                VALID.replace("\"nextAdvice\":\"练习锁竞争分析\"", "\"nextAdvice\":\" \""),
                VALID.replace("[{\"category\":\"JAVA\",\"level\":\"adequate\",\"text\":\"能解释线程互斥\"}]", "[null]"),
                VALID.replace("[{\"category\":\"JAVA\",\"level\":\"adequate\",\"text\":\"能解释线程互斥\"}]", "[]"),
                VALID.replace("\"JAVA\"", "null"),
                VALID.replace("\"adequate\"", "\"invalid-level\""),
                VALID.replace("\"能解释线程互斥\"", "[]"))) {
            when(llm.chat(anyString(), anyString())).thenReturn(raw);
            service.generateOrUpdateProfileAsync(42L, skill, report, List.of());
        }
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test void validProfileLevelsRetainExistingContextAndPersistTheOriginalContract() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(42L), eq("custom"))).thenReturn(VALID);
        for (String level : List.of("strong", "adequate", "weak", "untouched")) {
            String json = VALID.replace("adequate", level);
            when(llm.chat(anyString(), contains(VALID))).thenReturn("```json\n" + json + "\n```");
            service.generateOrUpdateProfileAsync(42L, skill, report, List.of());
            ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
            verify(jdbc).update(contains("interview_count = interview_count + 1"), eq(42L), eq("custom"), saved.capture());
            assertEquals(mapper.readTree(json), mapper.readTree(saved.getValue()));
            clearInvocations(jdbc);
        }
    }
}
