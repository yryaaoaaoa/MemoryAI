package com.jobai.knowledge.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaddleOcrService {

    private final JobAiOcrProperties properties;
    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String ocrImage(byte[] imageBytes, String fileName) {
        if (properties.getServerUrl().isBlank() || properties.getAccessToken().isBlank()) {
            log.debug("OCR not configured, skipping {}", fileName);
            return "";
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String url = properties.getServerUrl() + "/layout-parsing";

            Map<String, Object> body = Map.of(
                    "file", base64,
                    "fileType", 1,
                    "useDocOrientationClassify", true
            );

            String response = restClient.post()
                    .uri(url)
                    .header("Authorization", "token " + properties.getAccessToken())
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode result = root.get("result");
            if (result == null) {
                log.warn("No result field in OCR response for {}: {}", fileName, response);
                return "";
            }

            // Extract text from layout-parsing response
            JsonNode ocrResults = result.get("ocrResults");
            if (ocrResults != null && ocrResults.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : ocrResults) {
                    JsonNode pruned = item.get("prunedResult");
                    if (pruned != null) {
                        JsonNode recTexts = pruned.get("rec_texts");
                        if (recTexts != null && recTexts.isArray()) {
                            for (JsonNode t : recTexts) {
                                String text = t.asText();
                                if (!text.isBlank()) {
                                    if (sb.length() > 0) sb.append('\n');
                                    sb.append(text);
                                }
                            }
                        }
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }

            log.warn("Unexpected OCR response format for {}: {}", fileName, response);
            return "";
        } catch (Exception e) {
            log.error("OCR failed for {}: {}", fileName, e.getMessage());
            return "";
        }
    }
}
