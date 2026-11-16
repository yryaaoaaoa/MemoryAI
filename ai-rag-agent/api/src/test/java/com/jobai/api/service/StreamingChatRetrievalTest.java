package com.jobai.api.service;

import com.jobai.agent.tool.KnowledgeRetrievalTool;
import com.jobai.common.JobAiProperties;
import com.jobai.rag.service.ChatSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StreamingChatRetrievalTest {
    @Test
    @SuppressWarnings("unchecked")
    void disabledRetrievalSendsPlainQuestionAndPreservesNoteContextWithoutLegacyToolMessages() throws Exception {
        var chats = mock(ChatSessionService.class);
        var service = new StreamingChatService(new JobAiProperties(), null, chats);
        var client = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(
                "data: {\"choices\":[{\"delta\":{\"content\":\"回答\"},\"finish_reason\":\"stop\"}]}\n\n".getBytes(StandardCharsets.UTF_8)));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        ReflectionTestUtils.setField(service, "httpClient", client);
        ReflectionTestUtils.setField(service, "baseUrl", "http://unused.invalid");
        ReflectionTestUtils.setField(service, "apiKey", "test-only");
        ReflectionTestUtils.setField(service, "model", "test-model");
        service.streamChat("继续解释", List.of(
                Map.of("role", "user", "content", "原问题"),
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "old"))),
                Map.of("role", "tool", "content", "旧检索结果", "tool_call_id", "old"),
                Map.of("role", "assistant", "content", "原回答")),
                mock(SseEmitter.class), 1L, 42L, "学习笔记：事务只控制数据库", null, 3).join();
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        var bytes = new java.io.ByteArrayOutputStream();
        var complete = new CompletableFuture<Void>();
        request.getValue().bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { byte[] data = new byte[b.remaining()]; b.get(data); bytes.writeBytes(data); }
            public void onError(Throwable t) { complete.completeExceptionally(t); }
            public void onComplete() { complete.complete(null); }
        });
        complete.join();
        var body = new ObjectMapper().readTree(bytes.toByteArray());
        assertFalse(body.has("tools"));
        var messages = body.path("messages");
        assertEquals(4, messages.size());
        assertTrue(messages.get(0).path("content").asText().contains("事务只控制数据库"));
        assertEquals("继续解释", messages.get(3).path("content").asText());
        assertFalse(body.toString().contains("tool_call"));
        verify(chats).appendReply(eq(1L), eq(42L), eq(3), argThat(reply -> "completed".equals(reply.get("response_status"))));
    }

    @Test
    void historyContainsOnlyRecentPlainTextUserAndAssistantMessages() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(Map.of("role", "system", "content", "system secret"));
        history.add(Map.of("role", "tool", "content", "tool secret"));
        history.add(Map.of("role", "user", "content", List.of("not text")));
        history.add(Map.of("role", "assistant", "content", " "));
        for (int i = 0; i < 8; i++) {
            history.add(Map.of("role", i % 2 == 0 ? "user" : "assistant", "content", "turn-" + i,
                    "reasoning_content", "private reasoning"));
        }
        assertEquals("用户：turn-2\n助手：turn-3\n用户：turn-4\n助手：turn-5\n用户：turn-6\n助手：turn-7",
                StreamingChatService.rewriteHistory(history));
    }

    @Test
    void oversizedHistoryRespectsTotalAndPerMessageBudgetsAndKeepsLatestTurns() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(Map.of("role", "user", "content", "old question"));
        for (int i = 0; i < 10; i++) {
            history.add(Map.of("role", "assistant", "content", "turn-" + i + "x".repeat(10000)));
        }
        String result = StreamingChatService.rewriteHistory(history);
        assertNotNull(result);
        assertTrue(result.length() <= StreamingChatService.REWRITE_HISTORY_MAX_CHARS);
        assertTrue(result.contains("turn-9"));
        assertFalse(result.contains("old question"));
        assertTrue(result.lines().allMatch(line -> line.length() <= 1003));
        assertNull(StreamingChatService.rewriteHistory(null));
        assertNull(StreamingChatService.rewriteHistory(List.of(Map.of("role", "system", "content", "system"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualChatEntryPassesHistoryToRetrievalBeforeGeneratingAnswer() throws Exception {
        KnowledgeRetrievalTool retrieval = mock(KnowledgeRetrievalTool.class);
        when(retrieval.search(anyString(), eq(5), anyString())).thenReturn("retrieved context");
        StreamingChatService service = new StreamingChatService(new JobAiProperties(), retrieval,
                mock(ChatSessionService.class));
        HttpClient client = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream("data: [DONE]\n".getBytes(StandardCharsets.UTF_8)));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        ReflectionTestUtils.setField(service, "httpClient", client);
        ReflectionTestUtils.setField(service, "baseUrl", "http://unused.invalid");
        ReflectionTestUtils.setField(service, "apiKey", "test-only");
        ReflectionTestUtils.setField(service, "model", "test-model");
        List<Map<String, Object>> history = List.of(
                Map.of("role", "user", "content", "解释 Redis 缓存"),
                Map.of("role", "assistant", "content", "Redis 是内存数据库"));

        service.streamChat("那它有什么缺点", history, mock(SseEmitter.class), null, 42L, null, null, null).join();

        verify(retrieval).search("那它有什么缺点", 5, "用户：解释 Redis 缓存\n助手：Redis 是内存数据库");
        verify(client).send(any(), any(HttpResponse.BodyHandler.class));
    }
}
