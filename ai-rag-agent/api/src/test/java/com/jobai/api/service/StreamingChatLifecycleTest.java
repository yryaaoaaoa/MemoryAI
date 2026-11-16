package com.jobai.api.service;

import com.jobai.agent.tool.KnowledgeRetrievalTool;
import com.jobai.common.JobAiProperties;
import com.jobai.rag.service.ChatSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StreamingChatLifecycleTest {
    private final ChatSessionService chats = mock(ChatSessionService.class);
    private final SseEmitter emitter = mock(SseEmitter.class);
    private static final String TOKEN = "data: {\"choices\":[{\"delta\":{\"content\":\"部分回答\"}}]}\n\n";

    @SuppressWarnings("unchecked")
    private StreamingChatService service(String stream, int httpStatus) throws Exception {
        var retrieval = mock(KnowledgeRetrievalTool.class);
        var service = new StreamingChatService(new JobAiProperties(), retrieval, chats);
        var client = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(httpStatus);
        when(response.body()).thenReturn(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        ReflectionTestUtils.setField(service, "httpClient", client);
        ReflectionTestUtils.setField(service, "baseUrl", "http://unused.invalid");
        ReflectionTestUtils.setField(service, "apiKey", "test");
        ReflectionTestUtils.setField(service, "model", "test");
        return service;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> saved() {
        ArgumentCaptor<Map<String, Object>> captured = ArgumentCaptor.forClass(Map.class);
        verify(chats).appendReply(eq(5L), eq(42L), eq(1), captured.capture());
        return captured.getValue();
    }

    @Test void normalReplyIsSavedBeforeCompletionAndRemainsSuccessful() throws Exception {
        service(TOKEN + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", 200)
                .streamChat("问题", List.of(), emitter, 5L, 42L, "", "", 1).join();
        assertEquals("completed", saved().get("response_status"));
        assertEquals("部分回答", saved().get("content"));
        var order = inOrder(chats, emitter);
        order.verify(chats).appendReply(anyLong(), anyLong(), anyInt(), anyMap());
        order.verify(emitter).complete();
    }

    @Test void lengthLimitKeepsPartialAnswerWithTruncatedStatus() throws Exception {
        service(TOKEN + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n", 200)
                .streamChat("问题", List.of(), emitter, 5L, 42L, "", "", 1).join();
        assertEquals("truncated", saved().get("response_status"));
        assertEquals("部分回答", saved().get("content"));
        assertNotNull(saved().get("error_message"));
        verify(chats, never()).compressIfNeeded(anyLong());
    }

    @Test void unexpectedEndIsNotPresentedAsSuccessfulCompletion() throws Exception {
        service(TOKEN, 200).streamChat("问题", List.of(), emitter, 5L, 42L, "", "", 1).join();
        assertEquals("failed", saved().get("response_status"));
        assertEquals("部分回答", saved().get("content"));
    }

    @Test void browserStopStillSavesThePartialReply() throws Exception {
        doThrow(new IOException("client disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        service(TOKEN, 200).streamChat("问题", List.of(), emitter, 5L, 42L, "", "", 1).join();
        assertEquals("stopped", saved().get("response_status"));
        assertEquals("部分回答", saved().get("content"));
    }

    @Test void modelHttpFailureIsRecordedWithoutLeakingProviderResponse() throws Exception {
        service("sensitive provider response", 503).streamChat("问题", List.of(), emitter, 5L, 42L, "", "", 1).join();
        assertEquals("failed", saved().get("response_status"));
        assertFalse(saved().get("content").toString().contains("sensitive"));
    }
}
