package com.example.harness.core.multi;

import java.util.List;

/**
 * 拆分计划（M4）：parallel=false 时 subtasks 为空，调用方必须走单 Agent 现状路径。
 * 任何降级条件（解析失败/子任务不足/成环/路径重叠/纯依赖链）都落在 parallel=false，绝不抛错。
 */
public record DecomposePlan(boolean parallel, List<SubTask> subtasks, String reason) {

    public static DecomposePlan single(String reason) {
        return new DecomposePlan(false, List.of(), reason);
    }

    public DecomposePlan {
        subtasks = subtasks == null ? List.of() : List.copyOf(subtasks);
    }
}
