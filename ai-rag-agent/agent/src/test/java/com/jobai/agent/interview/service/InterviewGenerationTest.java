package com.jobai.agent.interview.service;

import com.jobai.agent.interview.model.InterviewQuestionDTO;
import com.jobai.agent.interview.skill.InterviewSkillService;
import com.jobai.agent.interview.skill.InterviewSkillService.SkillDTO;
import com.jobai.agent.interview.skill.InterviewSkillService.SkillCategoryDTO;
import com.jobai.common.BusinessException;
import com.jobai.common.auth.AuthContext;
import com.jobai.knowledge.llm.LangChain4jConfig;
import com.jobai.knowledge.mapper.ResumeMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InterviewGenerationTest {
    private final LangChain4jConfig config = mock(LangChain4jConfig.class);
    private final InterviewSkillService skills = mock(InterviewSkillService.class);
    private final ChatModel model = mock(ChatModel.class, CALLS_REAL_METHODS);
    private final SkillDTO skill = new SkillDTO("custom", "自定义", "custom JD", List.of(
            new SkillCategoryDTO("JAVA", "Java", "CORE", null, false)), false, "JD snapshot", null, null);
    private final List<DynamicQuestionService.RemainingCategory> remaining = List.of(
            new DynamicQuestionService.RemainingCategory("JAVA", "Java", "CORE", 10, 9));

    private void modelOutput(String output) {
        when(config.createChatModel(0.5)).thenReturn(model);
        doReturn(ChatResponse.builder().aiMessage(AiMessage.from(output)).build()).when(model).chat(any(ChatRequest.class));
        when(skills.buildStructuredResumeContext(any())).thenReturn("");
    }

    @Test void malformedDecisionDoesNotCompleteAnInterview() {
        var service = new DynamicQuestionService(config, skills);
        var questions = List.of(InterviewQuestionDTO.create(0, "Q", "JAVA", "Java").withAnswer("A"));
        for (String raw : List.of("not json", "{\"action\":\"next_topic\"}", "{\"action\":\"unexpected\",\"question\":\"Q2\"}")) {
            modelOutput(raw);
            assertThrows(BusinessException.class, () -> service.decide(skill, "mid", null, "", questions, remaining, null, null));
        }
        verify(skills, never()).getSkill(anyString());
    }

    @Test void customDirectionUsesTheGivenSnapshotForFirstQuestion() {
        modelOutput("{\"question\":\"解释 JVM 内存模型\",\"type\":\"JAVA\",\"category\":\"Java\"}");
        var action = new DynamicQuestionService(config, skills).decide(skill, "mid", null, "", List.of(), remaining, null, null);
        assertEquals("next_topic", action.action());
        assertEquals("解释 JVM 内存模型", action.question());
        verify(skills, never()).getSkill(anyString());
    }

    @Test void malformedFirstQuestionDoesNotReturnATemplate() {
        modelOutput("{}");
        assertThrows(BusinessException.class, () -> new DynamicQuestionService(config, skills)
                .decide(skill, "mid", null, "", List.of(), remaining, null, null));
    }

    @Test void failedBatchGenerationDoesNotReturnFixedQuestions() {
        modelOutput("not json");
        when(skills.calculateAllocation(eq("custom"), anyList(), eq(10), any())).thenReturn(Map.of("JAVA", 10));
        when(skills.buildAllocationTable(anyMap(), anyList())).thenReturn("JAVA: 10");
        when(skills.buildReferenceSection(any(), anyMap())).thenReturn("");
        var service = new InterviewQuestionService(skills, config, mock(ResumeMapper.class));
        try {
            assertThrows(BusinessException.class, () -> service.generateQuestions(skill, "mid", "", null, 10, List.of()));
        } finally { service.shutdown(); }
    }

    @Test void resumeBatchUsesTheRequestUserForProfileWeightedAllocation() {
        modelOutput("[{\"question\":\"Q1\",\"type\":\"JAVA\"},{\"question\":\"Q2\",\"type\":\"JAVA\"}]");
        when(skills.calculateAllocation(eq("custom"), anyList(), eq(1), eq(42L))).thenReturn(Map.of("JAVA", 1));
        when(skills.buildAllocationTable(anyMap(), anyList())).thenReturn("JAVA: 1");
        when(skills.buildReferenceSection(any(), anyMap())).thenReturn("");
        var service = new InterviewQuestionService(skills, config, mock(ResumeMapper.class));
        AuthContext.set(42L, "user");
        try {
            var generated = service.generateQuestions(skill, "mid", "候选人简历", null, 3, List.of());
            assertEquals(3, generated.size());
            assertEquals(List.of(0, 1, 2), generated.stream().map(InterviewQuestionDTO::questionIndex).toList());
            verify(skills).calculateAllocation("custom", skill.categories(), 1, 42L);
        } finally {
            AuthContext.clear();
            service.shutdown();
        }
    }

    @Test void blankFollowUpsAreRemovedBeforeTheCountLimitAndIndexesRemainContinuous() {
        modelOutput("[{\"question\":\"主问题\",\"type\":\"JAVA\",\"followUps\":[\"\",\"  \",null,12,\"追问一\",\" 追问二 \",\"超出上限\"]}]");
        when(skills.calculateAllocation(eq("custom"), anyList(), eq(1), any())).thenReturn(Map.of("JAVA", 1));
        when(skills.buildAllocationTable(anyMap(), anyList())).thenReturn("JAVA: 1");
        when(skills.buildReferenceSection(any(), anyMap())).thenReturn("");
        var service = new InterviewQuestionService(skills, config, mock(ResumeMapper.class));
        try {
            var generated = service.generateQuestions(skill, "mid", "", null, 1, List.of());
            assertEquals(List.of("主问题", "追问一", "追问二"), generated.stream().map(InterviewQuestionDTO::question).toList());
            assertEquals(List.of(0, 1, 2), generated.stream().map(InterviewQuestionDTO::questionIndex).toList());
            assertTrue(generated.get(1).isFollowUp());
            assertEquals(0, generated.get(1).parentQuestionIndex());
            assertEquals(0, generated.get(2).parentQuestionIndex());
        } finally { service.shutdown(); }
    }
}
