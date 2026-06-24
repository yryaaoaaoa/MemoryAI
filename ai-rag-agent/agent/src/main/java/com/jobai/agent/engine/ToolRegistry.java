package com.jobai.agent.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.agent.tool.*;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final KnowledgeRetrievalTool knowledgeRetrievalTool;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Getter
    private final Map<String, AgentTool<?, ?>> tools = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        register(knowledgeRetrievalTool);
        log.info("Agent tool registry initialized with {} tools: {}", tools.size(), tools.keySet());
    }

    private void register(AgentTool<?, ?> tool) {
        tools.put(tool.getName(), tool);
    }

    @SuppressWarnings("unchecked")
    public <T, R> R execute(String toolName, T params) {
        AgentTool<T, R> tool = (AgentTool<T, R>) tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        return tool.executeWithRetry(params);
    }

    /**
     * Execute a tool from raw JSON args (used in streaming chat where we parse LLM tool calls manually).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public String executeFromJson(String toolName, Map<String, Object> args) {
        AgentTool tool = tools.get(toolName);
        if (tool == null) {
            return "{\"error\":\"Unknown tool: " + toolName + "\"}";
        }
        try {
            Object params = convertArgs(tool, args);
            Object result = tool.executeWithRetry(params);
            if (result instanceof String s) return s;
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("[{}] execution failed: {}", toolName, e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private Object convertArgs(AgentTool<?, ?> tool, Map<String, Object> args) {
        if (tool instanceof KnowledgeRetrievalTool) {
            return new KnowledgeRetrievalTool.Params(
                    (String) args.get("query"),
                    args.containsKey("topK") ? ((Number) args.get("topK")).intValue() : 3);
        }
        throw new IllegalArgumentException("Unknown tool type: " + tool.getClass().getSimpleName());
    }

    public List<Map<String, Object>> getToolDefinitions() {
        return List.of(
                toolDef("knowledgeRetrieval", "搜索知识库获取相关内容。当用户询问具体技术问题时使用",
                        Map.of("query", Map.of("type", "string", "description", "搜索查询，如 'Java 线程池参数'"),
                               "topK", Map.of("type", "integer", "description", "返回结果数量，默认 3")))
        );
    }

    private Map<String, Object> toolDef(String name, String description, Map<String, Object> properties,
                                         String... requiredKeys) {
        List<String> required = requiredKeys.length > 0
                ? List.of(requiredKeys)
                : properties.keySet().stream().toList();
        return Map.of("type", "function",
                "function", Map.of("name", name, "description", description,
                        "parameters", Map.of("type", "object", "properties", properties, "required", required)));
    }

    public List<String> getToolDescriptions() {
        return tools.values().stream()
                .map(t -> String.format("  - %s: %s", t.getName(), t.getDescription()))
                .toList();
    }
}
