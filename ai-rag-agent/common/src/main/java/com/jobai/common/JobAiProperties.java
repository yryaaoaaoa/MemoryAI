package com.jobai.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jobai")
public class JobAiProperties {

    private ElasticsearchProperties elasticsearch = new ElasticsearchProperties();
    private EmbeddingProperties embedding = new EmbeddingProperties();
    private OllamaProperties ollama = new OllamaProperties();
    private SiliconFlowProperties siliconflow = new SiliconFlowProperties();
    private DeepseekProperties deepseek = new DeepseekProperties();
    private SplitterProperties splitter = new SplitterProperties();
    private RetrievalProperties retrieval = new RetrievalProperties();
    private OcrProperties ocr = new OcrProperties();

    @Data
    public static class ElasticsearchProperties {
        private String host = "localhost";
        private int port = 9200;
        private String scheme = "https";
        private String username = "elastic";
        private String password;
    }

    @Data
    public static class EmbeddingProperties {
        private String provider = "siliconflow";
    }

    @Data
    public static class OllamaProperties {
        private String baseUrl = "http://localhost:11434";
        private String embeddingModel = "bge-m3";
    }

    @Data
    public static class SiliconFlowProperties {
        private String apiKey;
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String model = "BAAI/bge-m3";
    }

    @Data
    public static class DeepseekProperties {
        private String apiKey;
        private String baseUrl = "https://api.deepseek.com/v1";
        private String model = "deepseek-chat";
    }

    @Data
    public static class SplitterProperties {
        private int maxChars = 1024;
        private int overlapChars = 128;
        private String strategy = "recursive";          // recursive | semantic
        private String tokenEstimation = "half";        // half | char4 | raw
    }

    @Data
    public static class RetrievalProperties {
        /** RRF rank_constant */
        private int rankConstant = 60;
        /** numCandidates = topK * numCandidatesFactor */
        private int numCandidatesFactor = 2;
        /** rankWindowSize = topK * rankWindowFactor */
        private int rankWindowFactor = 2;
        /** BM25 multi-match fields, comma-separated (e.g. "content^3,heading_path") */
        private String bm25Fields = "content^3,heading_path";
        /** queries with length <= this value are "short" */
        private int shortQueryMaxLen = 4;
        /** queries with length <= this value are "medium" */
        private int mediumQueryMaxLen = 12;
        /** parameters for short queries (≤ shortQueryMaxLen chars) */
        private RetrievalTier shortQuery = new RetrievalTier(20, 0.25);
        /** parameters for medium queries */
        private RetrievalTier mediumQuery = new RetrievalTier(12, 0.28);
        /** parameters for long queries */
        private RetrievalTier longQuery = new RetrievalTier(8, 0.28);
        /** default parameters used by agent tool etc. */
        private RetrievalTier defaults = new RetrievalTier(3, 0.2);

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class RetrievalTier {
            private int topK = 8;
            private double minScore = 0.28;
        }
    }

    @Data
    public static class OcrProperties {
        private PaddleOcrProperties paddle = new PaddleOcrProperties();
    }

    @Data
    public static class PaddleOcrProperties {
        private String serverUrl;
        private String accessToken;
    }

}
