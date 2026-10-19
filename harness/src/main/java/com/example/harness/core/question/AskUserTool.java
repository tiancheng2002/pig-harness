package com.example.harness.core.question;

import com.example.harness.tools.Tool;
import com.example.harness.tools.ToolContext;
import com.example.harness.tools.ToolResult;

import java.util.Map;

/**
 * askUser 工具定义（计划/TASK 模式澄清提问）：仅作为 LLM 工具清单中的 spec 存在，
 * 真正的挂起/应答由 AgentLoop.runLoop 特判处理（需要循环内上下文与 QuestionGate），
 * execute 永远不应被调用（返回错误兜底），也绝不注册进普通 ToolRegistry。
 */
public class AskUserTool implements Tool {

    public static final String NAME = "askUser";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "发现任务描述与仓库事实存在实质冲突时向用户提问澄清。"
                + "question=问题本身（一句话说清背景与分歧点）；options=2~4 个候选项；"
                + "recommended=推荐项在 options 中的下标（最符合用户利益时才标推荐）。"
                + "用户也可以自由输入；未答复时停止任务，不得擅自改修其它缺陷。";
    }

    @Override
    public String parametersJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "question": { "type": "string", "description": "要澄清的问题（说明背景与分歧点）" },
                    "options": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "2~4 个候选项，每项一句话；推荐项放最前"
                    },
                    "recommended": { "type": "integer", "description": "推荐项在 options 中的下标（从 0 开始）" }
                  },
                  "required": ["question"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        return ToolResult.error("askUser 由系统内部处理（不应直接执行）");
    }
}
