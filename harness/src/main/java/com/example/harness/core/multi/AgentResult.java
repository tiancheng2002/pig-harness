package com.example.harness.core.multi;

import com.example.harness.model.ToolCall;

import java.util.List;

/**
 * 子 Agent 上报（M4）：WorkingMemory 的内容在此压缩后交给父层聚合与黑板登记。
 * status ∈ success / partial（有失败步骤但正常收尾）/ failed（异常、超时）/ cancelled。
 */
public record AgentResult(String agentId,
                          String subTaskId,
                          String status,
                          String summary,
                          List<String> producedFiles,
                          List<ToolCall> steps) {

    public AgentResult {
        producedFiles = producedFiles == null ? List.of() : List.copyOf(producedFiles);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public boolean ok() {
        return "success".equals(status) || "partial".equals(status);
    }
}
