package com.example.harness.llm;

import java.util.List;

/**
 * LLM 对话客户端抽象（OpenAI 兼容协议，SSE 流式）。
 * 实现：OpenAiCompatChatClient（真实端点）/ MockChatClient（无 Key 演示模式）。
 */
public interface ChatClient {

    /**
     * 流式回调：模型增量内容。所有方法均为可选实现。
     */
    interface StreamListener {
        /** 正文增量（delta.content）。 */
        default void onText(String delta) {
        }

        /** 思考增量（delta.reasoning_content / delta.reasoning，模型不支持则不会回调）。 */
        default void onReasoning(String delta) {
        }

        /** function/tool_calls 增量到达；主要用于首响应延迟统计。 */
        default void onToolCallDelta() {
        }
    }

    /**
     * 发起一次对话（非流式便利入口：Planner / 意图分类等内部调用使用）。
     */
    default ChatResult chat(List<LlmMessage> messages, List<LlmToolSpec> tools) {
        return chat(messages, tools, null);
    }

    /**
     * 发起一次对话（流式）。listener 为 null 时仅累积结果、不回调。
     *
     * @param messages 完整上下文（system / user / assistant / tool）
     * @param tools    可用工具描述（function calling）；可为空
     * @param listener 增量回调；可为 null
     * @return 模型回复（累积后的内容/思考或工具调用列表）
     */
    ChatResult chat(List<LlmMessage> messages, List<LlmToolSpec> tools, StreamListener listener);

    /**
     * 客户端实际服务的模型 API 名（P1 成本观测归属用）；Mock 返回 mock，未知实现返回空串。
     */
    default String modelName() {
        return "";
    }
}
