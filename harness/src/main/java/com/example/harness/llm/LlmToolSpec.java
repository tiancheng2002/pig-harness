package com.example.harness.llm;

/** 暴露给 LLM 的工具规格（parametersJson 为 JSON Schema 字符串）。 */
public record LlmToolSpec(String name, String description, String parametersJson) {
}
