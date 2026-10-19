package com.example.harness.llm;

import java.util.List;

/** 对话消息（OpenAI 兼容格式）。 */
public record LlmMessage(String role, String content, List<LlmToolCall> toolCalls, String toolCallId) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null);
    }

    /** assistant 消息（携带工具调用请求）。 */
    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage("assistant", content, toolCalls, null);
    }

    /** 工具执行结果回灌。 */
    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage("tool", content, null, toolCallId);
    }
}
