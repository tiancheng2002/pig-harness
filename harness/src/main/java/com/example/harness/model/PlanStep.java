package com.example.harness.model;

/** Planner 拆分出的执行步骤。 */
public record PlanStep(int index, String step, String tool, String reason) {
}
