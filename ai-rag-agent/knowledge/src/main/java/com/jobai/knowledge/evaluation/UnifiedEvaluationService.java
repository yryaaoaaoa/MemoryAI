package com.jobai.knowledge.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.evaluation.EvaluationReport;
import com.jobai.common.evaluation.QaRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 统一的面试评估：分批评估 + 二次汇总。
 * 文本面试和未来的语音面试共享。
 * <p>
 * 使用 {@link LlmChat} 函数式接口 — 调用方提供 LangChain4j
 * 或 RestClient 适配器，避免对任何 LLM SDK 产生硬依赖。
 */
@Slf4j
@Service
public class UnifiedEvaluationService {

    @FunctionalInterface
    public interface LlmChat {
        /** 同步对话：系统提示词 → 用户提示词 → 回复文本。 */
        String call(String systemPrompt, String userPrompt);
    }

    private static final int BATCH_SIZE = 5;
    private static final int MAX_RESUME_CHARS = 3000;
    private static final int MAX_REF_CHARS = 6000;

    private static final String BATCH_SYSTEM_PROMPT = """
            你是一位资深技术面试评估专家。评估候选人对每题的回答。

            评估标准：
            1. 技术准确性（40%）— 答案是否正确，概念是否清晰
            2. 深度与广度（30%）— 是否展示了深入理解或扩展思考
            3. 表达能力（20%）— 逻辑是否清楚，层次是否分明
            4. 实战经验（10%）— 是否结合了实际场景

            严格按以下 JSON 格式输出，不要 markdown 标记：
            {
              "overallScore": 75,
              "overallFeedback": "本批次总体评价...",
              "strengths": ["优势1", "优势2"],
              "improvements": ["改进1", "改进2"],
              "questionEvaluations": [
                {
                  "questionIndex": 0,
                  "score": 80,
                  "feedback": "回答到位，可以更好...",
                  "referenceAnswer": "标准参考答案...",
                  "keyPoints": ["关键点1", "关键点2"]
                }
              ]
            }
            """;

    private static final String BATCH_USER_PROMPT = """
            候选人简历摘要：
            {resumeContext}

            面试题目与回答（{} 题）：
            {qaRecords}

            方向参考基线：
            {referenceContext}

            请评估上述每道题的回答。
            """;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一位资深技术面试评估专家。根据分批评估的结果，生成一份统一的面试报告。

            要求：
            1. 合并各批次的总体评价，提炼出 3-5 点核心优势和改进建议
            2. 总结需要简明扼要，每条不超过 50 字
            3. 输出纯 JSON，不要 markdown 标记

            JSON 格式：
            {
              "overallFeedback": "统一总体评价...",
              "strengths": ["优势1", "优势2", "优势3"],
              "improvements": ["改进1", "改进2", "改进3"]
            }
            """;

    private static final String SUMMARY_USER_PROMPT = """
            面试概况（{} 题）：
            {categorySummary}

            问题亮点：
            {questionHighlights}

            各批次总体评价：
            {batchFeedbacks}

            请汇总生成最终报告。
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== LLM 输出的 DTO ====================

    @SuppressWarnings("unused")
    private record BatchResultDTO(int overallScore, String overallFeedback,
                                  List<String> strengths, List<String> improvements,
                                  List<QuestionEvalDTO> questionEvaluations) {
    }

    @SuppressWarnings("unused")
    private record QuestionEvalDTO(int questionIndex, int score, String feedback,
                                   String referenceAnswer, List<String> keyPoints) {
    }

    @SuppressWarnings("unused")
    private record SummaryDTO(String overallFeedback, List<String> strengths, List<String> improvements) {
    }

    // ==================== 公共 API ====================

    public EvaluationReport evaluate(LlmChat chat, String sessionId,
                                      List<QaRecord> qaRecords,
                                      String resumeText, String referenceContext) {
        log.info("Evaluating interview: sessionId={}, questions={}", sessionId, qaRecords.size());

        String resumeCtx = truncate(resumeText, MAX_RESUME_CHARS);
        String refCtx = truncate(referenceContext, MAX_REF_CHARS);

        List<BatchResultDTO> batchResults = evaluateBatches(chat, qaRecords, resumeCtx, refCtx);
        if (batchResults.isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_SERVICE_FAILED, "所有批次评估均失败");
        }

        List<QuestionEvalDTO> mergedEvals = mergeEvaluations(batchResults, qaRecords.size());
        String fallbackFeedback = mergeBatchFeedbacks(batchResults);
        List<String> fallbackStrengths = mergeBatchItems(batchResults, true);
        List<String> fallbackImprovements = mergeBatchItems(batchResults, false);

        SummaryDTO summary = summarize(chat, qaRecords, mergedEvals,
                fallbackFeedback, fallbackStrengths, fallbackImprovements);

