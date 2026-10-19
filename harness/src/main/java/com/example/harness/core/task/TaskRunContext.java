package com.example.harness.core.task;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** 一轮请求共享的线程安全运行上下文，多 Agent 虚拟线程共同写入。 */
public final class TaskRunContext {
    private final String runId;
    private final String conversationId;
    private volatile Long userMessageId;
    private final String projectId;
    private final String rootFingerprint;
    private final Instant startedAt;
    private final long startedNano;
    private final boolean enabled;
    private final Metrics metrics = new Metrics();
    private final AtomicInteger waiters = new AtomicInteger();
    private final AtomicLong waitStartedNano = new AtomicLong();
    private final AtomicBoolean finalized = new AtomicBoolean();

    public TaskRunContext(String runId, String conversationId, String projectId,
                          String rootFingerprint, Instant startedAt, long startedNano,
                          boolean enabled) {
        this.runId = runId;
        this.conversationId = conversationId;
        this.projectId = projectId;
        this.rootFingerprint = rootFingerprint;
        this.startedAt = startedAt;
        this.startedNano = startedNano;
        this.enabled = enabled;
    }

    public String runId() { return runId; }
    public String conversationId() { return conversationId; }
    public Long userMessageId() { return userMessageId; }
    public void userMessageId(Long id) { this.userMessageId = id; }
    public String projectId() { return projectId; }
    public String rootFingerprint() { return rootFingerprint; }
    public Instant startedAt() { return startedAt; }
    public long startedNano() { return startedNano; }
    public boolean enabled() { return enabled; }
    public Metrics metrics() { return metrics; }
    public boolean tryFinalize() { return finalized.compareAndSet(false, true); }

    public long elapsedMs() {
        return Math.max(0, (System.nanoTime() - startedNano) / 1_000_000L);
    }

    public void markFirstToken() {
        metrics.firstTokenMs.compareAndSet(-1, elapsedMs());
    }

    /** 多个并发等待只统计其时间区间并集。 */
    public boolean beginWait() {
        boolean first = waiters.getAndIncrement() == 0;
        if (first) waitStartedNano.set(System.nanoTime());
        return first;
    }

    public boolean endWait() {
        int left = waiters.updateAndGet(v -> Math.max(0, v - 1));
        if (left == 0) {
            long start = waitStartedNano.getAndSet(0);
            if (start > 0) metrics.approvalWaitMs.add(
                    Math.max(0, (System.nanoTime() - start) / 1_000_000L));
        }
        return left == 0;
    }

    public static final class Metrics {
        final AtomicLong firstTokenMs = new AtomicLong(-1);
        final LongAdder approvalWaitMs = new LongAdder();
        final LongAdder llmDurationMs = new LongAdder();
        final LongAdder toolDurationMs = new LongAdder();
        final LongAdder llmCalls = new LongAdder();
        final LongAdder toolCalls = new LongAdder();
        final LongAdder failedToolCalls = new LongAdder();
        final LongAdder promptTokens = new LongAdder();
        final LongAdder completionTokens = new LongAdder();
        final LongAdder estimatedCalls = new LongAdder();
        final DoubleAdder knownCostUsd = new DoubleAdder();
        final AtomicBoolean unpriced = new AtomicBoolean(false);
        final ConcurrentHashMap<String, LongAdder> phaseDurations = new ConcurrentHashMap<>();

        public Long firstTokenMs() { long v = firstTokenMs.get(); return v < 0 ? null : v; }
        public long approvalWaitMs() { return approvalWaitMs.sum(); }
        public long llmDurationMs() { return llmDurationMs.sum(); }
        public long toolDurationMs() { return toolDurationMs.sum(); }
        public int llmCalls() { return llmCalls.intValue(); }
        public int toolCalls() { return toolCalls.intValue(); }
        public int failedToolCalls() { return failedToolCalls.intValue(); }
        public long promptTokens() { return promptTokens.sum(); }
        public long completionTokens() { return completionTokens.sum(); }
        public int estimatedCalls() { return estimatedCalls.intValue(); }
        public Double costUsd() { return unpriced.get() ? null : knownCostUsd.sum(); }
        public Map<String, Long> phaseDurationsMs() {
            Map<String, Long> out = new LinkedHashMap<>();
            phaseDurations.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> out.put(e.getKey(), e.getValue().sum()));
            return out;
        }
        public void addPhase(String phase, long ms) {
            phaseDurations.computeIfAbsent(phase, k -> new LongAdder()).add(Math.max(0, ms));
        }
    }
}
