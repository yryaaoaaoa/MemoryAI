package com.jobai.agent.interview.service;

import com.jobai.agent.interview.model.InterviewQuestionDTO;
import com.jobai.agent.interview.model.InterviewReportDTO;
import com.jobai.agent.interview.model.InterviewSessionEntity;
import com.jobai.agent.interview.skill.InterviewSkillService;
import com.jobai.agent.profile.ProfileGenerationService;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.evaluation.EvaluationReport;
import com.jobai.common.evaluation.QaRecord;
import com.jobai.knowledge.evaluation.UnifiedEvaluationService;
import com.jobai.knowledge.evaluation.UnifiedEvaluationService.LlmChat;
import com.jobai.knowledge.llm.LangChain4jConfig;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 适配器：将面试 DTO 转换为通用评估记录，
 * 委托给 UnifiedEvaluationService 并持久化结果。
 * 评估完成后异步生成 LLM 画像，替代旧的 user_mastery 数值方案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerEvaluationService {

    private final UnifiedEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSkillService skillService;
    private final LangChain4jConfig langChain4jConfig;
    private final ProfileGenerationService profileGenerationService;

    public InterviewReportDTO evaluateInterview(String sessionId, Long userId,
                                                  String resumeText,
                                                  List<InterviewQuestionDTO> questions) {
        log.info("Evaluating interview: sessionId={}, userId={}, questions={}", sessionId, userId, questions.size());

        List<QaRecord> qaRecords = questions.stream()
                .map(q -> new QaRecord(q.questionIndex(), q.question(), q.category(), q.userAnswer()))
                .toList();

        InterviewSessionEntity entity = persistenceService.findBySessionId(sessionId)
                .filter(s -> userId != null && userId.equals(s.getUserId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在"));
        var skill = skillService.getSessionSkill(entity.getSkillId(), entity.getSkillSnapshotJson());
        String referenceContext = skillService.buildEvaluationReferenceSection(skill);

        // 将 LangChain4j ChatModel 包装在 LlmChat 函数式接口中
        LlmChat chat = (String system, String user) ->
                langChain4jConfig.createChatModel(0.0)
                        .chat(SystemMessage.from(system), UserMessage.from(user))
                        .aiMessage().text();

        try {
            EvaluationReport report = evaluationService.evaluate(
                    chat, sessionId, qaRecords, resumeText, referenceContext);

            InterviewReportDTO dto = toInterviewReportDTO(report);

            // 持久化评分结果
            persistenceService.saveReport(sessionId, dto);

            // 异步生成 LLM 画像（替代旧的 user_mastery 写入）
            try {
                profileGenerationService.generateOrUpdateProfileAsync(
                        userId, skill, report, questions);
            } catch (Exception e) {
                log.warn("Could not schedule profile generation for session {}", sessionId, e);
            }

            log.info("Evaluation complete: sessionId={}, score={}", sessionId, report.overallScore());
            return dto;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Evaluation failed: sessionId={}, error={}", sessionId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.LLM_SERVICE_FAILED, "评估失败: " + e.getMessage());
        }
    }

    private InterviewReportDTO toInterviewReportDTO(EvaluationReport report) {
        return new InterviewReportDTO(
                report.sessionId(),
                report.totalQuestions(),
                report.overallScore(),
                report.categoryScores().stream()
                        .map(cs -> new InterviewReportDTO.CategoryScore(cs.category(), cs.score(), cs.totalCount()))
                        .toList(),
                report.questionDetails().stream()
                        .map(qe -> new InterviewReportDTO.QuestionEvaluation(
                                qe.questionIndex(), qe.question(), qe.category(),
                                qe.userAnswer(), qe.score(), qe.feedback()))
                        .toList(),
                report.overallFeedback(),
                report.strengths(),
                report.improvements(),
                report.referenceAnswers().stream()
                        .map(ra -> new InterviewReportDTO.ReferenceAnswer(
                                ra.questionIndex(), ra.question(),
                                ra.referenceAnswer(), ra.keyPoints()))
                        .toList()
        );
    }
}
