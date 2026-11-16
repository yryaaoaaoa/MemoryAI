package com.jobai.api.service;

import com.jobai.agent.tool.KnowledgeRetrievalTool;
import com.jobai.common.JobAiProperties;
import com.jobai.knowledge.mapper.ResumeMapper;
import com.jobai.rag.entity.ChatMessage;
import com.jobai.rag.entity.ChatSession;
import com.jobai.rag.mapper.ChatMessageMapper;
import com.jobai.rag.mapper.ChatSessionMapper;
import com.jobai.rag.service.ChatCompressionService;
import com.jobai.rag.service.ChatSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamingChatOrderTest {
    @Test @SuppressWarnings("unchecked")
    void stoppedSlowTurnRemainsBeforeASecondTurnThatFinishesFirst() throws Exception {
        var sessions = mock(ChatSessionMapper.class);
        var messages = mock(ChatMessageMapper.class);
        var session = new ChatSession();
        session.setId(1L); session.setUserId(10L);
        when(sessions.selectForUpdate(1L)).thenReturn(session);
        when(sessions.selectById(1L)).thenReturn(session);
        var rows = Collections.synchronizedList(new ArrayList<ChatMessage>());
        when(messages.insert(any(ChatMessage.class))).thenAnswer(i -> { rows.add(i.getArgument(0)); return 1; });
        when(messages.selectMaxReservedOrder(1L)).thenAnswer(i -> rows.stream()
                .mapToInt(m -> m.getMessageOrder() + ("user".equals(m.getRole()) ? 1 : 0)).max().stream().boxed().findFirst().orElse(null));
        when(messages.countUserMessages(1L)).thenAnswer(i -> (int) rows.stream().filter(m -> "user".equals(m.getRole())).count());
        when(messages.findByMinOrder(eq(1L), anyInt())).thenAnswer(i -> rows.stream().sorted(Comparator.comparingInt(ChatMessage::getMessageOrder)).toList());
        var chats = new ChatSessionService(sessions, messages, mock(ChatCompressionService.class), mock(ResumeMapper.class));
        var retrieval = mock(KnowledgeRetrievalTool.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(retrieval.search(eq("first"), eq(5), any())).thenAnswer(i -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test did not release retrieval");
            return "context";
        });
        var properties = new JobAiProperties(); properties.getRag().setEnabled(true);
        var service = new StreamingChatService(properties, retrieval, chats);
        var http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(("data: {\"choices\":[{\"delta\":{\"content\":\"second answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        ReflectionTestUtils.setField(service, "httpClient", http);
        ReflectionTestUtils.setField(service, "baseUrl", "http://unused.invalid");
        ReflectionTestUtils.setField(service, "apiKey", "test");
        ReflectionTestUtils.setField(service, "model", "test");
        var firstEmitter = mock(SseEmitter.class);
        var stopFirst = new AtomicReference<Runnable>();
        doAnswer(i -> { stopFirst.set(i.getArgument(0)); return null; }).when(firstEmitter).onCompletion(any(Runnable.class));
        var firstTurn = chats.beginUserTurn(1L, 10L, "first");
        var first = CompletableFuture.runAsync(() -> service.streamChat("first", List.of(), firstEmitter, 1L, 10L, "", "", firstTurn.replyOrder(), true));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            stopFirst.get().run();
            var secondTurn = chats.beginUserTurn(1L, 10L, "second");
            service.streamChat("second", List.of(), mock(SseEmitter.class), 1L, 10L, "", "", secondTurn.replyOrder()).join();
        } finally { release.countDown(); }
        first.get(5, TimeUnit.SECONDS);
        var ordered = rows.stream().sorted(Comparator.comparingInt(ChatMessage::getMessageOrder)).toList();
        assertEquals(List.of("user", "assistant", "user", "assistant"), ordered.stream().map(ChatMessage::getRole).toList());
        assertEquals("first", ordered.get(0).getContent());
        assertTrue(ordered.get(1).getMetadata().contains("stopped"));
        assertEquals("second", ordered.get(2).getContent());
        assertEquals("second answer", ordered.get(3).getContent());
        // A subsequent normal turn must not reuse either reserved slot.
        assertEquals(5, chats.beginUserTurn(1L, 10L, "third").replyOrder());
    }
}
