package com.example.harness.core.multi;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.AgentLoop;
import com.example.harness.core.task.ExecutionResult;
import com.example.harness.core.task.RunStatus;
import com.example.harness.core.memory.EpisodicStore;
import com.example.harness.core.memory.WorkingMemory;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.LlmMessage;
import com.example.harness.model.Knowledge;
import com.example.harness.model.PlanStep;
import com.example.harness.model.Task;
import com.example.harness.model.ToolCall;
import com.example.harness.model.Trace;
import com.example.harness.sandbox.Sandbox;
import com.example.harness.tools.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多 Agent 编排器（M4）：计划期 DAG 已由 Planner.decompose 确定，这里只执行——
 * 同波子任务虚拟线程并行扇出 → 收齐 AgentResult → 一次 LLM 流式聚合 → done/trace/persist。
 * 设计原则：单一事实源（事件流带 agent 字段 + 父 traces 一条，无第二张表）；
 * 黑板只做登记与占坑，不做运行时数据传输；取消（断连/停止）经 sharedCancel 传播到全部子 Agent。
 */
@Component
public class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final AgentLoop agentLoop;
    private final EpisodicStore episodicStore;
    private final ObjectMapper om;
    private final HarnessProperties props;
    private final WorktreeManager worktreeManager;
    private final com.example.harness.core.task.LlmCallTracker llmCallTracker;

    public Orchestrator(AgentLoop agentLoop,
                        EpisodicStore episodicStore,
                        ObjectMapper om,
                        HarnessProperties props,
                        WorktreeManager worktreeManager,
                        com.example.harness.core.task.LlmCallTracker llmCallTracker) {
        this.agentLoop = agentLoop;
        this.episodicStore = episodicStore;
        this.om = om;
        this.props = props;
        this.worktreeManager = worktreeManager;
        this.llmCallTracker = llmCallTracker;
    }

    /** 多 Agent 主入口。调用方（TaskDispatcher）已保证 subtasks 通过拆分触发门槛。 */
    public ExecutionResult execute(Task task, SseEmitter sse, ChatClient client, List<Knowledge> knowledge,
                        Sandbox sandbox, List<LlmMessage> history,
                        List<Map<String, Object>> transcript, List<SubTask> subtasks) {
        return execute(task, sse, client, knowledge, sandbox, history, transcript, subtasks, null, null);
    }

    /** 带 Hub 的会话入口（体验升级七）：事件进会话缓冲，取消经 hub.cancelFlag 显式传播。 */
    public ExecutionResult execute(Task task, SseEmitter sse, ChatClient client, List<Knowledge> knowledge,
                        Sandbox sandbox, List<LlmMessage> history,
                        List<Map<String, Object>> transcript, List<SubTask> subtasks,
                        com.example.harness.core.stream.StreamHub.Hub hub) {
        return execute(task, sse, client, knowledge, sandbox, history, transcript, subtasks, null, hub);
    }

    public ExecutionResult execute(Task task, SseEmitter sse, ChatClient client, List<Knowledge> knowledge,
                        Sandbox sandbox, List<LlmMessage> history,
                        List<Map<String, Object>> transcript, List<SubTask> subtasks,
                        com.example.harness.core.task.TaskRunContext runContext,
                        com.example.harness.core.stream.StreamHub.Hub hub) {
        AgentLoop.Emitter out = new AgentLoop.Emitter(sse, transcript,
                hub != null ? hub.cancelFlag : null, null, hub);
        // hub 存在时直接复用其取消标志（/stop 一处置位，波次/子 Agent/主循环全部联动）
        AtomicBoolean sharedCancel = hub != null ? hub.cancelFlag : new AtomicBoolean(false);
        SharedBlackboard blackboard = new SharedBlackboard();
        Map<String, String> agentIds = new LinkedHashMap<>();
        for (int i = 0; i < subtasks.size(); i++) {
            agentIds.put(subtasks.get(i).id(), "sub-" + (i + 1));
        }
        Map<String, AgentResult> results = new ConcurrentHashMap<>();
        // P1 worktree 物理隔离：开关开启且项目为 git 仓库时启用；懒创建于各 runOne（上游合并对下游可见）
        boolean worktreeMode = props.getMultiAgent().isWorktreeIsolation()
                && worktreeManager.isGitRoot(sandbox.root());
        if (props.getMultiAgent().isWorktreeIsolation() && !worktreeMode) {
            log.info("[{}] worktree 隔离开关开启但项目根不是 git 仓库，回退黑板占坑模式", task.id());
        }
        List<String> mergeConflicts = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            // ① 拆分计划展示（父层可观察；依赖与输出路径标注在 reason）
            out.send(Map.of("type", "plan", "steps", planSteps(subtasks)));
            log.info("[{}] 多 Agent 扇出：{} 个子任务（{}，隔离={}）", task.id(), subtasks.size(),
                    agentIds.values(), worktreeMode ? "git worktree" : "黑板占坑");

            // ② 波次调度（Kahn 分层已校验无环）
            runWaves(task, sse, client, knowledge, sandbox, history, transcript,
                    subtasks, agentIds, results, blackboard, sharedCancel, out, hub,
                    worktreeMode, mergeConflicts, runContext);

            // ③ 聚合（取消时不聚合，结论为已停止）
            String conclusion = sharedCancel.get()
                    ? "（已停止）"
                    : aggregate(llmCallTracker.tracked(client, runContext, "aggregator", null),
                            task, subtasks, agentIds, results, blackboard, out);
            if (!mergeConflicts.isEmpty()) {
                conclusion = conclusion + "\n\n⚠️ **合并冲突（以子任务版本为准）**："
                        + String.join("、", mergeConflicts.stream().distinct().toList());
            }

            // ④ 父 trace 一条（steps = 各子 Agent ToolCall 合并，带 agent 标注）
            Long traceId = null;
            try {
                traceId = episodicStore.save(Trace.of(task.prompt(),
                        om.writeValueAsString(mergedSteps(subtasks, agentIds, results)), conclusion));
            } catch (Exception e) {
                log.warn("父轨迹写入失败: {}", e.getMessage());
            }
            log.info("[{}] 多 Agent 任务完成: {}", task.id(), truncate(conclusion, 100));
            if (sharedCancel.get()) return ExecutionResult.stopped(conclusion, traceId, "MULTI_AGENT");
            boolean partial = results.values().stream().anyMatch(r -> !"success".equals(r.status()));
            return new ExecutionResult(partial ? RunStatus.PARTIAL : RunStatus.SUCCESS,
                    conclusion, traceId, "MULTI_AGENT", null, null, false);
        } catch (Exception e) {
            log.error("[{}] 多 Agent 任务执行异常", task.id(), e);
            String conclusion = "任务执行异常：" + e.getMessage();
            Long traceId = null;
            try {
                traceId = episodicStore.save(Trace.of(task.prompt(),
                        om.writeValueAsString(mergedSteps(subtasks, agentIds, results)),
                        "ERROR: " + e.getMessage()));
            } catch (Exception ignore) {
                log.warn("异常轨迹写入失败: {}", ignore.getMessage());
            }
            return ExecutionResult.failed(conclusion, traceId, "MULTI_AGENT", "ORCHESTRATOR_ERROR", e.getMessage());
        }
    }

    // --------------------------------------------------------------- 波次调度

    private void runWaves(Task task, SseEmitter sse, ChatClient client, List<Knowledge> knowledge,
                          Sandbox sandbox, List<LlmMessage> history, List<Map<String, Object>> transcript,
                          List<SubTask> subtasks, Map<String, String> agentIds,
                          Map<String, AgentResult> results, SharedBlackboard blackboard,
                          AtomicBoolean sharedCancel, AgentLoop.Emitter out,
                          com.example.harness.core.stream.StreamHub.Hub hub,
                          boolean worktreeMode, List<String> mergeConflicts,
                          com.example.harness.core.task.TaskRunContext runContext) {
        long deadline = System.currentTimeMillis()
                + props.getMultiAgent().getSubAgentTimeoutSeconds() * 1000L;
        Semaphore permits = new Semaphore(Math.max(1, props.getMultiAgent().getMaxConcurrency()));
        List<SubTask> pending = new ArrayList<>(subtasks);
        // 子任务 → plan 事件中的序号（planSteps 的 1..N），agent_done 时同步推进计划进度
        Map<String, Integer> planIdx = new LinkedHashMap<>();
        for (int i = 0; i < subtasks.size(); i++) {
            planIdx.put(subtasks.get(i).id(), i + 1);
        }

        while (!pending.isEmpty()) {
            if (sharedCancel.get()) {
                return;
            }
            List<SubTask> wave = new ArrayList<>();
            List<SubTask> skip = new ArrayList<>();
            for (SubTask st : pending) {
                boolean allPresent = st.dependsOn().stream().allMatch(results::containsKey);
                boolean anyFailed = st.dependsOn().stream()
                        .anyMatch(d -> results.containsKey(d) && !results.get(d).ok());
                if (anyFailed) {
                    skip.add(st);
                } else if (allPresent) {
                    wave.add(st);
                }
            }
            if (!skip.isEmpty()) {
                for (SubTask st : skip) {
                    AgentResult r = new AgentResult(agentIds.get(st.id()), st.id(), "failed",
                            "上游子任务失败，本子任务未启动", List.of(), List.of());
                    results.put(st.id(), r);
                    emitAgentDone(out, r);
                    emitPlanStep(out, r, planIdx);
                }
                pending.removeAll(skip);
                continue;
            }
            if (wave.isEmpty()) {
                log.warn("[{}] 波次调度无就绪子任务（异常状态），剩余 {} 个", task.id(), pending.size());
                return;
            }
            runWave(task, sse, client, knowledge, sandbox, history, transcript,
                    wave, agentIds, results, blackboard, sharedCancel, out, permits, deadline, planIdx, hub,
                    worktreeMode, mergeConflicts, runContext);
            pending.removeAll(wave);
        }
    }

    /** 同波子任务并行扇出；整体 deadline 内未完成的 future.cancel(true) 中断。 */
    private void runWave(Task task, SseEmitter sse, ChatClient client, List<Knowledge> knowledge,
                         Sandbox sandbox, List<LlmMessage> history, List<Map<String, Object>> transcript,
                         List<SubTask> wave, Map<String, String> agentIds,
                         Map<String, AgentResult> results, SharedBlackboard blackboard,
                         AtomicBoolean sharedCancel, AgentLoop.Emitter out,
                         Semaphore permits, long deadline, Map<String, Integer> planIdx,
                         com.example.harness.core.stream.StreamHub.Hub hub,
                         boolean worktreeMode, List<String> mergeConflicts,
                         com.example.harness.core.task.TaskRunContext runContext) {
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        Map<String, Future<AgentResult>> futures = new LinkedHashMap<>();
        try {
            for (SubTask st : wave) {
                String agentId = agentIds.get(st.id());
                futures.put(st.id(), ex.submit(() -> runOne(task, sse, client, knowledge,
                        sandbox, history, transcript, st, agentId, results, blackboard,
                        sharedCancel, out, permits, hub, worktreeMode, mergeConflicts, runContext)));
            }
            for (Map.Entry<String, Future<AgentResult>> e : futures.entrySet()) {
                SubTask st = wave.stream().filter(s -> s.id().equals(e.getKey())).findFirst().orElseThrow();
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    e.getValue().cancel(true);
                    finish(e.getKey(), st, new AgentResult(agentIds.get(e.getKey()), e.getKey(),
                            "failed", "执行超时（整体 deadline " + props.getMultiAgent()
                            .getSubAgentTimeoutSeconds() + "s）", List.of(), List.of()), results, out, planIdx);
                    continue;
                }
                try {
                    finish(e.getKey(), st, e.getValue().get(remaining, TimeUnit.MILLISECONDS), results, out, planIdx);
                } catch (TimeoutException te) {
                    e.getValue().cancel(true);
                    finish(e.getKey(), st, new AgentResult(agentIds.get(e.getKey()), e.getKey(),
                            "failed", "执行超时（整体 deadline "
                                    + props.getMultiAgent().getSubAgentTimeoutSeconds() + "s）",
                            List.of(), List.of()), results, out, planIdx);
                } catch (ExecutionException ee) {
                    Throwable c = ee.getCause() == null ? ee : ee.getCause();
                    finish(e.getKey(), st, new AgentResult(agentIds.get(e.getKey()), e.getKey(),
                            "failed", "执行异常：" + truncate(String.valueOf(c.getMessage()), 200),
                            List.of(), List.of()), results, out, planIdx);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    e.getValue().cancel(true);
                    finish(e.getKey(), st, new AgentResult(agentIds.get(e.getKey()), e.getKey(),
                            "failed", "被中断", List.of(), List.of()), results, out, planIdx);
                    return;
                }
            }
        } finally {
            ex.close();
        }
    }

    /** 结果入表 + agent_done / plan_step 事件（超时/中断的兜底结果同样发出）。 */
    private void finish(String subTaskId, SubTask st, AgentResult r,
                        Map<String, AgentResult> results, AgentLoop.Emitter out,
                        Map<String, Integer> planIdx) {
        results.put(subTaskId, r);
        emitAgentDone(out, r);
        emitPlanStep(out, r, planIdx);
        log.info("[{}] 子 Agent {}（{}）完成: {} — {}",
                st.id(), r.agentId(), r.status(), truncate(st.description(), 40),
                truncate(r.summary(), 60));
    }

    /** 子任务完成 → 父层计划进度同步推进（done=成功/部分成功，failed=失败/超时，停止则保持原状）。 */
    private void emitPlanStep(AgentLoop.Emitter out, AgentResult r, Map<String, Integer> planIdx) {
        Integer idx = planIdx.get(r.subTaskId());
        if (idx == null) {
            return;
        }
        String status = switch (r.status()) {
            case "success", "partial" -> "done";
            case "cancelled" -> null;
            default -> "failed";
        };
        if (status != null) {
            out.send(Map.of("type", "plan_step", "index", idx, "status", status));
        }
    }

    /** 单个子 Agent：占坑分配路径 → 独立 WorkingMemory/ToolContext → 上游产物注入 → 复用 AgentLoop。 */
    private AgentResult runOne(Task parent, SseEmitter sse, ChatClient client,
                               List<Knowledge> knowledge, Sandbox sandbox,
                               List<LlmMessage> history, List<Map<String, Object>> transcript,
                               SubTask st, String agentId, Map<String, AgentResult> results,
                               SharedBlackboard blackboard, AtomicBoolean sharedCancel,
                               AgentLoop.Emitter out, Semaphore permits,
                               com.example.harness.core.stream.StreamHub.Hub hub,
                               boolean worktreeMode, List<String> mergeConflicts,
                               com.example.harness.core.task.TaskRunContext runContext) {
        try {
            permits.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new AgentResult(agentId, st.id(), "failed", "被中断", List.of(), List.of());
        }
        // P1：懒创建 worktree（从当前 HEAD 分支，上游子任务的合并提交对下游可见）；失败回退共享沙箱
        Path worktree = worktreeMode ? worktreeManager.create(sandbox.root(), st.id()) : null;
        Sandbox subSandbox = worktree != null ? new Sandbox(worktree.toString()) : sandbox;
        try {
            if (sharedCancel.get()) {
                return new AgentResult(agentId, st.id(), "cancelled", "（已停止，未启动）",
                        List.of(), List.of());
            }
            out.send(Map.of("type", "agent_spawn", "agent", agentId, "task", st.description()));

            // 计划期预分配路径占坑（冲突已在拆分校验阶段排除，这里登记供观察）
            for (String p : st.outputPaths()) {
                String key = "file:" + SubAgentConfig.normalize(p);
                blackboard.claim(key, agentId);
                out.send(Map.of("type", "blackboard", "key", key, "value", "（路径占坑）",
                        "owner", agentId));
            }

            // 子任务上下文：独立 WorkingMemory（并发安全前提），继承沙箱/权限/模型，skillId=null
            Task sub = new Task(parent.id() + "-" + st.id(), st.description(), parent.root(),
                    parent.conversationId(), LocalDateTime.now(), parent.modelId(),
                    parent.deepThink(), parent.approval(), null);
            WorkingMemory memory = new WorkingMemory(sub);
            ToolContext ctx = new ToolContext(subSandbox, memory,
                    "full".equalsIgnoreCase(parent.approval()),
                    runContext, agentId);
            memory.putVariable("root", subSandbox.root().toString());

            SubAgentConfig cfg = new SubAgentConfig(agentId, st.outputPaths(), blackboard,
                    sharedCancel, props.getMultiAgent().getSubAgentMaxSteps());
            String conclusion = agentLoop.executeSubAgent(sub, sse, client, knowledge,
                    memory, subSandbox, ctx, history, transcript, instruction(st, agentId, results),
                    cfg, hub);

            // 结果上报（WorkingMemory 内容在此压缩）
            String status;
            if (sharedCancel.get() || conclusion.contains("（已停止）")) {
                status = "cancelled";
            } else if (conclusion.startsWith("执行异常：") || conclusion.startsWith("已达最大步数")) {
                status = "failed";
            } else if (memory.steps().stream().anyMatch(s -> !s.ok())) {
                status = "partial";
            } else {
                status = "success";
            }

            // worktree 收尾：成功/部分成功 → commit + merge 回主根（冲突以子任务版本覆盖并标注）；
            // 失败/取消不合并（worktree 即弃）。最后清理 worktree 与临时分支。
            if (worktree != null) {
                if (status.equals("success") || status.equals("partial")) {
                    WorktreeManager.MergeOutcome mo =
                            worktreeManager.commitAndMerge(sandbox.root(), worktree, st.id());
                    if (!mo.conflicts().isEmpty()) {
                        mergeConflicts.addAll(mo.conflicts());
                        blackboard.record("merge-conflict/" + agentId,
                                String.join("、", mo.conflicts()), agentId);
                        out.send(Map.of("type", "blackboard", "key", "merge-conflict/" + agentId,
                                "value", truncate(String.join("、", mo.conflicts()), 300),
                                "owner", agentId));
                    }
                    if (!mo.ok()) {
                        log.warn("[{}] 子任务 {} 合并回主根失败: {}", parent.id(), st.id(), mo.error());
                    }
                }
                worktreeManager.cleanup(sandbox.root(), st.id());
            }

            AgentResult r = new AgentResult(agentId, st.id(), status, truncate(conclusion, 500),
                    memory.files().stream().distinct().toList(), memory.steps());
            blackboard.record("summary/" + agentId, truncate(conclusion, 500), agentId);
            if (!memory.files().isEmpty()) {
                blackboard.record("files/" + agentId, String.join("、", memory.files()), agentId);
                out.send(Map.of("type", "blackboard", "key", "files/" + agentId,
                        "value", truncate(String.join("、", memory.files()), 300), "owner", agentId));
            }
            return r;
        } finally {
            permits.release();
        }
    }

    /** 子 Agent 指令：自包含描述 + 固定输出路径 + 建议工具 + 上游产物摘要（计划期 DAG 的下游输入）。 */
    private String instruction(SubTask st, String agentId, Map<String, AgentResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是子 Agent ").append(agentId)
                .append("，负责多 Agent 协作中的一个子任务，请独立完成：\n")
                .append(st.description());
        if (!st.outputPaths().isEmpty()) {
            sb.append("\n\n你的产出文件路径已固定（不要写入其它路径）：")
                    .append(String.join("、", st.outputPaths()));
        }
        if (!st.suggestedTools().isEmpty()) {
            sb.append("\n建议使用工具：").append(String.join("、", st.suggestedTools()));
        }
        for (String d : st.dependsOn()) {
            AgentResult up = results.get(d);
            if (up != null) {
                sb.append("\n\n上游子任务 ").append(up.agentId()).append(" 已完成，摘要：")
                        .append(truncate(up.summary(), 400));
                if (!up.producedFiles().isEmpty()) {
                    sb.append("；其产出文件：").append(String.join("、", up.producedFiles()))
                            .append("（可直接 fileRead 读取后继续你的工作）");
                }
            }
        }
        sb.append("\n\n完成后直接输出简明中文总结（不要向用户提问）。");
        return sb.toString();
    }

    // --------------------------------------------------------------- 聚合与收尾

    /** 一次 LLM 流式聚合；失败/关闭 fail-isolation 时降级为模板拼接，保证必有结论。 */
    private String aggregate(ChatClient client, Task task, List<SubTask> subtasks,
                             Map<String, String> agentIds, Map<String, AgentResult> results,
                             SharedBlackboard blackboard, AgentLoop.Emitter out) {
        if (!props.getMultiAgent().isFailIsolation()) {
            for (SubTask st : subtasks) {
                AgentResult r = results.get(st.id());
                if (r != null && !r.ok()) {
                    return "任务未完成：子任务 " + st.id() + "（" + truncate(st.description(), 60)
                            + "）失败 —— " + truncate(r.summary(), 200);
                }
            }
        }
        String sys = "[ROLE=AGGREGATOR] 你是多 Agent 结果聚合器。根据用户任务与各子 Agent 的执行结果，"
                + "输出面向用户的统一结论：Markdown 格式、简明中文，逐项说明各子任务完成情况与产出文件，"
                + "失败项明确标注原因。不要罗列内部调度细节。";
        try {
            StringBuilder payload = new StringBuilder();
            payload.append("用户任务：").append(task.prompt()).append("\n\n各子 Agent 执行结果：\n");
            for (SubTask st : subtasks) {
                AgentResult r = results.get(st.id());
                if (r == null) {
                    payload.append("- ").append(agentIds.get(st.id())).append("（").append(st.id())
                            .append("）：未执行\n");
                    continue;
                }
                payload.append("- ").append(r.agentId()).append("（").append(r.status()).append("）")
                        .append("：").append(truncate(st.description(), 120))
                        .append("\n  摘要：").append(truncate(r.summary(), 400)).append('\n');
                payload.append("  产出文件：")
                        .append(r.producedFiles().isEmpty() ? "无" : String.join("、", r.producedFiles()))
                        .append('\n');
            }
            List<SharedBlackboard.Entry> snap = blackboard.snapshot();
            if (!snap.isEmpty()) {
                payload.append("\n共享登记：");
                for (SharedBlackboard.Entry e : snap) {
                    payload.append('\n').append("- ").append(e.key()).append(" = ")
                            .append(truncate(e.value(), 120)).append("（").append(e.owner()).append("）");
                }
            }
            StringBuilder buf = new StringBuilder();
            client.chat(List.of(LlmMessage.system(sys), LlmMessage.user(payload.toString())),
                    List.of(), new ChatClient.StreamListener() {
                        @Override
                        public void onText(String delta) {
                            buf.append(delta);
                            out.sendLive(Map.of("type", "text_delta", "delta", delta));
                        }
                    });
            String conclusion = buf.toString();
            if (!conclusion.isBlank()) {
                return conclusion;
            }
            log.warn("聚合 LLM 输出为空，降级模板拼接");
        } catch (Exception e) {
            log.warn("聚合 LLM 调用失败，降级模板拼接: {}", e.getMessage());
        }
        return template(task, subtasks, agentIds, results);
    }

    /** 降级模板：各子任务 summary + 文件清单 + 失败项标注。 */
    private String template(Task task, List<SubTask> subtasks, Map<String, String> agentIds,
                            Map<String, AgentResult> results) {
        StringBuilder sb = new StringBuilder("## 多 Agent 执行结果\n\n");
        for (SubTask st : subtasks) {
            AgentResult r = results.get(st.id());
            String icon = r == null ? "○" : switch (r.status()) {
                case "success" -> "✅";
                case "partial" -> "⚠️";
                case "cancelled" -> "⏹";
                default -> "❌";
            };
            sb.append(icon).append(" **").append(agentIds.get(st.id())).append("** ")
                    .append(truncate(st.description(), 120)).append('\n');
            if (r == null) {
                sb.append("  未执行\n\n");
                continue;
            }
            if (!r.producedFiles().isEmpty()) {
                sb.append("  产出：").append(String.join("、", r.producedFiles())).append('\n');
            }
            sb.append("  ").append(truncate(r.summary().replace('\n', ' '), 300)).append("\n\n");
        }
        return sb.toString();
    }

    /** 拆分计划以既有 plan 事件展示（每个子任务一条 PlanStep）。 */
    private List<PlanStep> planSteps(List<SubTask> subtasks) {
        List<PlanStep> steps = new ArrayList<>();
        int i = 1;
        for (SubTask st : subtasks) {
            StringBuilder reason = new StringBuilder();
            if (!st.dependsOn().isEmpty()) {
                reason.append("依赖：").append(String.join("、", st.dependsOn())).append("；");
            }
            if (!st.outputPaths().isEmpty()) {
                reason.append("输出：").append(String.join("、", st.outputPaths()));
            }
            if (reason.isEmpty()) {
                reason.append("可并行子任务");
            }
            steps.add(new PlanStep(i++, st.description(),
                    st.suggestedTools().isEmpty() ? null : st.suggestedTools().get(0),
                    reason.toString()));
        }
        return steps;
    }

    /** 父 trace：各子 Agent ToolCall 合并，每条附 agent 标注。 */
    private List<Map<String, Object>> mergedSteps(List<SubTask> subtasks,
                                                  Map<String, String> agentIds,
                                                  Map<String, AgentResult> results) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (SubTask st : subtasks) {
            AgentResult r = results.get(st.id());
            if (r == null) {
                continue;
            }
            for (ToolCall c : r.steps()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name());
                m.put("args", c.args());
                m.put("result", c.result());
                m.put("ok", c.ok());
                m.put("agent", agentIds.get(st.id()));
                merged.add(m);
            }
        }
        return merged;
    }

    private void emitAgentDone(AgentLoop.Emitter out, AgentResult r) {
        out.send(Map.of("type", "agent_done", "agent", r.agentId(), "status", r.status(),
                "summary", truncate(r.summary(), 300), "files", r.producedFiles()));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
