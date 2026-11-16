package com.jobai.agent.interview.service;

import com.jobai.agent.interview.model.InterviewQuestionDTO;
import com.jobai.agent.interview.model.InterviewSessionEntity;
import com.jobai.agent.interview.skill.InterviewSkillService;
import com.jobai.agent.interview.skill.InterviewSkillService.SkillDTO;
import com.jobai.agent.profile.ProfileGenerationService;
import com.jobai.common.BusinessException;
import com.jobai.common.evaluation.EvaluationReport;
import com.jobai.knowledge.evaluation.UnifiedEvaluationService;
import com.jobai.knowledge.llm.LangChain4jConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InterviewAnswerEvaluationTest {
    private final UnifiedEvaluationService evaluator = mock(UnifiedEvaluationService.class);
    private final InterviewPersistenceService persistence = mock(InterviewPersistenceService.class);
    private final InterviewSkillService skills = mock(InterviewSkillService.class);
    private final ProfileGenerationService profiles = mock(ProfileGenerationService.class);
    private final AnswerEvaluationService service = new AnswerEvaluationService(evaluator, persistence, skills,
            mock(LangChain4jConfig.class), profiles);
    private final SkillDTO skill = new SkillDTO("custom", "JD", "custom", List.of(), false, "original JD", null, null);
    private final List<InterviewQuestionDTO> questions = List.of(InterviewQuestionDTO.create(0, "Q", "JAVA", "Java").withAnswer("A"));

    private void session() {
        var entity = new InterviewSessionEntity(); entity.setUserId(7L); entity.setSkillId("custom"); entity.setSkillSnapshotJson("snapshot");
        when(persistence.findBySessionId("session")).thenReturn(Optional.of(entity));
        when(skills.getSessionSkill("custom", "snapshot")).thenReturn(skill);
        when(skills.buildEvaluationReferenceSection(skill)).thenReturn("");
    }

    @Test void customProfileReceivesThePersistedSkillInsteadOfLookingUpCustomPreset() {
        session();
        var report = new EvaluationReport("session", 1, 85, List.of(), List.of(), "Good", List.of(), List.of(), List.of());
        when(evaluator.evaluate(any(), eq("session"), anyList(), eq(""), eq(""))).thenReturn(report);
        service.evaluateInterview("session", 7L, "", questions);
        verify(persistence).saveReport(eq("session"), any());
        verify(profiles).generateOrUpdateProfileAsync(7L, skill, report, questions);
        verify(skills, never()).getSkill(anyString());
    }

    @Test void failedEvaluationDoesNotPersistReportOrGenerateProfile() {
        session();
        when(evaluator.evaluate(any(), eq("session"), anyList(), eq(""), eq(""))).thenThrow(new BusinessException(2002, "failed"));
        assertThrows(BusinessException.class, () -> service.evaluateInterview("session", 7L, "", questions));
        verify(persistence, never()).saveReport(anyString(), any());
        verifyNoInteractions(profiles);
    }
}