        return buildReport(sessionId, qaRecords, mergedEvals,
                summary.overallFeedback(), summary.strengths(), summary.improvements());
    }

    // ==================== 分批评估 ====================

    private List<BatchResultDTO> evaluateBatches(LlmChat chat,
                                                  List<QaRecord> qaRecords,
                                                  String resumeCtx, String refCtx) {
        // 并行调用各批次 LLM 评估（I/O 密集型，显著减少总体等待时间）
        List<CompletableFuture<BatchResultDTO>> futures = new ArrayList<>();

        for (int start = 0; start < qaRecords.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, qaRecords.size());
            List<QaRecord> batch = qaRecords.subList(start, end);
            int batchStart = start;
            int batchEnd = end;

            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return evaluateOneBatch(chat, batch, resumeCtx, refCtx);
                } catch (Exception e) {
                    log.error("Batch evaluation failed: [{},{}), error={}", batchStart, batchEnd, e.getMessage());
                    return buildZeroResult(batchStart, batchEnd, "该批次评估失败: " + e.getMessage());
                }
            }));
        }

        return futures.stream().map(CompletableFuture::join).toList();
    }

    private BatchResultDTO evaluateOneBatch(LlmChat chat,
                                             List<QaRecord> batch,
                                             String resumeCtx, String refCtx) {
        String qaText = buildQaText(batch);
        String userPrompt = BATCH_USER_PROMPT
                .replace("{resumeContext}", resumeCtx.isBlank() ? "（无简历）" : resumeCtx)
                .replace("{}", String.valueOf(batch.size()))
                .replace("{qaRecords}", qaText)
                .replace("{referenceContext}", refCtx.isBlank() ? "（无参考基线）" : refCtx);

        String raw = chat.call(BATCH_SYSTEM_PROMPT, userPrompt);

        raw = raw.replaceAll("```[a-z]*\\s*", "").replace("```", "").strip();
        try {
            return mapper.readValue(raw, BatchResultDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LLM evaluation output: " + e.getMessage(), e);
        }
    }

    private BatchResultDTO buildZeroResult(int start, int end, String feedback) {
        List<QuestionEvalDTO> evals = new ArrayList<>();
        for (int i = start; i < end; i++) {
            evals.add(new QuestionEvalDTO(i, 0, feedback, "", List.of()));
        }
        return new BatchResultDTO(0, feedback, List.of(), List.of(), evals);
    }

    // ==================== 合并 ====================

    private List<QuestionEvalDTO> mergeEvaluations(List<BatchResultDTO> batchResults, int totalSize) {
        Map<Integer, QuestionEvalDTO> indexMap = new HashMap<>();
        for (BatchResultDTO batch : batchResults) {
            if (batch.questionEvaluations() == null) continue;
            for (QuestionEvalDTO eval : batch.questionEvaluations()) {
                indexMap.put(eval.questionIndex(), eval);
            }
        }

        List<QuestionEvalDTO> merged = new ArrayList<>();
        for (int i = 0; i < totalSize; i++) {
            QuestionEvalDTO eval = indexMap.get(i);
            merged.add(eval != null ? eval : new QuestionEvalDTO(i, 0,
                    "未生成有效评估", "", List.of()));
        }
        return merged;
    }

    private String mergeBatchFeedbacks(List<BatchResultDTO> batchResults) {
        return batchResults.stream()
                .map(BatchResultDTO::overallFeedback)
                .filter(f -> f != null && !f.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    private List<String> mergeBatchItems(List<BatchResultDTO> batchResults, boolean strengthsMode) {
        return batchResults.stream()
                .map(r -> strengthsMode ? r.strengths() : r.improvements())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .limit(8)
                .toList();
    }

    // ==================== 二次汇总 ====================

    private SummaryDTO summarize(LlmChat chat,
                                  List<QaRecord> qaRecords,
                                  List<QuestionEvalDTO> evaluations,
                                  String fallbackFeedback,
                                  List<String> fallbackStrengths,
                                  List<String> fallbackImprovements) {
        try {
            String userPrompt = SUMMARY_USER_PROMPT
                    .replace("{}", String.valueOf(qaRecords.size()))
                    .replace("{categorySummary}", buildCategorySummary(qaRecords, evaluations))
                    .replace("{questionHighlights}", buildHighlights(qaRecords, evaluations))
                    .replace("{batchFeedbacks}", fallbackFeedback);

            String raw = chat.call(SUMMARY_SYSTEM_PROMPT, userPrompt);

            raw = raw.replaceAll("```[a-z]*\\s*", "").replace("```", "").strip();
            SummaryDTO dto = mapper.readValue(raw, SummaryDTO.class);

            String feedback = dto.overallFeedback() != null && !dto.overallFeedback().isBlank()
                    ? dto.overallFeedback() : fallbackFeedback;
            List<String> strengths = sanitizeList(dto.strengths(), fallbackStrengths);
            List<String> improvements = sanitizeList(dto.improvements(), fallbackImprovements);
            return new SummaryDTO(feedback, strengths, improvements);
        } catch (Exception e) {
            log.warn("Summary failed, falling back to batch merge: {}", e.getMessage());
            return new SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements);
        }
    }

    private List<String> sanitizeList(List<String> primary, List<String> fallback) {
        List<String> source = (primary != null && !primary.isEmpty()) ? primary : fallback;
        if (source == null || source.isEmpty()) return List.of();
        return source.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .limit(8)
                .toList();
    }

    // ==================== 报告构建 ====================

    private EvaluationReport buildReport(String sessionId, List<QaRecord> qaRecords,
                                          List<QuestionEvalDTO> evaluations,
                                          String overallFeedback,
                                          List<String> strengths, List<String> improvements) {
        List<EvaluationReport.QuestionEvaluation> questionDetails = new ArrayList<>();
        List<EvaluationReport.ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, List<Integer>> catScores = new LinkedHashMap<>();
        Map<String, Integer> catTotal = new LinkedHashMap<>();

        long answeredCount = qaRecords.stream()
                .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank()).count();

        int evalSize = evaluations != null ? evaluations.size() : 0;

        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evalSize ? evaluations.get(i) : null;

            boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
            int score = hasAnswer && eval != null ? eval.score() : 0;
            String feedback = hasAnswer && eval != null && eval.feedback() != null
                    ? eval.feedback() : "（未作答）";
            String refAnswer = eval != null && eval.referenceAnswer() != null
                    ? eval.referenceAnswer() : "";
            List<String> keyPoints = eval != null && eval.keyPoints() != null
                    ? eval.keyPoints() : List.of();

            questionDetails.add(new EvaluationReport.QuestionEvaluation(
                    q.questionIndex(), q.question(), q.category(), q.userAnswer(), score, feedback));
            referenceAnswers.add(new EvaluationReport.ReferenceAnswer(
                    q.questionIndex(), q.question(), refAnswer, keyPoints));
            catTotal.merge(q.category(), 1, Integer::sum);
            // 只算已答题进分类均分，未答题不拉低分数
            if (hasAnswer) {
                catScores.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
            }
        }

        List<EvaluationReport.CategoryScore> categoryScores = catScores.entrySet().stream()
                .map(e -> new EvaluationReport.CategoryScore(e.getKey(),
                        (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                        e.getValue().size(),
                        catTotal.getOrDefault(e.getKey(), 0)))
                .collect(Collectors.toList());

        int overallScore = answeredCount == 0 ? 0
                : (int) questionDetails.stream().mapToInt(EvaluationReport.QuestionEvaluation::score)
                .average().orElse(0);

        return new EvaluationReport(sessionId, qaRecords.size(), overallScore, categoryScores,
                questionDetails, overallFeedback,
                strengths != null ? strengths : List.of(),
                improvements != null ? improvements : List.of(),
                referenceAnswers);
    }

    // ==================== 提示词辅助方法 ====================

    private String buildQaText(List<QaRecord> batch) {
        StringBuilder sb = new StringBuilder();
        for (QaRecord q : batch) {
            sb.append(String.format("问题%d [%s]: %s\n", q.questionIndex() + 1, q.category(), q.question()));
            sb.append(String.format("回答: %s\n\n", q.userAnswer() != null ? q.userAnswer() : "（未回答）"));
        }
        return sb.toString();
    }

    private String buildCategorySummary(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        Map<String, List<Integer>> scores = new LinkedHashMap<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = eval != null && q.userAnswer() != null && !q.userAnswer().isBlank()
                    ? eval.score() : 0;
            scores.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }
        return scores.entrySet().stream()
                .map(e -> String.format("- %s: 均分 %d, 题数 %d",
                        e.getKey(),
                        (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                        e.getValue().size()))
                .sorted().collect(Collectors.joining("\n"));
    }

    private String buildHighlights(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        return qaRecords.stream()
                .limit(20)
                .map(q -> {
                    QuestionEvalDTO eval = q.questionIndex() < evaluations.size()
                            ? evaluations.get(q.questionIndex()) : null;
                    int score = eval != null ? eval.score() : 0;
                    String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
                    String shortQ = q.question().length() > 40
                            ? q.question().substring(0, 40) + "..." : q.question();
                    String shortF = feedback.length() > 60
                            ? feedback.substring(0, 60) + "..." : feedback;
                    return String.format("- Q%d | %s | %d分 | %s",
                            q.questionIndex() + 1, shortQ, score, shortF);
                })
                .collect(Collectors.joining("\n"));
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "\n...(truncated)" : text;
    }
}
