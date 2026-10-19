package com.example.harness.config;

import com.example.harness.embedding.CachingEmbeddingClient;
import com.example.harness.embedding.EmbeddingClient;
import com.example.harness.embedding.NoopEmbeddingClient;
import com.example.harness.embedding.OpenAiCompatEmbeddingClient;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.MockChatClient;
import com.example.harness.llm.OpenAiCompatChatClient;
import com.example.harness.skill.LlmParameterFiller;
import com.example.harness.skill.MockParameterFiller;
import com.example.harness.skill.ParameterFiller;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置装配 LLM 客户端：
 * - 已配置 API Key → OpenAI 兼容真实端点（默认 DeepSeek）
 * - 未配置 → MockChatClient 本地演示模式（可完整跑通 plan→act→observe→reflect 链路）
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public ChatClient chatClient(HarnessProperties props) {
        String key = props.getLlm().getApiKey();
        if (key == null || key.isBlank()) {
            log.warn("未配置 LLM API Key（HARNESS_LLM_API_KEY），进入 Mock 演示模式");
            return new MockChatClient();
        }
        return new OpenAiCompatChatClient(
                props.getLlm().getBaseUrl(), key, props.getLlm().getModel(),
                props.getContext().isRequestUsage(), props.getLlm().getMaxCompletionTokens());
    }

    @Bean
    public boolean llmMockMode(HarnessProperties props) {
        String key = props.getLlm().getApiKey();
        return key == null || key.isBlank();
    }

    @Bean
    public EmbeddingClient embeddingClient(HarnessProperties props) {
        String key = props.getEmbedding().getApiKey();
        if (key == null || key.isBlank()) {
            // 未配 Key：绝不发起网络请求（否则启动时逐技能连接超时，拖慢启动并刷失败日志）
            log.info("未配置 Embedding API Key（HARNESS_EMBEDDING_API_KEY），技能检索降级为关键词匹配");
            return new NoopEmbeddingClient();
        }
        return new CachingEmbeddingClient(new OpenAiCompatEmbeddingClient(
                props.getEmbedding().getBaseUrl(), key, props.getEmbedding().getModel()));
    }

    @Bean
    public ParameterFiller parameterFiller(ChatClient chatClient, ObjectMapper om, HarnessProperties props) {
        if (llmMockMode(props)) {
            return new MockParameterFiller();
        }
        return new LlmParameterFiller(chatClient, om);
    }
}
