package com.jobai.agent.service;

import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.auth.AuthContext;
import com.jobai.knowledge.entity.Chunk;
import com.jobai.knowledge.entity.KnowledgeBase;
import com.jobai.knowledge.entity.QuizQuestion;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.knowledge.mapper.ChunkMapper;
import com.jobai.knowledge.mapper.QuizQuestionMapper;
import com.jobai.knowledge.retrieval.VectorRetrievalService;
import com.jobai.knowledge.retrieval.VectorRetrievalService.RetrievedChunk;
import com.jobai.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuizServiceTest {
    private final LlmService llm = mock(LlmService.class);
    private final VectorRetrievalService retrieval = mock(VectorRetrievalService.class);
    private final QuizQuestionMapper questions = mock(QuizQuestionMapper.class);
    private final UserMasteryService mastery = mock(UserMasteryService.class);
    private final KnowledgeBaseService libraries = mock(KnowledgeBaseService.class);
    private final ChunkMapper chunks = mock(ChunkMapper.class);
    private final QuizService service = new QuizService(llm, retrieval, questions, mastery, libraries, chunks);
    private static final String GENERATED = """
            [{"question":"线程池适合什么任务？","options":{"A":"异步任务","B":"无","C":"无","D":"无"},
              "answer":"A","explanation":"依据提供的线程池资料"}]
            """;

    @BeforeEach void setup() {
        AuthContext.set(42L, "user");
        KnowledgeBase kb = new KnowledgeBase(); kb.setId(7L); kb.setName("学习资料");
        when(libraries.getById(7L, 42L)).thenReturn(kb);
        when(llm.chat(anyString(), anyString(), anyInt())).thenReturn(GENERATED);
        doAnswer(invocation -> {
            QuizQuestion question = invocation.getArgument(0); question.setId(101L); return 1;
        }).when(questions).insert(any(QuizQuestion.class));
    }
    @AfterEach void clear() { AuthContext.clear(); }

    @Test void ordinaryQuizWorksWithoutAnyRagBeans() {
        QuizService noRag = new QuizService(llm, null, questions, mastery, null, chunks);
        var result = noRag.generateByKbs(List.of(), 1, "mixed", "线程池");
        assertEquals(1, result.size());
        verify(llm).chat(contains("指定的技术方向"), contains("线程池"), anyInt());
        verifyNoInteractions(retrieval, libraries, chunks);
        var saved = ArgumentCaptor.forClass(QuizQuestion.class);
        verify(questions).insert(saved.capture());
        assertEquals(0L, saved.getValue().getKbId());
        assertEquals(42L, saved.getValue().getUserId());
    }

    @Test void disabledRagRejectsExplicitKnowledgeBaseWithoutCallingModel() {
        QuizService noRag = new QuizService(llm, null, questions, mastery, null, chunks);
        var error = assertThrows(BusinessException.class,
                () -> noRag.generateByKbs(List.of(7L), 1, "mixed", "线程池"));
        assertEquals(503, error.getCode());
        verifyNoInteractions(llm, questions, chunks);
    }

    @Test void ordinaryQuizWithoutDirectionOrHistoryExplainsHowToContinue() {
        QuizService noRag = new QuizService(llm, null, questions, mastery, null, chunks);
        when(mastery.getWeakestTopics(42L, 1)).thenReturn(List.of());
        var error = assertThrows(IllegalArgumentException.class,
                () -> noRag.generateByKbs(List.of(), 1, "mixed", null));
        assertTrue(error.getMessage().contains("填写出题方向"));
        verifyNoInteractions(llm, questions, chunks);
    }

    private void sample() {
        Chunk chunk = new Chunk(); chunk.setContent("线程池用于复用线程执行异步任务。");
        chunk.setHeadingPath("并发 / 线程池");
        when(chunks.selectReadySamples(7L, 5)).thenReturn(List.of(chunk));
    }

    @Test void emptyWeakTopicsStillGeneratesPersistedQuestionsFromSelectedMaterial() {
        // This empty result covers new users as well as existing users with no due weak topic.
        when(mastery.getWeakestTopics(42L, 1)).thenReturn(List.of());
        sample();
        var result = service.generateByKbs(List.of(7L), 1, "hard", null);
        assertEquals(101L, result.get(0).getId());
        verifyNoInteractions(retrieval);
        verify(libraries).getById(7L, 42L);
        verify(llm).chat(argThat(prompt -> !prompt.contains("1/3简单")),
                argThat(prompt -> prompt.contains("线程池用于复用线程") && prompt.contains("困难")), eq(4096));
        ArgumentCaptor<QuizQuestion> saved = ArgumentCaptor.forClass(QuizQuestion.class);
        verify(questions).insert(saved.capture());
        assertEquals("并发 / 线程池", saved.getValue().getTopic());
        assertEquals(42L, saved.getValue().getUserId());
        assertEquals(7L, saved.getValue().getKbId());
    }

    @Test void unrelatedAutomaticWeakTopicFallsBackToSelectedLibrary() {
        when(mastery.getWeakestTopics(42L, 1)).thenReturn(List.of(
                new com.jobai.agent.model.MasteryEntry("Redis", 30, 1, 0, null)));
        when(retrieval.search("Redis", List.of(7L), 5, 0.2, 42L)).thenReturn(List.of());
        sample();
        assertEquals(1, service.generateByKbs(List.of(7L), 1, "mixed", " ").size());
        ArgumentCaptor<QuizQuestion> saved = ArgumentCaptor.forClass(QuizQuestion.class);
        verify(questions).insert(saved.capture());
        assertEquals("并发 / 线程池", saved.getValue().getTopic());
    }

    @Test void relevantAutomaticWeakTopicKeepsExistingRetrievalBehavior() {
        when(mastery.getWeakestTopics(42L, 1)).thenReturn(List.of(
                new com.jobai.agent.model.MasteryEntry("线程池", 30, 1, 0, null)));
        when(retrieval.search("线程池", List.of(7L), 5, 0.2, 42L)).thenReturn(List.of(
                new RetrievedChunk("线程池参考", "并发", .9, 7L, 1L)));
        assertEquals(1, service.generateByKbs(List.of(7L), 1, "easy", null).size());
        verifyNoInteractions(chunks);
        verify(llm).chat(anyString(), argThat(prompt -> prompt.contains("简单")), eq(4096));
    }

    @Test void explicitUnrelatedTopicStillFailsWithoutSamplingOrModelCall() {
        when(retrieval.search("Redis", List.of(7L), 5, 0.2, 42L)).thenReturn(List.of());
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.generateByKbs(List.of(7L), 1, "medium", "Redis"));
        assertTrue(error.getMessage().contains("不相关"));
        verifyNoInteractions(chunks, llm, questions, mastery);
    }

    @Test void checksAllLibraryPermissionsBeforeReadingAnyContent() {
        when(libraries.getById(8L, 42L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        var error = assertThrows(BusinessException.class,
                () -> service.generateByKbs(List.of(7L, 8L), 1, "mixed", null));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), error.getCode());
        verifyNoInteractions(chunks, retrieval, llm, questions, mastery);
    }

    @Test void noReadyContentExplainsHowToRecover() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.generateByKbs(List.of(7L), 1, "mixed", null));
        assertTrue(error.getMessage().contains("等待解析完成"));
        verifyNoInteractions(llm, questions);
    }

    @Test void providerFailureNeverReturnsUnpersistedTemplateQuestions() {
        sample();
        when(llm.chat(anyString(), anyString(), anyInt())).thenThrow(new IllegalStateException("unavailable"));
        var error = assertThrows(BusinessException.class,
                () -> service.generateByKbs(List.of(7L), 1, "mixed", null));
        assertEquals(ErrorCode.QUIZ_GENERATE_FAILED.getCode(), error.getCode());
        verifyNoInteractions(questions);
    }

    @Test void persistenceFailureIsNotReportedAsSuccessfulGeneration() {
        sample();
        doThrow(new RuntimeException("database unavailable")).when(questions).insert(any(QuizQuestion.class));
        assertThrows(BusinessException.class,
                () -> service.generateByKbs(List.of(7L), 1, "mixed", null));
    }

    @Test void emptyModelResponseIsNotReplacedByTemplateQuestions() {
        sample();
        when(llm.chat(anyString(), anyString(), anyInt())).thenReturn("[]");
        assertThrows(BusinessException.class,
                () -> service.generateByKbs(List.of(7L), 1, "mixed", null));
        verifyNoInteractions(questions);
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 20})
    void largerQuizzesRequestEnoughOutputAndSaveTheFullBatch(int count) {
        sample();
        String question = GENERATED.strip();
        question = question.substring(1, question.length() - 1);
        String response = "[" + String.join(",", java.util.Collections.nCopies(count, question)) + "]";
        when(llm.chat(anyString(), anyString(), anyInt())).thenReturn(response);
        assertEquals(count, service.generateByKbs(List.of(7L), count, "mixed", null).size());
        verify(llm).chat(anyString(), anyString(), eq(2048 + count * 1024));
        verify(questions, times(count)).insert(any(QuizQuestion.class));
    }

    @Test void truncatedOutputExplainsTheLengthLimitWithoutSavingQuestions() {
        sample();
        when(llm.chat(anyString(), anyString(), anyInt())).thenThrow(new LlmService.OutputTruncatedException());
        var error = assertThrows(BusinessException.class,
                () -> service.generateByKbs(List.of(7L), 10, "mixed", null));
        assertTrue(error.getMessage().contains("长度限制"));
        assertFalse(error.getMessage().contains("配置"));
        verifyNoInteractions(questions);
    }

    @Test void malformedJsonIsReportedAsFormatFailureWithoutSavingQuestions() {
        sample();
        when(llm.chat(anyString(), anyString(), anyInt())).thenReturn(GENERATED.strip().substring(0, 100));
        var error = assertThrows(BusinessException.class,
                () -> service.generateByKbs(List.of(7L), 10, "mixed", null));
        assertTrue(error.getMessage().contains("格式不完整"));
        verifyNoInteractions(questions);
    }

    @Test void invalidLaterQuestionDoesNotSaveEarlierValidQuestions() {
        sample();
        String question = GENERATED.strip();
        question = question.substring(1, question.length() - 1);
        when(llm.chat(anyString(), anyString(), anyInt())).thenReturn("[" + question + ",{}]");
        assertThrows(BusinessException.class, () -> service.generateByKbs(List.of(7L), 2, "mixed", null));
        verifyNoInteractions(questions);
    }

    @Test void tooFewQuestionsAreNotPresentedAsACompleteQuiz() {
        sample();
        var error = assertThrows(BusinessException.class,
                () -> service.generateByKbs(List.of(7L), 10, "mixed", null));
        assertTrue(error.getMessage().contains("指定数量"));
        verifyNoInteractions(questions);
    }

    @Test void codeFencesInsideQuestionsArePreserved() {
        sample();
        when(llm.chat(anyString(), anyString(), anyInt())).thenReturn("```json\n" +
                GENERATED.replace("线程池适合什么任务？", "这段代码的输出是什么？\\n```java\\nint n = 1;\\n```") + "\n```");
        var result = service.generateByKbs(List.of(7L), 1, "mixed", null);
        assertTrue(result.get(0).getQuestion().contains("```java\nint n = 1;\n```"));
    }
}
