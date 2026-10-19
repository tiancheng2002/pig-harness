package com.example.harness.model;

/** 会话内一条持久化消息：role=user/assistant；events 为该轮 Agent 事件流 JSON；trace_id 关联轨迹。 */
public record StoredMessage(Long id, String conversationId, String role, String content,
                            String events, Long traceId, String taskRunId, String createdAt) {
}
