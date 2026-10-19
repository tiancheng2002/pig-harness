package com.example.harness.tools;

import java.util.Map;

/**
 * 工具接口（F5）：所有可被 LLM function calling 调用的能力实现此接口。
 */
public interface Tool {

    /** 工具名（LLM function name）。 */
    String name();

    /** 给 LLM 的用途描述。 */
    String description();

    /** 参数 JSON Schema（parameters）。 */
    String parametersJson();

    /** 执行工具。ctx 提供沙箱与工作记忆。 */
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}
