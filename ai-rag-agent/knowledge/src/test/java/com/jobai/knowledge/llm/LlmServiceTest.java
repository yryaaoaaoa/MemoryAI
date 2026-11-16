package com.jobai.knowledge.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.common.JobAiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LlmServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private HttpServer server;
    private LlmService service;
    private String content = "完整回答\n```java\nint count = 1;\n```";
    private String finishReason = "stop";

    @BeforeEach void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = mapper.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("finish_reason", finishReason,
                            "message", Map.of("role", "assistant", "content", content))),
                    "usage", Map.of("prompt_tokens", 100, "completion_tokens", 4096, "total_tokens", 4196)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        JobAiProperties properties = new JobAiProperties();
        properties.getDeepseek().setApiKey("local-test-key");
        properties.getDeepseek().setModel("local-test-model");
        properties.getDeepseek().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        service = new LlmService(properties);
    }

    @AfterEach void tearDown() {
        server.stop(0);
    }

    @Test void ordinaryChatKeepsDefaultBudgetAndCompleteContent() {
        assertEquals(content, service.chat("系统提示", "问题"));
        assertEquals(4096, received.get().get("max_tokens").asInt());
        assertEquals("local-test-model", received.get().get("model").asText());
        assertEquals("系统提示", received.get().at("/messages/0/content").asText());
        assertEquals("问题", received.get().at("/messages/1/content").asText());
    }

    @Test void callerCanSupplyLargerOutputBudget() {
        assertEquals(content, service.chat("出题", "生成十道题", 12288));
        assertEquals(12288, received.get().get("max_tokens").asInt());
        assertEquals(0.0, received.get().get("temperature").asDouble());
    }

    @Test void lengthFinishReasonRejectsEvenSyntacticallyValidJson() {
        content = "[{\"question\":\"仅有第一题\"}]";
        finishReason = "length";
        var error = assertThrows(LlmService.OutputTruncatedException.class,
                () -> service.chat("出题", "生成十道题", 12288));
        assertTrue(error.getMessage().contains("长度上限"));
        assertFalse(error.getMessage().contains(content));
    }

    @Test void lengthFinishReasonRejectsPartialJsonBeforeCallersTryParsing() {
        content = "[{\"question\":\"被截断的题目";
        finishReason = "length";
        assertThrows(LlmService.OutputTruncatedException.class,
                () -> service.chat("出题", "生成十道题"));
    }
}
