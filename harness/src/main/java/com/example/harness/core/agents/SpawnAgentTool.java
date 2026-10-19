package com.example.harness.core.agents;

import com.example.harness.tools.Tool;
import com.example.harness.tools.ToolContext;
import com.example.harness.tools.ToolResult;

import java.util.Map;

/**
 * spawnAgent 工具定义（子智能体体系）：仅作为 LLM 工具清单中的 spec 存在，
 * 真正的派发由 AgentLoop.runLoop 特判处理（需要循环内上下文：emitter/history/transcript 等），
 * 这里 execute 永远不应被调用（返回错误兜底）。
 */
public class SpawnAgentTool implements Tool {

    public static final String NAME = "spawnAgent";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "派出一个子智能体独立完成某个专项任务，其结论作为工具结果返回。"
                + "agent=子智能体名称（见系统提示中的可用清单）；task=给它的自包含任务描述"
                + "（要具体：目标、涉及路径、期望产出）。适合：项目代码调研、成块的专业子工作；"
                + "简单的单文件写入不要派出，自己做更快。";
    }

    @Override
    public String parametersJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "agent": { "type": "string", "description": "子智能体名称，如：代码探索" },
                    "task": { "type": "string", "description": "交给子智能体的自包含任务描述（它看不到你们的对话历史）" }
                  },
                  "required": ["agent", "task"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        return ToolResult.error("spawnAgent 由系统内部处理（不应直接执行）");
    }
}
