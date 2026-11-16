package com.jobai.api.config;

import com.jobai.agent.engine.ToolRegistry;
import com.jobai.agent.service.QuizService;
import com.jobai.agent.service.UserMasteryService;
import com.jobai.agent.tool.KnowledgeRetrievalTool;
import com.jobai.api.controller.*;
import com.jobai.common.GlobalExceptionHandler;
import com.jobai.common.JobAiProperties;
import com.jobai.infrastructure.config.ElasticsearchConfig;
import com.jobai.knowledge.config.RetrievalConfigService;
import com.jobai.knowledge.embedding.SiliconFlowEmbeddingModel;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.knowledge.mapper.ChunkMapper;
import com.jobai.knowledge.mapper.QuizQuestionMapper;
import com.jobai.knowledge.retrieval.RerankService;
import com.jobai.knowledge.retrieval.VectorRetrievalService;
import com.jobai.knowledge.service.DocumentProcessor;
import com.jobai.knowledge.service.DocumentService;
import com.jobai.knowledge.service.KnowledgeBaseService;
import com.jobai.knowledge.store.ElasticsearchEmbeddingStoreAdapter;
import com.jobai.knowledge.store.KnowledgeStore;
import com.jobai.rag.admin.EsAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RagFeatureConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(JobAiProperties.class, ElasticsearchConfig.class, SiliconFlowEmbeddingModel.class,
                    KnowledgeStore.class, ElasticsearchEmbeddingStoreAdapter.class, DocumentProcessor.class,
                    DocumentService.class, KnowledgeBaseService.class, VectorRetrievalService.class,
                    RerankService.class, RetrievalConfigService.class, KnowledgeRetrievalTool.class,
                    EsAdminService.class, KnowledgeBaseController.class, DocumentController.class,
                    EsManageController.class, SettingsController.class, ToolRegistry.class, QuizService.class,
                    FeatureController.class, RagDisabledController.class)
            .withBean(LlmService.class, () -> mock(LlmService.class))
            .withBean(QuizQuestionMapper.class, () -> mock(QuizQuestionMapper.class))
            .withBean(ChunkMapper.class, () -> mock(ChunkMapper.class))
            .withBean(UserMasteryService.class, () -> mock(UserMasteryService.class));

    @Test void defaultConfigurationStartsWithoutRagDependencies() {
        context.withPropertyValues("jobai.elasticsearch.ca-certificate=C:/missing/rag-ca.crt").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(QuizService.class).hasSingleBean(RagDisabledController.class);
            assertThat(ctx).doesNotHaveBean(KnowledgeRetrievalTool.class).doesNotHaveBean(KnowledgeBaseService.class)
                    .doesNotHaveBean(SiliconFlowEmbeddingModel.class).doesNotHaveBean(ElasticsearchConfig.class)
                    .doesNotHaveBean(EsAdminService.class).doesNotHaveBean(DocumentProcessor.class);
            assertThat(ctx.getBean(ToolRegistry.class).getToolDefinitions()).isEmpty();
            assertThat(ctx.getBean(ToolRegistry.class).getTools()).isEmpty();
        });
    }

    @Test void disabledEndpointsHaveAnExplicitRecoverableResponse() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RagDisabledController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String path : new String[]{"/api/knowledge-bases", "/api/knowledge-bases/5/documents",
                "/api/documents/6/status", "/api/admin/es/indices", "/api/settings/retrieval"}) {
            mvc.perform(get(path)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("知识库检索未启用")));
        }
    }

    @Test void enabledConfigurationCreatesExplicitClientsAndExposesFeature() {
        new ApplicationContextRunner().withUserConfiguration(ElasticsearchConfig.class)
                .withBean(JobAiProperties.class, JobAiProperties::new)
                .withPropertyValues("jobai.rag.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .hasSingleBean(co.elastic.clients.elasticsearch.ElasticsearchClient.class));
        JobAiProperties properties = new JobAiProperties();
        properties.getRag().setEnabled(true);
        assertThat(new FeatureController(properties).features().getData().ragEnabled()).isTrue();
    }
}
