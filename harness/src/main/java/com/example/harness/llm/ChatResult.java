package com.example.harness.llm;

import java.util.List;

/** 一次 LLM 回复：文本内容、思考内容（reasoning，可能为 null）或工具调用列表，及可选的用量统计。 */
public record ChatResult(String content, String reasoning, List<LlmToolCall> toolCalls, Usage usage) {

    /** API 回传的真实用量（stream_options.include_usage）；Mock 或端点不支持时为 null。 */
    public record Usage(Integer promptTokens, Integer completionTokens) {
    }

    /** 兼容构造：无思考内容。 */
    public ChatResult(String content, List<LlmToolCall> toolCalls) {
        this(content, null, toolCalls, null);
    }

    /** 兼容构造：带思考内容。 */
    public ChatResult(String content, String reasoning, List<LlmToolCall> toolCalls) {
        this(content, reasoning, toolCalls, null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
