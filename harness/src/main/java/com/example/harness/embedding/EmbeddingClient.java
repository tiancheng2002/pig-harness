package com.example.harness.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 客户端抽象 — M2 阶段用于技能检索路由；P1 代码语义索引复用（批量接口）。
 * 实现：OpenAiCompatEmbeddingClient（真实端点）+ CachingEmbeddingClient（缓存装饰）。
 */
public interface EmbeddingClient {

    /** 返回文本的稠密向量。 */
    float[] embed(String text);

    /**
     * P1：批量向量化（代码索引建库用）。默认逐条循环；OpenAI 兼容端点重写为 input 数组单次请求。
     * 返回顺序与输入一致；任一条失败抛异常（调用方决定降级策略）。
     */
    default List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embed(t));
        }
        return out;
    }
}
