package com.jobai.knowledge.llm;

import com.jobai.common.JobAiProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 由 DeepSeek API 支持的 LangChain4j ChatModel Bean。
 * DeepSeek 兼容 OpenAI — 使用带自定义 base URL 的 OpenAiChatModel。
 * <p>
 * 温度约定（按场景）：
 * <table>
 *   <tr><td>出题</td><td>0.5</td><td>需要题目多样性，不同轮次不能雷同</td></tr>
 *   <tr><td>评估/打分</td><td>0.0</td><td>需要一致性，同样答案给同样分数</td></tr>
 *   <tr><td>JD 解析</td><td>0.0</td><td>结构化抽取，不需要创意</td></tr>
 *   <tr><td>对话总结</td><td>0.1</td><td>轻微变化即可</td></tr>
 * </table>
 * 默认 Bean 使用 0.0。需要更高温度的调用方自行构建带有场景适当温度的
 * {@code OpenAiChatModel} 实例。
 * <p>
 * 注意：Bean 始终被创建。调用方在运行时通过重试 + 降级处理 API 故障
 * （与 {@link LlmService} 一致）。
 */
@Slf4j
@Configuration
public class LangChain4jConfig {

    private static final String PLACEHOLDER_KEY = "sk-your-key-here";

    private final JobAiProperties.DeepseekProperties props;

    public LangChain4jConfig(JobAiProperties properties) {
        this.props = properties.getDeepseek();
        String key = props.getApiKey();
        if (key == null || key.isBlank() || key.contains(PLACEHOLDER_KEY)) {
            log.warn("DeepSeek API key not configured — LangChain4j calls will fail at runtime");
        }
        log.info("LangChain4j ChatModel ready: baseUrl={}, model={}",
                props.getBaseUrl(), props.getModel());
    }

    /** 默认 Bean（0.0）— 用于评估、JD 解析和结构化提取。 */
    @Bean
    public ChatModel chatLanguageModel() {
        return createChatModel(0.0);
    }

    /**
     * 创建具有特定场景温度的对话模型。
     * 需要非零温度的调用方使用此方法而非注入的 Bean。
     *
     * @param temperature 出题=0.5, 评估=0.0, JD解析=0.0, 总结=0.1
     */
    public ChatModel createChatModel(double temperature) {
        return OpenAiChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModel())
                .temperature(temperature)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(false)
                .build();
    }

    /** 流式对话模型 — 用于 SSE 流式对话场景。 */
    @Bean
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModel())
                .temperature(0.0)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(false)
                .build();
    }
}
