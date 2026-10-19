package com.example.harness.model;

/** 一次工具调用记录（写入轨迹）。 */
public record ToolCall(String name, java.util.Map<String, Object> args, String result, boolean ok) {
}
