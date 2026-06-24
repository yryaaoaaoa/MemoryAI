package com.jobai.knowledge.embedding;

import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.JobAiProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "jobai.embedding.provider", havingValue = "siliconflow", matchIfMissing = true)
public class SiliconFlowEmbeddingModel implements EmbeddingModel {

    private final RestClient restClient;
    private final String model;
    private volatile Integer cachedDimension;

    public SiliconFlowEmbeddingModel(JobAiProperties properties) {
        JobAiProperties.SiliconFlowProperties sf = properties.getSiliconflow();
        this.model = sf.getModel();
        this.restClient = RestClient.builder()
                .baseUrl(sf.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + sf.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<String> texts = textSegments.stream()
                .map(TextSegment::text)
                .toList();

        Map<String, Object> request = Map.of("model", model, "input", texts);

        Map<String, Object> response = restClient.post()
                .uri("/embeddings")
                .body(request)
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("data") == null) {
            throw new BusinessException(ErrorCode.EMBEDDING_FAILED);
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        List<Embedding> embeddings = new ArrayList<>(data.size());
        for (Map<String, Object> entry : data) {
            List<Double> raw = (List<Double>) entry.get("embedding");
            float[] vec = new float[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                vec[i] = raw.get(i).floatValue();
            }
            embeddings.add(new Embedding(vec));
        }

        if (cachedDimension == null && !embeddings.isEmpty()) {
            cachedDimension = embeddings.get(0).dimension();
        }

        return Response.from(embeddings);
    }

    @Override
    public int dimension() {
        if (cachedDimension == null) {
            embedAll(List.of(TextSegment.from(".")));
        }
        return cachedDimension != null ? cachedDimension : 0;
    }
}
