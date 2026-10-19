package com.example.harness.tools;

/** 工具执行结果：ok + 文本输出（回灌 LLM 上下文）+ 产出文件（用于 file 事件）。 */
public record ToolResult(boolean ok, String output, String file) {

    public static ToolResult success(String output) {
        return new ToolResult(true, output, null);
    }

    public static ToolResult success(String output, String file) {
        return new ToolResult(true, output, file);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, message, null);
    }

    /** 回灌 LLM 的文本形式（失败显式标记 ERROR，便于模型反思）。 */
    public String toContextString() {
        return ok ? output : "ERROR: " + output;
    }
}
