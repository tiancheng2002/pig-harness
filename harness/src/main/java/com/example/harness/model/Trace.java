package com.example.harness.model;

/** 执行轨迹（对应 SQLite traces 表）。 */
public record Trace(Long id, String task, String createdAt, String steps, String outcome) {

    public static Trace of(String task, String stepsJson, String outcome) {
        return new Trace(null, task, java.time.LocalDateTime.now().toString(), stepsJson, outcome);
    }
}
