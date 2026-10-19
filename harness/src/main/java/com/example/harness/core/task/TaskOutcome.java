package com.example.harness.core.task;

import java.util.List;
import java.util.Map;

/** 服务器证据构建的结构化任务结论；聊天卡与报告的共同事实源。 */
public record TaskOutcome(
        String status,
        String mode,
        String summary,
        PlanSummary plan,
        List<ChangedFile> changedFiles,
        boolean changeSummaryComplete,
        List<Verification> verifications,
        List<FailedAgent> failedAgents,
        List<String> unresolved,
        List<String> risks,
        Metrics metrics
) {
    public record PlanSummary(int total, int completed, int failed) {}
    public record ChangedFile(String path, String type, Integer additions, Integer deletions,
                              String source) {}
    public record Verification(String name, String status, long durationMs, String evidence) {}
    public record FailedAgent(String agentId, String status, String summary) {}
    public record Metrics(long wallDurationMs, Long firstTokenMs, long activeDurationMs,
                          long approvalWaitMs, long llmDurationMs, long toolDurationMs,
                          int llmCalls, int toolCalls, int failedToolCalls,
                          long promptTokens, long completionTokens, int estimatedCalls,
                          Double costUsd, Map<String, Long> phaseDurationsMs,
                          List<Map<String, Object>> byModel,
                          List<Map<String, Object>> byPhase,
                          List<Map<String, Object>> byAgent) {}
}
