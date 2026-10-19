package com.example.harness.embedding;

/**
 * 未配置 Embedding API Key 时的占位实现：调用即快速失败，不发起任何网络请求。
 * 技能路由此时降级为关键词匹配（IntentRouter 已按 Key 判空提前分流，此处为兜底）。
 */
public class NoopEmbeddingClient implements EmbeddingClient {

    public static final String MESSAGE =
            "未配置 Embedding API Key（HARNESS_EMBEDDING_API_KEY），向量检索不可用，已降级为关键词匹配";

    @Override
    public float[] embed(String text) {
        throw new IllegalStateException(MESSAGE);
    }
}
