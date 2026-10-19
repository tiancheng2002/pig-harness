package com.example.harness.llm;

/** 模型发起的一次工具调用。arguments 为 JSON 字符串。 */
public record LlmToolCall(String id, String name, String arguments) {
}
