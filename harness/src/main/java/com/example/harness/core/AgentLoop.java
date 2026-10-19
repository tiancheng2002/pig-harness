package com.example.harness.core;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.agents.SpawnAgentTool;
import com.example.harness.core.memory.AgentProfileStore;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.memory.EpisodicStore;
import com.example.harness.core.memory.WorkingMemory;
import com.example.harness.core.multi.SharedBlackboard;
import com.example.harness.core.multi.SubAgentConfig;
import com.example.harness.core.policy.CompletionGuard;
import com.example.harness.core.policy.ExecutionBudget;
import com.example.harness.core.policy.ExecutionEvidence;
import com.example.harness.core.policy.FailureClassifier;
import com.example.harness.core.policy.TaskIntent;
import com.example.harness.core.policy.ToolLoopGuard;
import com.example.harness.core.policy.VerificationClassifier;
import com.example.harness.core.stream.SafeSseEmitter;
import com.example.harness.core.stream.StreamHub;
import com.example.harness.core.task.ExecutionResult;
import com.example.harness.core.task.RunStatus;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.ChatResult;
import com.example.harness.llm.LlmMessage;
import com.example.harness.llm.LlmToolCall;
import com.example.harness.llm.LlmToolSpec;
import com.example.harness.llm.TokenEstimator;
import com.example.harness.model.AgentProfile;
import com.example.harness.model.Knowledge;
import com.example.harness.model.PlanStep;
import com.example.harness.model.Task;
import com.example.harness.model.ToolCall;
import com.example.harness.model.Trace;
import com.example.harness.sandbox.Sandbox;
import com.example.harness.tools.Tool;
import com.example.harness.tools.ToolContext;
import com.example.harness.tools.ToolResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AgentLoop 主循环（F3）：plan → act → observe → reflect（流式版）。
 * LLM 增量输出实时推送前端（text_delta / thinking_delta）；
 * 工具轮的说明文本以 step_commentary 落库回放，结论仍在 done 事件整体下发（兼容旧回放）。
 * M4：同一循环体经 runLoop 复用给受限子 Agent（executeSubAgent）——子级不 emit done、
 * 不落 trace/persist、不 complete，事件经 Emitter 注入 agent 字段并入父会话。
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** 需要用户审批的写操作工具（权限模式=请求审批时生效）。 */
    private static final java.util.Set<String> MUTATING_TOOLS = ToolRegistry.MUTATING_TOOLS;
    /** P1：成功执行后触发代码索引增量重切的工具。 */
    private static final java.util.Set<String> FILE_INDEX_TOOLS =
            java.util.Set.of("fileWrite", "fileEdit", "fileDelete", "docxWrite");
    /** 请求审批的等待上限（SSE 总超时 10 分钟，预留收尾时间）。 */
    private static final long APPROVAL_TIMEOUT_MS = 300_000;
    /** 计划模式（approval=plan）允许的工具白名单：纯只读 + 澄清提问；bash/fileEdit 等可写文件故禁用。 */
    private static final List<String> PLAN_TOOLS = List.of(
            "fileRead", "listFiles", "grep", "codeSearch", "httpFetch");

    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final EpisodicStore episodicStore;
    private final ConversationStore conversationStore;
    private final ApprovalGate approvalGate;
    private final com.example.harness.core.question.QuestionGate questionGate;
    private final ObjectMapper om;
    private final HarnessProperties props;
    private final ModelRegistry modelRegistry;
    private final AgentProfileStore agentProfileStore;
    private final com.example.harness.core.codeindex.CodeIndexService codeIndexService;
    private final com.example.harness.core.hooks.HookService hookService;
    private final com.example.harness.core.task.TaskRunService taskRunService;
    private final com.example.harness.core.task.LlmCallTracker llmCallTracker;
    private final WorkspaceSnapshotService workspaceSnapshotService;
    private final CompletionGuard completionGuard = new CompletionGuard();
    private final int maxSteps;
    /** spawnAgent 泳道序号（跨任务共享计数，仅用于命名唯一性）。 */
    private final AtomicInteger laneSeq = new AtomicInteger();

    public AgentLoop(ToolRegistry toolRegistry,
                     Planner planner,
                     EpisodicStore episodicStore,
                     ConversationStore conversationStore,
                     ApprovalGate approvalGate,
                     com.example.harness.core.question.QuestionGate questionGate,
                     ObjectMapper om,
                     HarnessProperties props,
                     ModelRegistry modelRegistry,
                     AgentProfileStore agentProfileStore,
                     com.example.harness.core.codeindex.CodeIndexService codeIndexService,
                     com.example.harness.core.hooks.HookService hookService,
                     com.example.harness.core.task.TaskRunService taskRunService,
                     com.example.harness.core.task.LlmCallTracker llmCallTracker,
                     WorkspaceSnapshotService workspaceSnapshotService) {
        this.toolRegistry = toolRegistry;
        this.planner = planner;
        this.episodicStore = episodicStore;
        this.conversationStore = conversationStore;
        this.approvalGate = approvalGate;
        this.questionGate = questionGate;
        this.om = om;
        this.props = props;
        this.modelRegistry = modelRegistry;
        this.agentProfileStore = agentProfileStore;
        this.codeIndexService = codeIndexService;
        this.hookService = hookService;
        this.taskRunService = taskRunService;
        this.llmCallTracker = llmCallTracker;
        this.workspaceSnapshotService = workspaceSnapshotService;
        this.maxSteps = Math.max(1, props.getMaxSteps());
    }

    /** 主执行逻辑（与 SkillExecutor 共享调用签名）。client 为本次请求选定的模型。 */
    public ExecutionResult execute(Task task, SseEmitter emitter, ChatClient client,
                        List<Knowledge> knowledge,
                        WorkingMemory memory, Sandbox sandbox, ToolContext ctx,
                        List<LlmMessage> history, List<Map<String, Object>> transcript) {
        return execute(task, emitter, client, knowledge, memory, sandbox, ctx, history, transcript, null, null);
    }

    /**
     * 指令注入入口（M5 提示词技能）：instruction 非空时作为 user 消息并跳过 Planner，
     * 其余（流式事件 / 审批 / 轨迹落库 / done 契约）与普通执行完全一致。
     */
    public ExecutionResult execute(Task task, SseEmitter emitter, ChatClient client,
                        List<Knowledge> knowledge,
                        WorkingMemory memory, Sandbox sandbox, ToolContext ctx,
                        List<LlmMessage> history, List<Map<String, Object>> transcript,
                        String instruction) {
        return execute(task, emitter, client, knowledge, memory, sandbox, ctx, history, transcript,
                instruction, null);
    }

    /**
     * 会话入口（体验升级七）：hub 非空时事件同步写入缓冲供 /live 重放续流，
     * hub.cancelFlag 作为取消标志——客户端断开不再终止任务，停止只能经 /stop 显式触发。
     */
    public ExecutionResult execute(Task task, SseEmitter emitter, ChatClient client,
                        List<Knowledge> knowledge,
                        WorkingMemory memory, Sandbox sandbox, ToolContext ctx,
                        List<LlmMessage> history, List<Map<String, Object>> transcript,
                        String instruction, StreamHub.Hub hub) {
        Emitter out = new Emitter(emitter, transcript,
                hub != null ? hub.cancelFlag : null, null, hub);
        try {
            LoopResult loop = runLoop(task, emitter, out, client, knowledge, memory, sandbox, ctx,
                    history, instruction, null, hub, transcript);
            String conclusion = loop.conclusion();
            long traceId = episodicStore.save(Trace.of(task.prompt(),
                    om.writeValueAsString(memory.steps()), conclusion));
            log.info("[{}] 任务完成: {}", task.id(), truncate(conclusion, 100));
            if (out.cancelled()) return ExecutionResult.stopped(conclusion, traceId, "TASK");
            boolean partial = loop.stepLimitReached() || loop.guardPartial();
            String reasonCode = !partial ? null
                    : loop.budgetExceeded() ? "BUDGET_EXCEEDED" : "DELIVERY_INCOMPLETE";
            String reasonText = !partial ? null
                    : loop.budgetExceeded() ? "已达执行预算上限，任务被要求提前收尾" : "运行时交付证据未满足";
            return new ExecutionResult(partial ? RunStatus.PARTIAL : RunStatus.SUCCESS,
                    conclusion, traceId, "TASK", reasonCode, reasonText, loop.stepLimitReached());
        } catch (Exception e) {
            log.error("[{}] 任务执行异常", task.id(), e);
            String conclusion = "任务执行异常：" + e.getMessage();
            Long traceId = null;
            try {
                traceId = episodicStore.save(Trace.of(task.prompt(),
                        om.writeValueAsString(memory.steps()), "ERROR: " + e.getMessage()));
            } catch (Exception ignore) {
                log.warn("异常轨迹写入失败: {}", ignore.getMessage());
            }
            return ExecutionResult.failed(conclusion, traceId, "TASK", "AGENT_LOOP_ERROR", e.getMessage());
        }
    }

    /**
     * 子 Agent 执行（M4-F5）：复用主循环，但事件注入 agent 字段、callId 加前缀、
     * 写操作占坑兜底；不 emit done、不落 trace/persist、不 complete（由父层 Orchestrator 统一收尾）。
     * 返回结论文本；异常被捕获为错误文本（调用方转为 AgentResult.failed，失败隔离）。
     */
    public String executeSubAgent(Task task, SseEmitter emitter, ChatClient client,
                                  List<Knowledge> knowledge,
                                  WorkingMemory memory, Sandbox sandbox, ToolContext ctx,
                                  List<LlmMessage> history, List<Map<String, Object>> transcript,
                                  String instruction, SubAgentConfig cfg) {
        return executeSubAgent(task, emitter, client, knowledge, memory, sandbox, ctx,
                history, transcript, instruction, cfg, null);
    }

    /** 带 Hub 的子 Agent 执行（泳道事件同步进会话事件缓冲，供 /live 重放）。 */
    public String executeSubAgent(Task task, SseEmitter emitter, ChatClient client,
                                  List<Knowledge> knowledge,
                                  WorkingMemory memory, Sandbox sandbox, ToolContext ctx,
                                  List<LlmMessage> history, List<Map<String, Object>> transcript,
                                  String instruction, SubAgentConfig cfg, StreamHub.Hub hub) {
        Emitter out = new Emitter(emitter, transcript, cfg.sharedCancel(), cfg.agentId(), hub);
        try {
            return runLoop(task, emitter, out, client, knowledge, memory, sandbox, ctx,
                    history, instruction, cfg, hub, transcript).conclusion();
        } catch (Exception e) {
            log.error("[{}] 子 Agent {} 执行异常", task.id(), cfg.agentId(), e);
            return "执行异常：" + e.getMessage();
        }
    }

    /**
     * 循环主体：plan 构建（cfg==null 且 instruction 为空时）→ 流式迭代 → 结论判定。
     * 不负责 done / trace / persist / complete，由调用方收尾。
     * sse 供 spawnAgent 特判向子智能体传递（executeSubAgent 需要原始 emitter）。
     */
    private LoopResult runLoop(Task task, SseEmitter sse, Emitter out, ChatClient client,
                           List<Knowledge> knowledge,
                           WorkingMemory memory, Sandbox sandbox, ToolContext ctx,
                           List<LlmMessage> history, String instruction, SubAgentConfig cfg,
                           StreamHub.Hub hub, List<Map<String, Object>> transcript) {
        boolean guided = instruction != null && !instruction.isBlank();
        boolean conversational = task.conversationId() != null && !task.conversationId().isBlank();
        WorkspaceSnapshotService.Snapshot workspace = workspaceSnapshotService.inspect(sandbox);
        TaskIntent taskIntent = TaskIntent.infer(task.prompt());
        int stepLimit = cfg != null ? Math.max(1, cfg.maxSteps()) : maxSteps;
        ExecutionEvidence evidence = new ExecutionEvidence();
        ToolLoopGuard toolLoopGuard = new ToolLoopGuard(
                props.getAgentPolicy().getSameCallLoopLimit());
        boolean enforceDelivery = cfg == null && !"plan".equalsIgnoreCase(task.approval());

        // ---- PLAN ----
        // 计划模式（approval=plan）：只读调研 + askUser 澄清，跳过 Planner（执行型计划卡与只读承诺冲突）
        boolean planMode = "plan".equalsIgnoreCase(task.approval()) && cfg == null;
        ChatClient loopClient = llmCallTracker.tracked(client, ctx.runContext(),
                cfg == null ? "agent_loop" : "sub_agent", ctx.agentId());
        List<PlanStep> plan = List.of();
        if (!guided && !planMode && shouldPlan(task, taskIntent)) {
            Set<String> planTools = new java.util.TreeSet<>(toolRegistry.names());
            if (conversational) planTools.add(com.example.harness.core.question.AskUserTool.NAME);
            plan = planner.plan(task.prompt(), planTools,
                    llmCallTracker.tracked(client, ctx.runContext(), "planner", ctx.agentId()), workspace);
            memory.setPlan(plan);
            out.send(Map.of("type", "plan", "steps", plan));
            log.info("[{}] 规划完成，共 {} 步（历史消息 {} 条）",
                    task.id(), plan.size(), history.size());
        } else if (planMode) {
            log.info("[{}] 计划模式：跳过 Planner，直接进入只读调研", task.id());
        } else if (guided) {
            log.info("[{}] 指令引导执行（跳过规划）：{}", task.id(), truncate(instruction, 80));
        } else {
            // A separate planning round trip is pure latency for a single bounded change.
            log.info("[{}] 有界任务，跳过 Planner 直接执行", task.id());
        }

        // ---- ACT / OBSERVE / REFLECT（流式）----
        List<LlmMessage> msgs = new ArrayList<>();
        msgs.add(LlmMessage.system(systemPrompt(sandbox, plan, knowledge,
                task.root() != null && !task.root().isBlank(), task.approval())
                + "\n" + workspace.toPrompt()));
        msgs.addAll(history);
        msgs.add(LlmMessage.user(guided ? instruction : task.prompt()));
        // 本次任务可用的工具清单：主泳道全量（计划模式=只读白名单）；子智能体按白名单过滤且永不包含 spawnAgent
        List<LlmToolSpec> specs = planMode
                ? planModeSpecs()
                : (cfg == null ? taskModeSpecs(conversational) : toolRegistry.specsFor(cfg.allowedTools()));

        String conclusion = null;
        StringBuilder roundText = new StringBuilder();
        int rounds = 0;
        int progressIdx = 0; // 计划进度推进游标（best-effort）
        Set<String> successfulTools = new java.util.HashSet<>();
        int completionAttempts = 0;
        // A runtime nudge is worth saying once; repeating it only dilutes the real instruction.
        Set<String> policyCodesUsed = new java.util.HashSet<>();
        Set<String> advisoryReported = new java.util.HashSet<>();
        int callsAtLastRetry = -1;
        long startedNano = System.nanoTime();
        boolean hardBudgetReached = false;
        boolean budgetExceeded = false;
        // 预算按任务规模缩放，且硬停只看「有没有进展」，不看调用总数——
        // 全栈功能天然比单点修复贵，不该和空转的 Agent 撞同一个上限。
        ExecutionBudget budget = new ExecutionBudget(
                props.getAgentPolicy().getToolCallSoftBudget(),
                props.getAgentPolicy().getToolCallCeiling(),
                props.getAgentPolicy().getWallClockBudgetSeconds(),
                props.getAgentPolicy().getUnproductiveCallLimit(),
                taskIntent);
        long lastProgressVersion = toolLoopGuard.progressVersion();
        int callsAtLastProgress = 0;
        int explorationCheckpoint = Math.max(1, props.getAgentPolicy().getExplorationSoftLimit());
        int stagnantRounds = 0;
        boolean guardPartial = false;
        boolean needsInput = false;
        boolean deliveryReady = false;
        // P1 验证钩子：每任务读一次 .harness/hooks.json（改文件即生效）；无文件零行为
        List<com.example.harness.core.hooks.HookService.Hook> verifyHooks =
                (cfg == null && !planMode) ? hookService.load(sandbox.root()) : List.of();
        int hookAttempts = 0;
        boolean askApproval = "ask".equalsIgnoreCase(task.approval())
                && task.conversationId() != null && !task.conversationId().isBlank();
        // 主 Agent 不因轮数或工具总数终止；max-steps 仅作为长任务反思检查点。
        // 子 Agent 仍使用其局部步数上限，耗尽后结果回到主 Agent，由主任务继续处理。
        while (!out.cancelled() && (cfg == null || rounds < stepLimit)) {
            rounds++;
            roundText.setLength(0);
            StringBuilder roundReasoning = new StringBuilder();
            List<String> roundPolicyMessages = new ArrayList<>();
            long progressBeforeRound = toolLoopGuard.progressVersion();

            if (cfg == null && rounds > 1 && (rounds - 1) % stepLimit == 0) {
                String message = "长任务已运行 " + (rounds - 1) + " 轮。此检查点不会停止任务；"
                        + "请对照用户完整要求和计划，合并剩余工作、删除低价值步骤，并继续执行直到真正完成。";
                out.send(guardEvent("LONG_RUN_CHECKPOINT", message, false));
                msgs.add(LlmMessage.user("[运行时反思检查点] " + message));
            }

            // 执行预算：软预算提醒收敛，硬预算收回写入类工具并要求立即收尾。
            // 主 Agent 没有轮数上限，预算是它唯一的成本上界。
            if (cfg == null && !planMode) {
                if (toolLoopGuard.progressVersion() != lastProgressVersion) {
                    lastProgressVersion = toolLoopGuard.progressVersion();
                    callsAtLastProgress = evidence.totalCalls();
                }
                ExecutionBudget.Verdict verdict = budget.evaluate(evidence.totalCalls(),
                        evidence.totalCalls() - callsAtLastProgress,
                        (System.nanoTime() - startedNano) / 1_000_000_000L);
                if (verdict.hard()) {
                    hardBudgetReached = true;
                    budgetExceeded = true;
                    specs = readOnlySpecs(conversational);
                    out.send(guardEvent(verdict.code(), verdict.message(), true));
                    msgs.add(LlmMessage.user("[运行时预算] " + verdict.message()));
                } else if (verdict.soft()) {
                    out.send(guardEvent(verdict.code(), verdict.message(), false));
                    msgs.add(LlmMessage.user("[运行时预算] " + verdict.message()));
                }
            }

            // 任务内上下文压缩：估算超软阈值时，旧轮工具输出原位替换为占位符（context_compact 落库）
            compactIfNeeded(task, out, msgs);

            ChatResult resp;
            try {
                resp = chatRound(loopClient, msgs, specs, roundText, roundReasoning, out);
            } catch (Exception first) {
                if (out.cancelled()) break;
                // 上下文超限：先紧急压缩（占位替换 + 裁剪最旧历史）再走统一重试
                if (isContextOverflow(first)) {
                    compactAggressive(task, out, msgs);
                }
                // 上游流中断（超时/断连）：保留已输出片段为说明，重试一次
                if (!roundReasoning.isEmpty()) {
                    out.send(Map.of("type", "thinking", "text",
                            roundReasoning + "\n\n（连接中断，重试前已保留以上思考）"));
                }
                if (!roundText.isEmpty()) {
                    out.send(Map.of("type", "step_commentary", "round", rounds,
                            "text", roundText + "\n\n（连接中断，已自动重试）"));
                }
                roundText.setLength(0);
                roundReasoning.setLength(0);
                out.send(Map.of("type", "reflect", "reason",
                    "模型响应中断（" + truncate(String.valueOf(first.getMessage()), 120) + "），已自动重试一次。"));
                resp = chatRound(loopClient, msgs, specs, roundText, roundReasoning, out);
            }
            if (out.cancelled()) break;
            emitUsage(out, msgs, resp, task, cfg == null, client);

            if (!resp.hasToolCalls()) {
                String proposed = !roundText.isEmpty() ? roundText.toString()
                        : (resp.content() == null || resp.content().isBlank()
                                ? "（模型未返回结论）" : resp.content());
                if (enforceDelivery) {
                    List<CompletionGuard.Missing> allMissing = completionGuard.evaluate(taskIntent, evidence);
                    for (CompletionGuard.Missing advisory : allMissing) {
                        if (!advisory.blocking() && advisoryReported.add(advisory.code())) {
                            out.send(guardEvent(advisory.code(), advisory.message(), false));
                        }
                    }
                    List<CompletionGuard.Missing> missing = CompletionGuard.blocking(allMissing);
                    if (!missing.isEmpty()) {
                        // A retry in which the model called no tool at all will not call one next
                        // time either; burning the remaining retries on it only costs build runs.
                        if (completionAttempts < Math.max(0, props.getAgentPolicy().getCompletionRetries())
                                && evidence.totalCalls() != callsAtLastRetry) {
                            completionAttempts++;
                            callsAtLastRetry = evidence.totalCalls();
                            for (CompletionGuard.Missing item : missing) {
                                out.send(guardEvent(item.code(), item.message(), false));
                            }
                            msgs.add(LlmMessage.assistant(proposed, null));
                            msgs.add(LlmMessage.user(completionFeedback(missing, completionAttempts)));
                            out.send(Map.of("type", "reflect", "reason",
                                    "交付证据不完整，已要求模型补齐（第 " + completionAttempts + "/"
                                            + props.getAgentPolicy().getCompletionRetries() + " 次）"));
                            continue;
                        }
                        guardPartial = true;
                        for (CompletionGuard.Missing item : missing) {
                            out.send(guardEvent(item.code(), item.message(), true));
                        }
                        proposed = proposed + "\n\n⚠️ **交付未完成**：\n- "
                                + missing.stream().map(CompletionGuard.Missing::message)
                                .reduce((a, b) -> a + "\n- " + b).orElse("缺少必要证据");
                    }
                }
                // P1 验证钩子硬闸门：结论产出前必须通过项目 verify 命令；失败回灌自动修复（有界重试）
                if (!verifyHooks.isEmpty()) {
                    List<com.example.harness.core.hooks.HookService.HookResult> failures =
                            hookService.runAll(verifyHooks, ctx, out::send);
                    if (!failures.isEmpty()) {
                        if (hookAttempts < hookService.maxRetries()) {
                            hookAttempts++;
                            out.send(Map.of("type", "reflect",
                                    "reason", "交付前验证未通过，已回灌模型修复（第 " + hookAttempts
                                            + "/" + hookService.maxRetries() + " 次重试）"));
                            msgs.add(LlmMessage.assistant(proposed, null));
                            msgs.add(LlmMessage.user(hookFeedback(failures)));
                            continue; // conclusion 保持 null，进入下一轮
                        }
                        proposed = proposed + "\n\n⚠️ **交付前验证未通过**（已重试 "
                                + hookService.maxRetries() + " 次仍未通过，请人工处理）：\n"
                                + hookFailureLines(failures);
                    }
                }
                conclusion = proposed;
                break;
            }

            // 本轮要调工具：先落聚合思考与说明文本（供回放），再执行工具
            if (!roundReasoning.isEmpty()) {
                out.send(Map.of("type", "thinking", "text", roundReasoning.toString()));
            }
            if (!roundText.isEmpty()) {
                out.send(Map.of("type", "step_commentary", "round", rounds,
                        "text", roundText.toString()));
            }

            msgs.add(LlmMessage.assistant(resp.content(), resp.toolCalls()));
            for (LlmToolCall call : resp.toolCalls()) {
                Map<String, Object> args = parseArgs(call.arguments());
                substituteVars(args, memory);
                evidence.nextSequence();

                // 占坑兜底（仅子 Agent）：输出路径被并行兄弟占用时拒绝执行并回灌
                if (cfg != null) {
                    String conflict = claimConflict(cfg, call.name(), args, ctx, sandbox);
                    if (conflict != null) {
                        ToolResult blocked = ToolResult.error(conflict);
                        msgs.add(LlmMessage.tool(call.id(), blocked.toContextString()));
                        memory.addStep(new ToolCall(call.name(), args,
                                truncate(blocked.output(), 2000), false));
                        out.send(toolCallEvent(cfg, call.id(), call.name(), args,
                                truncate(blocked.output(), 4000), false));
                        out.send(Map.of("type", "reflect",
                                "reason", "路径冲突已阻止：" + truncate(conflict, 200)));
                        continue;
                    }
                }

                // 子智能体工具白名单（防御性；specs 已过滤，模型幻觉调用未授权工具时直接回灌）
                if (cfg != null && !cfg.canUse(call.name())) {
                    ToolResult deniedTool = ToolResult.error("子智能体「" + cfg.agentId()
                            + "」无权使用工具 " + call.name() + "（只允许："
                            + (cfg.allowedTools().isEmpty() ? "除 spawnAgent 外的全部工具"
                            : String.join("、", cfg.allowedTools())) + "）。请改用允许的工具完成任务。");
                    msgs.add(LlmMessage.tool(call.id(), deniedTool.toContextString()));
                    memory.addStep(new ToolCall(call.name(), args,
                            truncate(deniedTool.output(), 2000), false));
                    out.send(toolCallEvent(cfg, call.id(), call.name(), args,
                            truncate(deniedTool.output(), 4000), false));
                    toolRegistry.recordRejected(gateId(cfg, call.id()), call.name(), args, ctx,
                            deniedTool.output());
                    continue;
                }

                // 计划模式硬闸门（防御性，主循环）：系统提示词的固定工具文案可能诱导模型发起写类调用，
                // 无论 tools 数组是否包含，一律拒绝并回灌错误，保证「不改任何文件」的只读承诺
                if (planMode && cfg == null && !PLAN_TOOLS.contains(call.name())
                        && !com.example.harness.core.question.AskUserTool.NAME.equals(call.name())
                        && !SpawnAgentTool.NAME.equals(call.name())) {
                    ToolResult denied = ToolResult.error("计划模式（只读）不允许使用工具 " + call.name()
                            + "。只能调研（fileRead/listFiles/httpFetch）、askUser 澄清或 spawnAgent 派只读子智能体；"
                            + "请基于已有调研继续，并在最终结论中输出计划文档。");
                    msgs.add(LlmMessage.tool(call.id(), denied.toContextString()));
                    memory.addStep(new ToolCall(call.name(), args,
                            truncate(denied.output(), 2000), false));
                    out.send(toolCallEvent(cfg, call.id(), call.name(), args,
                            truncate(denied.output(), 4000), false));
                    out.send(Map.of("type", "reflect",
                            "reason", "计划模式已阻止写类工具调用：" + call.name()));
                    toolRegistry.recordRejected(gateId(cfg, call.id()), call.name(), args, ctx,
                            denied.output());
                    continue;
                }

                // 预算硬闸门（防御性）：specs 已收回写入类工具，但系统提示词仍描述它们，
                // 模型可能照提示词文案继续发起写类调用，这里一律拒绝并回灌收尾要求
                if (hardBudgetReached && cfg == null
                        && ToolRegistry.MUTATING_TOOLS.contains(call.name())) {
                    ToolResult denied = ToolResult.error("已达执行预算上限，写入与命令类工具已收回（"
                            + call.name() + " 不可用）。请基于现有证据立即输出最终结论，"
                            + "并如实说明尚未完成的部分。");
                    msgs.add(LlmMessage.tool(call.id(), denied.toContextString()));
                    memory.addStep(new ToolCall(call.name(), args, denied.output(), false));
                    out.send(toolCallEvent(cfg, call.id(), call.name(), args, denied.output(), false));
                    toolRegistry.recordRejected(gateId(cfg, call.id()), call.name(), args, ctx,
                            denied.output());
                    continue;
                }

                // spawnAgent 特判（子智能体体系）：主 Agent 按需派出子智能体，事件自动进独立泳道；
                // 子智能体不可再派出（防递归）
                if (SpawnAgentTool.NAME.equals(call.name())) {
                    java.time.Instant spawnStartedAt = java.time.Instant.now();
                    long spawnStartedNano = System.nanoTime();
                    out.sendLive(Map.of("type", "tool_start",
                            "callId", gateId(cfg, call.id()),
                            "name", call.name(),
                            "target", truncate(String.valueOf(args.getOrDefault("agent", "")), 60)));
                    ToolResult r = cfg == null
                    ? spawnSubAgent(task, sse, client, knowledge, sandbox, ctx,
                                    history, transcript, out, hub, args)
                            : ToolResult.error("子智能体不能再派出子智能体（spawnAgent 仅主 Agent 可用）");
                    msgs.add(LlmMessage.tool(call.id(), r.toContextString()));
                    memory.addStep(new ToolCall(call.name(), args,
                            truncate(r.output(), 2000), r.ok()));
                    out.send(toolCallEvent(cfg, call.id(), call.name(), args,
                            truncate(r.output(), 4000), r.ok()));
                    toolRegistry.recordSynthetic(gateId(cfg, call.id()), call.name(), args, ctx,
                            r.ok(), r.ok() ? null : r.output(), spawnStartedAt,
                            Math.max(0, (System.nanoTime() - spawnStartedNano) / 1_000_000L));
                    if (!r.ok()) {
                        out.send(Map.of("type", "reflect",
                                "reason", "子智能体派发失败：" + truncate(r.output(), 300)));
                    } else if (progressIdx < plan.size()
                            && planToolMatches(plan.get(progressIdx).tool(), call.name())) {
                        out.send(Map.of("type", "plan_step",
                                "index", plan.get(progressIdx).index(), "status", "done"));
                        progressIdx++;
                    }
                    continue;
                }

                // 澄清提问特判：PLAN 最多 3 问；TASK 仅在有仓库证据且尚未修改时允许 1 问。
                if (cfg == null && com.example.harness.core.question.AskUserTool.NAME.equals(call.name())) {
                    String question = truncate(String.valueOf(args.getOrDefault("question", "")), 500);
                    String qId = gateId(cfg, call.id()); // 与 tool_call 事件同 callId，前端据此判定已答
                    int maxQuestions = planMode ? 3 : Math.max(0, props.getAgentPolicy().getTaskMaxQuestions());
                    boolean eligible = planMode || (conversational
                            && evidence.successfulDiscoveryCalls() >= 2 && evidence.mutations() == 0);
                    if (!eligible || evidence.questions() >= maxQuestions) {
                        String reason = !eligible
                                ? "TASK 澄清前必须至少完成 2 次相关读取/检索，且不得已经修改文件。"
                                : "本次任务的澄清次数已达上限（" + maxQuestions + "）。";
                        ToolResult denied = ToolResult.error(reason + "请基于已有证据收尾，不要重复提问。");
                        msgs.add(LlmMessage.tool(call.id(), denied.toContextString()));
                        memory.addStep(new ToolCall(call.name(), args, denied.output(), false));
                        out.send(toolCallEvent(cfg, call.id(), call.name(), args, denied.output(), false));
                        toolRegistry.recordSynthetic(qId, call.name(), args, ctx, false, denied.output(),
                                java.time.Instant.now(), 0);
                        evidence.recordOther(false);
                        continue;
                    }
                    evidence.recordQuestion();
                    // 先注册后广播：问题卡秒答时 POST 会在注册前落空（409 竞态，与审批闸门同款）
                    questionGate.register(task.conversationId(), qId);
                    Map<String, Object> req = new HashMap<>();
                    req.put("type", "question_request");
                    req.put("callId", qId);
                    req.put("question", question);
                    Object opts = args.get("options");
                    if (opts instanceof List<?> list && !list.isEmpty()) {
                        req.put("options", list.stream().limit(4).map(String::valueOf).toList());
                    }
                    Object rec = args.get("recommended");
                    if (rec instanceof Number n) {
                        req.put("recommended", n.intValue());
                    }
                    out.send(req);
                    log.info("[{}] 澄清提问（挂起等待答复）: {}", task.id(), truncate(question, 80));
                    if (ctx.runContext() != null) taskRunService.beginWait(ctx.runContext());
                    String answer;
                    try {
                        answer = questionGate.await(task.conversationId(), qId, APPROVAL_TIMEOUT_MS);
                    } finally {
                        if (ctx.runContext() != null) taskRunService.endWait(ctx.runContext());
                    }
                    ToolResult r = answer == null
                            ? ToolResult.error("用户未在 5 分钟内答复。任务将暂停，不会改修其它缺陷。")
                            : ToolResult.success("用户答复：" + answer);
                    msgs.add(LlmMessage.tool(call.id(), r.toContextString()));
                    memory.addStep(new ToolCall(call.name(), args,
                            truncate(r.output(), 2000), r.ok()));
                    out.send(toolCallEvent(cfg, call.id(), call.name(), args,
                            truncate(r.output(), 4000), r.ok()));
                    toolRegistry.recordSynthetic(qId, call.name(), args, ctx, r.ok(),
                            r.ok() ? null : r.output(), java.time.Instant.now(), 0);
                    if (!r.ok()) {
                        if (!planMode) {
                            needsInput = true;
                            guardPartial = true;
                            conclusion = "任务描述与当前仓库事实存在关键不一致，已请求澄清但未收到答复。"
                                    + "为避免误改其它缺陷，本轮未继续修改；请回复问题后重新执行。";
                            out.send(guardEvent("NEEDS_INPUT", "等待用户澄清，已停止继续探索和修改", true));
                            break;
                        }
                        out.send(Map.of("type", "reflect", "reason", "澄清未获答复，计划将基于现有证据收尾。"));
                    }
                    continue;
                }

                ToolLoopGuard.Decision loopDecision = toolLoopGuard.before(call.name(), args);
                if (loopDecision.blocked()) {
                    ToolResult denied = ToolResult.error(loopDecision.message());
                    msgs.add(LlmMessage.tool(call.id(), denied.toContextString()));
                    memory.addStep(new ToolCall(call.name(), args, denied.output(), false));
                    out.send(toolCallEvent(cfg, call.id(), call.name(), args, denied.output(), false));
                    out.send(guardEvent("TOOL_LOOP_BLOCKED", loopDecision.message(), false));
                    toolRegistry.recordRejected(gateId(cfg, call.id()), call.name(), args, ctx,
                            loopDecision.message());
                    policyOnce(policyCodesUsed, "TOOL_LOOP_BLOCKED", loopDecision.message(), roundPolicyMessages);
                    continue;
                }

                // 审批闸门：ask 模式的写操作/bash，或 require-approval 开启时的外部工具（mcp__*）
                if (needsApproval(call.name(), askApproval, conversational)) {
                    String gateId = gateId(cfg, call.id());
                    // 先注册后广播：审批事件先到客户端时，秒回的 POST 会在注册前落空（409 竞态）
                    approvalGate.register(task.conversationId(), gateId);
                    Map<String, Object> req = new HashMap<>();
                    req.put("type", "approval_request");
                    req.put("callId", gateId);
                    req.put("name", call.name());
                    req.put("path", String.valueOf(args.getOrDefault("path", "")));
                    Object cmdArg = args.get("command"); // bash 审批卡展示完整命令
                    if (cmdArg != null) {
                        req.put("command", truncate(String.valueOf(cmdArg), 300));
                    }
                    req.put("summary", toolRegistry.describe(call.name(), args));
                    out.send(req);
                    log.info("[{}] 等待审批: {} {}（call {}）", task.id(), call.name(),
                            args.get("path"), gateId);
                    if (ctx.runContext() != null) taskRunService.beginWait(ctx.runContext());
                    boolean approved;
                    try {
                        approved = approvalGate.await(task.conversationId(), gateId, APPROVAL_TIMEOUT_MS);
                    } finally {
                        if (ctx.runContext() != null) taskRunService.endWait(ctx.runContext());
                    }
                    if (!approved) {
                        ToolResult denied = ToolResult.error(
                                "用户拒绝了此操作（或 5 分钟未确认）。请不要原样重试，"
                                        + "向用户说明该步骤的目的，或提出调整后的方案。");
                        msgs.add(LlmMessage.tool(call.id(), denied.toContextString()));
                        memory.addStep(new ToolCall(call.name(), args,
                                truncate(denied.output(), 2000), false));
                        out.send(toolCallEvent(cfg, call.id(), call.name(), args,
                                truncate(denied.output(), 4000), false));
                        toolRegistry.recordRejected(gateId, call.name(), args, ctx, denied.output());
                        continue;
                    }
                }

                // 执行前发 tool_start（仅推流不落库）：前端显示「正在执行 X」滚动指示，
                // 对应 tool_call 到达（按 callId 匹配）后熄灭
                out.sendLive(Map.of("type", "tool_start",
                        "callId", gateId(cfg, call.id()),
                        "name", call.name(),
                        "target", truncate(toolTarget(args), 120)));
                long toolStarted = System.nanoTime();
                ToolResult result = toolRegistry.execute(gateId(cfg, call.id()), call.name(), args, ctx);
                long toolDurationMs = Math.max(0, (System.nanoTime() - toolStarted) / 1_000_000L);
                msgs.add(LlmMessage.tool(call.id(), result.toContextString()));
                memory.addStep(new ToolCall(call.name(), args,
                        truncate(result.output(), 2000), result.ok()));

                out.send(toolCallEvent(cfg, call.id(), call.name(), args,
                        truncate(result.output(), 4000), result.ok()));
                toolLoopGuard.record(loopDecision.fingerprint(), result.ok(), result.output());

                if (ExecutionEvidence.isDiscoveryTool(call.name())) {
                    evidence.recordDiscovery(result.ok());
                    if (enforceDelivery && evidence.mutations() == 0
                            && evidence.questions() == 0
                            && evidence.discoveryCalls() >= explorationCheckpoint) {
                        String message = "已完成 " + evidence.discoveryCalls()
                                + " 次只读探索。这不是停止条件；请先汇总已有证据，选择信息增益最高的下一步，"
                                + "避免重复浏览。确有必要时可以继续读取。";
                        out.send(guardEvent("EXPLORATION_DECISION", message, false));
                        policyOnce(policyCodesUsed, "EXPLORATION_DECISION", message, roundPolicyMessages);
                        explorationCheckpoint += Math.max(1,
                                props.getAgentPolicy().getExplorationSoftLimit());
                    }
                } else if (ExecutionEvidence.isMutationTool(call.name())) {
                    evidence.recordMutation(call.name(),
                            String.valueOf(args.getOrDefault("path", "")), result.ok());
                } else if ("bash".equals(call.name())) {
                    Set<ExecutionEvidence.VerificationKind> kinds = VerificationClassifier.classify(
                            String.valueOf(args.getOrDefault("command", "")));
                    if (kinds.isEmpty()) {
                        evidence.recordOther(result.ok());
                    } else {
                        for (ExecutionEvidence.VerificationKind kind : kinds) {
                            evidence.recordVerification(kind, result.ok());
                            out.send(verificationEvent(kind, args, result, toolDurationMs));
                        }
                        evidence.finishVerificationCall(result.ok());
                    }
                } else {
                    evidence.recordOther(result.ok());
                }
                if (result.ok()) successfulTools.add(call.name());

                if (enforceDelivery && taskIntent.boundedBugFix() && !deliveryReady
                        && taskIntent.hasDeliveryRequirements()
                        && completionGuard.evaluate(taskIntent, evidence).isEmpty()) {
                    deliveryReady = true;
                    String message = "最小交付证据已齐备（源码修改 + 修改后测试通过）。"
                            + "请立即输出最终结论；不要重跑已经通过的验证，也不要主动扩大范围。";
                    out.send(guardEvent("DELIVERY_READY", message, false));
                    policyOnce(policyCodesUsed, "DELIVERY_READY", message, roundPolicyMessages);
                }

                if (result.ok()) {
                    if (result.file() != null) {
                        memory.addFile(result.file());
                        Map<String, Object> fileEvent = new HashMap<>();
                        fileEvent.put("type", "file");
                        fileEvent.put("path", result.file());
                        fileEvent.put("size", fileSize(ctx, result.file()));
                        out.send(fileEvent);
                    }
                    // P1 代码语义索引增量维护：文件写入/删除成功后重切该文件（主/子泳道同根共享索引）
                    if (FILE_INDEX_TOOLS.contains(call.name())) {
                        try {
                            codeIndexService.invalidatePath(ctx.sandbox(),
                                    String.valueOf(args.getOrDefault("path", "")));
                        } catch (Exception idxEx) {
                            log.debug("代码索引增量更新失败: {}", idxEx.getMessage());
                        }
                    }
                    // 计划进度：只有当前步骤建议的工具与实际成功工具语义兼容时才推进。
                    if (progressIdx < plan.size() && planToolMatches(plan.get(progressIdx).tool(), call.name())) {
                        out.send(Map.of("type", "plan_step",
                                "index", plan.get(progressIdx).index(), "status", "done"));
                        progressIdx++;
                    }
                } else {
                    FailureClassifier.Failure failure = FailureClassifier.classify(call.name(), result.output());
                    int repeats = evidence.recordFailureSignature(failure.signature());
                    out.send(Map.of("type", "reflect",
                            "reason", "工具 " + call.name() + " 失败："
                            + truncate(result.output(), 300) + "，Agent 将调整策略重试。"));
                    int repeatedLimit = Math.max(1, props.getAgentPolicy().getMaxRepeatedFailures());
                    if (repeats % repeatedLimit == 0) {
                        String message = "同类失败已出现 " + repeats + " 次（" + failure.signature() + "）。"
                                + failure.advice() + "任务会继续运行，但不得再原样重试。";
                        out.send(guardEvent("REPEATED_FAILURE_KIND", message, false));
                        policyOnce(policyCodesUsed, "REPEATED_FAILURE_KIND", message, roundPolicyMessages);
                    }
                    int failLimit = Math.max(1, props.getAgentPolicy().getMaxConsecutiveFailures());
                    if (evidence.consecutiveFailures() > 0
                            && evidence.consecutiveFailures() % failLimit == 0) {
                        String message = "已连续失败 " + evidence.consecutiveFailures()
                                + " 次；请先分析失败类别并更换策略。任务不会因为失败次数自动终止。";
                        out.send(guardEvent("REPEATED_FAILURES", message, false));
                        policyOnce(policyCodesUsed, "REPEATED_FAILURES", message, roundPolicyMessages);
                    }
                }
            }
            if (toolLoopGuard.progressVersion() == progressBeforeRound) {
                stagnantRounds++;
                int threshold = Math.max(1, props.getAgentPolicy().getStagnationRoundThreshold());
                if (stagnantRounds % threshold == 0) {
                    String message = "连续 " + stagnantRounds + " 轮没有产生新的文件观察、修改或验证结果。"
                            + "任务不会停止；请回顾已有信息，改变方法或进入下一阶段，禁止换个写法重复同一操作。";
                    out.send(guardEvent("STAGNATION_RECOVERY", message, false));
                    policyOnce(policyCodesUsed, "STAGNATION_RECOVERY", message, roundPolicyMessages);
                }
            } else {
                stagnantRounds = 0;
            }
            if (!roundPolicyMessages.isEmpty()) {
                msgs.add(LlmMessage.user("[运行时策略]\n- "
                        + String.join("\n- ", roundPolicyMessages)));
            }
            if (needsInput || (guardPartial && conclusion != null)) break;
        }

        if (out.cancelled() && conclusion == null) {
            conclusion = !roundText.isEmpty() ? roundText + "\n\n（已停止）" : "（已停止）";
        }
        // 只有子 Agent 仍有局部步数上限；其结果会回到主 Agent，主任务本身不会因此终止。
        boolean stepLimitReached = cfg != null && conclusion == null
                && !out.cancelled() && rounds >= stepLimit;
        // 计划进度收尾：只按真实工具证据结算；不能因模型输出了结论就把所有步骤批量标成 done。
        if (!plan.isEmpty() && !out.cancelled()) {
            for (int i = progressIdx; i < plan.size(); i++) {
                boolean satisfied = !guardPartial && conclusion != null
                        && planToolSatisfied(plan.get(i).tool(), successfulTools);
                out.send(Map.of("type", "plan_step",
                        "index", plan.get(i).index(), "status", satisfied ? "done" : "failed"));
            }
        }
        if (conclusion == null) {
            conclusion = cfg != null
                    ? "子智能体已达到局部步数上限（" + stepLimit + " 步），结果将交回主 Agent 继续处理。"
                    : "任务未生成可用结论。";
            if (cfg == null) guardPartial = true;
        }
        return new LoopResult(conclusion, stepLimitReached,
                guardPartial || needsInput || budgetExceeded, budgetExceeded);
    }

    private record LoopResult(String conclusion, boolean stepLimitReached, boolean guardPartial,
                              boolean budgetExceeded) {}

    /** A runtime-policy nudge is worth saying once; repeating it only dilutes the real instruction. */
    private static void policyOnce(Set<String> used, String code, String message, List<String> sink) {
        if (used.add(code)) sink.add(message);
    }

    /** Tool set the loop falls back to once the budget is spent: observe and report, do not change. */
    private List<LlmToolSpec> readOnlySpecs(boolean conversational) {
        List<LlmToolSpec> specs = new ArrayList<>(toolRegistry.specsFor(PLAN_TOOLS));
        if (conversational) specs.add(askUserSpec());
        specs.sort(java.util.Comparator.comparing(LlmToolSpec::name));
        return specs;
    }

    /**
     * 派出子智能体（spawnAgent 特判体）：按档案构造子 Task（继承沙箱/权限/模型），
     * 档案 prompt + 任务描述作为最高优先级指令注入受限循环（guided 模式跳过 Planner），
     * 工具按白名单过滤；agent_spawn / agent_done 手工发出，泳道事件经 Emitter 自动带 agent 字段
     * 并入父 transcript（落库可回放）。结论作为 ToolResult 返回给主 Agent。
     */
    private ToolResult spawnSubAgent(Task parent, SseEmitter sse, ChatClient client,
                                     List<Knowledge> knowledge, Sandbox sandbox, ToolContext parentCtx,
                                     List<LlmMessage> history, List<Map<String, Object>> transcript,
                                     Emitter out, StreamHub.Hub hub, Map<String, Object> args) {
        String name = String.valueOf(args.getOrDefault("agent", "")).trim();
        String subTask = String.valueOf(args.getOrDefault("task", "")).trim();
        if (name.isEmpty() || subTask.isEmpty()) {
            return ToolResult.error("spawnAgent 需要 agent（子智能体名称）与 task（自包含任务描述）两个参数");
        }
        Optional<AgentProfile> picked = agentProfileStore.findByName(name);
        if (picked.isEmpty()) {
            String available = agentProfileStore.listEnabled().stream()
                    .map(AgentProfile::name)
                    .reduce((a, b) -> a + "、" + b).orElse("（无已启用的子智能体）");
            return ToolResult.error("子智能体不存在: " + name + "（可用: " + available + "）");
        }
        AgentProfile profile = picked.get();
        if (!profile.enabled()) {
            return ToolResult.error("子智能体已禁用: " + profile.name());
        }
        String lane = profile.name() + "-" + laneSeq.incrementAndGet();
        out.send(Map.of("type", "agent_spawn", "agent", lane, "task", truncate(subTask, 200)));
        log.info("[{}] 派出子智能体 {}（{}）: {}", parent.id(), lane, profile.name(),
                truncate(subTask, 80));

        // 计划模式下子智能体白名单收敛为「档案白名单 ∩ 只读集」（防用户档案带写工具破坏只读承诺）；
        // askUser 仅主 Agent 计划模式可用，子智能体不可向用户提问
        boolean parentPlanMode = "plan".equalsIgnoreCase(parent.approval());
        List<String> subTools = parentPlanMode
                ? profile.allowedTools().stream()
                        .filter(t -> PLAN_TOOLS.contains(t)
                                && !com.example.harness.core.question.AskUserTool.NAME.equals(t))
                        .toList()
                : profile.allowedTools();

        Task sub = new Task(parent.id() + "-agent", subTask, parent.root(),
                parent.conversationId(), LocalDateTime.now(), parent.modelId(),
                parent.deepThink(), parent.approval(), null);
        WorkingMemory memory = new WorkingMemory(sub);
        ToolContext ctx = new ToolContext(sandbox, memory,
                "full".equalsIgnoreCase(parent.approval()), parentCtx.runContext(), lane);
        memory.putVariable("root", sandbox.root().toString());
        SubAgentConfig cfg = new SubAgentConfig(lane, List.of(), new SharedBlackboard(),
                hub != null ? hub.cancelFlag : new AtomicBoolean(false),
                profile.effectiveMaxSteps(props.getMultiAgent().getSubAgentMaxSteps()),
                subTools);
        String instruction = "你是子智能体「" + profile.name() + "」，由主 Agent 派出独立完成专项任务，"
                + "完成后直接输出简明中文结论（不要向用户提问）。\n\n【职责说明】\n" + profile.prompt()
                + "\n\n【本次任务】\n" + subTask;
        String conclusion;
        try {
            conclusion = executeSubAgent(sub, sse, client, knowledge, memory, sandbox, ctx,
                    history, transcript, instruction, cfg, hub);
        } catch (Exception e) {
            log.error("[{}] 子智能体 {} 执行异常", parent.id(), lane, e);
            conclusion = "执行异常：" + e.getMessage();
        }
        String status = conclusion.contains("（已停止）") ? "cancelled"
                : (conclusion.startsWith("执行异常：") || conclusion.startsWith("已达最大步数"))
                ? "failed" : "success";
        List<String> files = memory.files().stream().distinct().toList();
        Map<String, Object> done = new HashMap<>();
        done.put("type", "agent_done");
        done.put("agent", lane);
        done.put("status", status);
        done.put("summary", truncate(conclusion, 300));
        done.put("files", files);
        out.send(done);
        return ToolResult.success("【子智能体 " + profile.name() + " · " + status + "】\n"
                + truncate(conclusion, 6000));
    }

    /** tool_call 事件（子 Agent 时 callId 加前缀，Emitter 注入 agent 字段）。 */
    private Map<String, Object> toolCallEvent(SubAgentConfig cfg, String callId, String name,
                                              Map<String, Object> args, String result, boolean ok) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "tool_call");
        event.put("callId", gateId(cfg, callId));
        event.put("name", name);
        event.put("args", args);
        event.put("result", result);
        event.put("ok", ok);
        return event;
    }

    private static String gateId(SubAgentConfig cfg, String callId) {
        return cfg != null ? cfg.gateCallId(callId) : callId;
    }

    /** 工具执行目标摘要（tool_start 展示用）：取路径/命令/目录等首个非空字段。 */
    private String toolTarget(Map<String, Object> args) {
        for (String key : List.of("path", "command", "dir", "url", "query", "keywords", "agent", "task")) {
            Object v = args.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s.replace('\n', ' ');
            }
        }
        return "";
    }

    /** 子 Agent 写操作占坑校验：路径归一化为相对工作根后交给 cfg 判定；越界路径交给沙箱拒绝。 */
    private String claimConflict(SubAgentConfig cfg, String toolName, Map<String, Object> args,
                                 ToolContext ctx, Sandbox sandbox) {
        Object p = args.get("path");
        if (!(p instanceof String s) || s.isBlank()) {
            return null;
        }
        String rel;
        try {
            rel = sandbox.relativize(ctx.resolve(s));
        } catch (Exception e) {
            return null; // 越界/非法路径不参与占坑，由工具执行时的沙箱校验拒绝
        }
        if (rel.isBlank() || ".".equals(rel) || rel.contains(":") || rel.startsWith("/")) {
            return null; // 工作根本身或工作根外的路径不参与占坑
        }
        return cfg.claimConflict(toolName, rel);
    }

    /** assistant 消息落库：内容=结论文本，events=该轮事件流（供历史会话完整回放）。 */

    /**
     * 单轮 LLM 流式调用：delta 实时下发并累积到对应缓冲。
     * specs 为空列表表示不带工具（步数耗尽的收尾总结轮，模型只能输出文本）。
     */
    private ChatResult chatRound(ChatClient client, List<LlmMessage> msgs, List<LlmToolSpec> specs,
                                 StringBuilder roundText, StringBuilder roundReasoning,
                                 Emitter out) {
        return client.chat(msgs, specs,
                new ChatClient.StreamListener() {
            @Override
            public void onText(String delta) {
                roundText.append(delta);
                out.sendLive(Map.of("type", "text_delta", "delta", delta));
            }

            @Override
            public void onReasoning(String delta) {
                roundReasoning.append(delta);
                out.sendLive(Map.of("type", "thinking_delta", "delta", delta));
            }
        });
    }

    /**
     * Whether a separate planning LLM round trip earns its latency. A bounded bug fix or a short
     * prompt is already its own plan; planning it again only delays the first tool call.
     */
    private boolean shouldPlan(Task task, TaskIntent intent) {
        String mode = props.getPlanner().getMode();
        if ("off".equalsIgnoreCase(mode)) return false;
        if ("always".equalsIgnoreCase(mode)) return true;
        if (intent.boundedBugFix()) return false;
        String prompt = task.prompt() == null ? "" : task.prompt().trim();
        return prompt.length() > props.getPlanner().getAutoPromptChars();
    }

    /** 本轮请求的上下文长度上限（按任务指定的模型档案解析，子 Agent 继承父任务 modelId）。 */
    private int contextLimitOf(Task task) {
        try {
            return modelRegistry.contextLengthOf(task.modelId());
        } catch (Exception e) {
            return 131_072;
        }
    }

    /**
     * 任务内压缩（软阈值）：msgs 估算 + 输出预留超过 limit×soft 时，把旧轮工具输出原位替换为
     * 占位符（保留最近 keep-recent-rounds 轮完整），并发 context_compact 事件（落库可回放）。
     */
    private void compactIfNeeded(Task task, Emitter out, List<LlmMessage> msgs) {
        if (!props.getContext().isEnabled()) return;
        int limit = contextLimitOf(task);
        int soft = (int) (limit * props.getContext().getSoftThreshold());
        int used = TokenEstimator.estimateMessages(msgs);
        int reserve = 4096; // 为本轮输出与下轮工具回灌预留
        if (used + reserve <= soft) return;
        int before = used;
        int rounds = Math.max(1, props.getContext().getKeepRecentRounds());
        com.example.harness.core.context.ToolOutputCompactor.compact(msgs, rounds);
        int after = TokenEstimator.estimateMessages(msgs);
        if (after < before) {
            out.send(Map.of("type", "context_compact",
                    "before", before, "after", after, "scope", "task"));
            log.info("[{}] 任务内上下文压缩：{}→{} tokens", task.id(), before, after);
        }
    }

    /** 紧急压缩（400 超限兜底）：占位替换到只保留最近 1 轮，仍超限则裁剪最旧的逐字历史。 */
    private void compactAggressive(Task task, Emitter out, List<LlmMessage> msgs) {
        int before = TokenEstimator.estimateMessages(msgs);
        com.example.harness.core.context.ToolOutputCompactor.compact(msgs, 1);
        com.example.harness.core.context.ToolOutputCompactor.hardTrimHistory(msgs);
        int after = TokenEstimator.estimateMessages(msgs);
        if (after < before) {
            out.send(Map.of("type", "context_compact",
                    "before", before, "after", after, "scope", "task"));
            log.warn("[{}] 上下文超限，已紧急压缩：{}→{} tokens", task.id(), before, after);
        }
    }

    /**
     * 每轮 LLM 调用后推 usage 事件（仅推流不落库）：优先 API 回传的真实 prompt_tokens，
     * 无则启发式估算（estimated=true）；前端实时更新上下文容量徽标。
     * 主泳道（非子 Agent）的真实用量同时持久化到会话，作为容量徽标的下限。
     */
    private void emitUsage(Emitter out, List<LlmMessage> msgs, ChatResult resp,
                           Task task, boolean mainLane, ChatClient client) {
        ChatResult.Usage usage = resp.usage();
        Integer prompt = usage != null ? usage.promptTokens() : null;
        if (mainLane && prompt != null && prompt > 0
                && task.conversationId() != null && !task.conversationId().isBlank()) {
            conversationStore.updateLastPromptTokens(task.conversationId(), prompt);
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "context_usage");
        if (prompt != null && prompt > 0) {
            ev.put("promptTokens", prompt);
            ev.put("estimated", false);
        } else {
            ev.put("promptTokens", TokenEstimator.estimateMessages(msgs));
            ev.put("estimated", true);
        }
        out.sendLive(ev);
    }

    /** 错误信息是否为上下文超限（OpenAI/DeepSeek/通义的常见文案）。 */
    private static boolean isContextOverflow(Throwable e) {
        String m = String.valueOf(e.getMessage()).toLowerCase();
        return m.contains("context_length_exceeded") || m.contains("maximum context length")
                || m.contains("context length") || m.contains("context window")
                || m.contains("too many tokens") || m.contains("token limit")
                || m.contains("input length") || m.contains("上下文长度");
    }

    private String systemPrompt(Sandbox sandbox, List<PlanStep> plan, List<Knowledge> knowledge,
                                boolean projectMode, String approval) {
        return baseSystemPrompt(sandbox.root().toString(), plan, knowledge, projectMode, approval,
                toolRegistry.extras(), agentProfileStore.listEnabled());
    }

    /**
     * 系统提示构建（static：ContextService 基线估算复用同一模板，传入占位根目录与空计划即可）。
     * agents 为已启用的子智能体档案（spawnAgent 清单），估算了它们的真实请求口径。
     */
    public static String baseSystemPrompt(String sandboxRoot, List<PlanStep> plan, List<Knowledge> knowledge,
                                          boolean projectMode, String approval, List<Tool> extras,
                                          List<AgentProfile> agents) {
        boolean fullAccess = "full".equalsIgnoreCase(approval);
        boolean planPrompt = "plan".equalsIgnoreCase(approval);
        StringBuilder sb = new StringBuilder();
        sb.append("你是运行在本机的文件操作 Agent（harness）。当前工作根目录（沙箱）：")
                .append(sandboxRoot).append("\n规则：\n");
        if (planPrompt) {
            // 计划模式：工具说明只列只读白名单——若照搬常规文案描述 fileEdit/bash 等，
            // 模型会照提示词文本发起写类调用（即使 tools 数组没有，也会凭描述构造调用）
            sb.append("1. 本次处于【计划模式】（[MODE=PLAN]）：只读调研并产出计划文档，")
                    .append("不得修改任何文件、不得执行任何命令。\n");
            sb.append("2. 可用工具（只读白名单，除此之外不存在其它工具）：")
                    .append("fileRead 读取文件（支持 offset/limit 分页）；listFiles 浏览目录；")
                    .append("httpFetch 抓取网页（只读）；askUser 就关键分歧向用户提问；")
                    .append("spawnAgent 派出子智能体做只读调研（其可用工具同样受限）。")
                    .append("fileWrite/fileEdit/fileDelete/docxWrite/bash 均不可用，尝试调用会被直接拒绝。\n");
            sb.append("3. 调研方法：先 listFiles 了解项目结构，再 fileRead 精读与任务相关的文件，")
                    .append("记录关键文件路径与行号/职责，调研深度以能产出可执行的实施计划为准。\n");
            sb.append("4. 澄清提问（askUser）：遇到影响方案方向的关键分歧（技术选型/范围/交互形态等）时提问——")
                    .append("一次一个问题，给 2~4 个候选项，推荐项放最前并设置 recommended 下标；整次任务最多 3 问；")
                    .append("用户也可能自由输入；提问未获答复时不要重问，自行决策继续。\n");
            sb.append("5. 每次调用工具前用一两句话说明本轮要做什么（会展示给用户）。\n");
            sb.append("6. 计划必须针对【本轮用户请求】：对话历史仅作背景参考，")
                    .append("严禁把历史轮次的结论或内容当作本轮产出复述。\n");
            sb.append("7. 最终结论输出一份完整的计划文档（Markdown），结构：① 目标 ② 现状调研结论")
                    .append("（关键文件路径 + 行号/职责）③ 实施方案 ④ 分步实施清单（每步含涉及文件与改动内容）")
                    .append("⑤ 风险与验证方式 ⑥ 待确认事项（如有）。输出计划时不要再调用工具。\n");
        } else {
            if (fullAccess) {
                sb.append("1. 用户已授予「完全访问」权限：允许使用绝对路径读写本机任意位置的文件（含工作根之外）；")
                        .append("相对路径仍相对工作根。权限很大，请谨慎操作：只动与任务相关的文件，不做破坏性删除。\n");
            } else {
                sb.append("1. 所有文件路径必须是相对工作根的相对路径；绝对路径与 ../ 逃逸会被沙箱拒绝。\n");
            }
            sb.append("2. 目标是用尽量少的步骤把事情做对：只处理用户本次明确提出的问题，不主动扩大范围，")
                    .append("典型缺陷修复应在 15 次工具调用内完成。基础文件工具：")
                    .append("fileWrite 创建或覆盖文本文件；fileEdit 精确修改已有文件；")
                    .append("fileRead 读取文件（支持 offset/limit 分页，多个文件用 paths 一次批量读）；listFiles 浏览目录；")
                    .append("fileDelete 删除文件；docxWrite 生成 Word 文档；")
                    .append("grep/codeSearch 定位源码；bash 只用于运行程序、测试、构建和 Git。")
                    .append("bash 的真实方言见 WORKSPACE_SNAPSHOT，命令必须匹配该方言；优先用 working_dir 指定模块，")
                    .append("不要反复 cd 或切换盘符。\n");
            // 扩展工具（httpFetch、mcp__* 等）动态追加，避免硬编码清单随工具集演进而失真
            if (!extras.isEmpty()) {
                sb.append("· 扩展工具（按需使用，调用前看清各自参数）：\n");
                for (Tool t : extras) {
                    sb.append("  - ").append(t.name()).append("：").append(truncate(t.description(), 100)).append('\n');
                }
            }
            sb.append("3. 定位问题：先用 grep/codeSearch 搜索，再 fileRead 精读命中的文件；")
                    .append("每次调用都必须带来新的事实、修改或验证状态，")
                    .append("相同工具与参数在没有新进展时会被局部熔断。")
                    .append("不得为了确认 Maven/JUnit 是否存在而遍历本机依赖缓存。\n")
                    .append("4. 修改代码：用 fileEdit 做最小替换——先 fileRead 拿到原文，再提供逐字符一致的 old_string，")
                    .append("不要整份重写；一次补丁补齐同一逻辑单元的全部改动（含 import），不要拆成多次试错。\n")
                    .append("5. 验证：改完跑一次相关测试或构建即可，退出码为 0 就是有效证据。")
                    .append("不要重跑已经通过的验证，不要读取 surefire 报告，")
                    .append("也不要把构建日志写入文件再 grep/sed 回读——用例数、失败用例与断言差异已由 bash 自动摘录置顶。")
                    .append("Maven 不得擅自使用 -o 离线模式。除非用户明确要求真实运行时验证，")
                    .append("不得后台启动应用、探测端口或创建临时脚本；优先使用单元/集成测试。\n")
                    .append("6. 仓库当前状态就是你的起点：不得用 git stash / git checkout -- / git reset / git show <rev>:file ")
                    .append("回退或还原代码来制造复现条件，除非用户明确要求。")
                    .append("若发现缺陷已被修复或与描述不符，直接验证并说明，不要为了拿到红灯而撤销现有改动。\n")
                    .append("7. 每次调用工具前用一两句话说明本轮要做什么（会展示给用户）。\n")
                    .append("8. 通常自主完成任务。仅当至少两次相关读取/检索已经证明任务描述与仓库事实实质冲突、且尚未修改文件时，")
                    .append("可用 askUser 快速澄清一次；未答复则暂停，不得擅自改修成另一个 Bug。\n")
                    .append("9. 最终结论面向用户且自成一体（用户看不到你的中间执行过程）：")
                    .append("说明完成了什么、改动了哪些文件（相对路径 + 一句话）、验证方式与结果；")
                    .append("有遗留事项附在最后。不要延续中间步骤的编号或语气，不要用「然后/接着」开头。\n");
            if ("ask".equalsIgnoreCase(approval)) {
                sb.append("10. 权限模式为「请求审批」：每次写入/修改/删除文件或执行 bash 命令前系统会先请用户确认；")
                        .append("若被拒绝，不要原样重试，说明该步骤的目的或给出调整后的方案。\n");
            }
        }
        // 可派出的子智能体清单（spawnAgent 体系）：名称 + 何时用（计划模式同样可派，子智能体工具受限）
        if (agents != null && !agents.isEmpty()) {
            sb.append("· 可派出的子智能体（用 spawnAgent 工具，参数 agent=名称、task=自包含任务描述）：\n");
            for (AgentProfile a : agents) {
                sb.append("  - ").append(a.name()).append("：").append(truncate(a.description(), 120)).append('\n');
            }
            sb.append("  使用原则：简单的单文件任务自己做（派出有开销，子智能体看不到对话历史，task 必须自包含）；")
                    .append("项目模式下做功能改动前，若对相关代码不熟悉，先派「代码探索」调研再动手。\n");
        }
        if (projectMode) {
            sb.append("· 这是用户的真实本地项目（非演示沙箱）：动手前先浏览与阅读现状，")
                    .append("只做与任务相关的最小改动，保持既有代码风格；")
                    .append("不要删除、重命名或重写与任务无关的文件；改动会在 Git 变更面板中展示给用户。\n");
        }
        if (!plan.isEmpty()) {
            sb.append("· 参考执行计划（可按需调整）：\n");
            for (var p : plan) {
                sb.append("   ").append(p.index()).append(". ").append(p.step());
                if (p.tool() != null) sb.append(" [").append(p.tool()).append("]");
                sb.append("\n");
            }
        }
        String memory = TaskDispatcher.knowledgePrompt(knowledge);
        if (!memory.isEmpty()) {
            sb.append('\n').append(memory);
        }
        return sb.toString();
    }

    /** 审批闸门判定：ask 模式的写操作，或 require-approval 开启时（会话内）的外部工具。 */
    private boolean needsApproval(String toolName, boolean askApproval, boolean conversational) {
        if (askApproval && MUTATING_TOOLS.contains(toolName)) return true;
        return props.getMcp().isRequireApproval()
                && toolName != null && toolName.startsWith("mcp__")
                && conversational;
    }

    /** 计划模式工具清单：只读白名单 + askUser，再并回 spawnAgent（specsFor 恒排除它）。 */
    private List<LlmToolSpec> planModeSpecs() {
        List<LlmToolSpec> specs = new ArrayList<>(toolRegistry.specsFor(PLAN_TOOLS));
        specs.add(askUserSpec());
        toolRegistry.specs().stream()
                .filter(s -> SpawnAgentTool.NAME.equals(s.name()))
                .findFirst()
                .ifPresent(specs::add);
        return specs;
    }

    /** TASK 模式只在有会话可等待答复时注入 askUser；它永远不进入普通工具注册表。 */
    private List<LlmToolSpec> taskModeSpecs(boolean conversational) {
        List<LlmToolSpec> specs = new ArrayList<>(toolRegistry.specs());
        if (conversational) specs.add(askUserSpec());
        specs.sort(java.util.Comparator.comparing(LlmToolSpec::name));
        return specs;
    }

    private LlmToolSpec askUserSpec() {
        com.example.harness.core.question.AskUserTool tool =
                new com.example.harness.core.question.AskUserTool();
        return new LlmToolSpec(tool.name(), tool.description(), tool.parametersJson());
    }

    private Map<String, Object> verificationEvent(ExecutionEvidence.VerificationKind kind,
                                                   Map<String, Object> args, ToolResult result,
                                                   long durationMs) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "verification");
        event.put("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
        event.put("name", switch (kind) {
            case REPRO -> "复现";
            case TEST -> "测试";
            case BUILD -> "构建";
            case LINT -> "检查";
        });
        event.put("command", truncate(String.valueOf(args.getOrDefault("command", "")), 500));
        event.put("ok", result.ok());
        event.put("durationMs", durationMs);
        event.put("output", truncate(result.output(), 1200));
        return event;
    }

    private Map<String, Object> guardEvent(String code, String message, boolean terminal) {
        return Map.of("type", "guard", "code", code, "message", message, "terminal", terminal);
    }

    private String completionFeedback(List<CompletionGuard.Missing> missing, int attempt) {
        return "【运行时交付闸门 · 第 " + attempt + " 次】你准备结束任务，但以下 done when 尚未满足：\n- "
                + missing.stream().map(CompletionGuard.Missing::message)
                .reduce((a, b) -> a + "\n- " + b).orElse("缺少必要证据")
                + "\n请继续使用工具补齐证据。不要只解释或重复结论；若确实无法完成，请如实说明阻塞。";
    }

    private static boolean planToolMatches(String planned, String actual) {
        if (planned == null || planned.isBlank() || actual == null) return false;
        if (planned.equals(actual)) return true;
        Set<String> discovery = Set.of("fileRead", "listFiles", "grep", "codeSearch");
        Set<String> mutation = Set.of("fileWrite", "fileEdit", "fileDelete", "docxWrite");
        return (discovery.contains(planned) && discovery.contains(actual))
                || (mutation.contains(planned) && mutation.contains(actual));
    }

    private static boolean planToolSatisfied(String planned, Set<String> successfulTools) {
        // 无建议工具的“报告/收尾”步骤只能在本轮确实产生过成功工具证据后完成；
        // 这样提前空口结论不会把整张计划卡刷成 N/N。
        if (planned == null || planned.isBlank()) return !successfulTools.isEmpty();
        return successfulTools.stream().anyMatch(actual -> planToolMatches(planned, actual));
    }

    private long fileSize(ToolContext ctx, String relPath) {
        try {
            return Files.size(ctx.resolve(relPath));
        } catch (Exception e) {
            return -1;
        }
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return om.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 步骤间变量传递：{{var}} 占位替换（如 lastFile）。 */
    private void substituteVars(Map<String, Object> args, WorkingMemory memory) {
        if (args == null || args.isEmpty()) return;
        for (Map.Entry<String, Object> e : List.copyOf(args.entrySet())) {
            if (e.getValue() instanceof String s && s.contains("{{")) {
                args.put(e.getKey(), memory.substitute(s));
            }
        }
    }

    /** 钩子失败回灌消息：让模型修复后重新给出结论。 */
    private String hookFeedback(List<com.example.harness.core.hooks.HookService.HookResult> failures) {
        return "【交付前验证失败】你在上面给出的结论被项目的验证钩子拦截，以下验证命令未通过：\n"
                + hookFailureLines(failures)
                + "\n请先修复相关问题（可用 fileEdit/bash 等工具），然后重新输出最终结论；"
                + "结论中要说明验证结果。不要原样重复上面的结论。";
    }

    private String hookFailureLines(List<com.example.harness.core.hooks.HookService.HookResult> failures) {
        StringBuilder sb = new StringBuilder();
        for (var f : failures) {
            sb.append("- ✘ ").append(f.name()).append("（").append(f.command()).append("，")
                    .append(f.durationMs()).append("ms）\n  输出摘要：")
                    .append(truncate(f.output(), 300)).append('\n');
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }

    /**
     * 单次执行的 SSE 发送上下文：send 落库回放，sendLive 只推流不落库；
     * hub 非空时两类事件都同步写入会话事件缓冲（供 /live 重放续流）。
     * 取消语义（体验升级七）：shared 标志（hub.cancelFlag 或 Orchestrator sharedCancel）置位
     * 或自身 local 置位时中止；发送失败仅在无 shared 标志时视为客户端断开（旧 /api/task 语义），
     * 会话任务断开后任务继续后台跑完并落库，停止必须经 /stop 显式触发。
     * M4：agentTag 非空时给事件注入 "agent" 字段（含 sendLive 的 delta），子 Agent 事件
     * 全部可归入泳道。
     */
    public static final class Emitter {
        private final SseEmitter emitter;
        private final List<Map<String, Object>> transcript;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** 客户端已断开：不再反复写一条死连接（事件仍进 hub 缓冲，/live 可完整重放）。 */
        private final AtomicBoolean broken = new AtomicBoolean(false);
        private final AtomicBoolean shared;
        private final String agentTag;
        private final StreamHub.Hub hub;

        public Emitter(SseEmitter emitter, List<Map<String, Object>> transcript) {
            this(emitter, transcript, null, null, null);
        }

        public Emitter(SseEmitter emitter, List<Map<String, Object>> transcript,
                       AtomicBoolean sharedCancel, String agentTag) {
            this(emitter, transcript, sharedCancel, agentTag, null);
        }

        public Emitter(SseEmitter emitter, List<Map<String, Object>> transcript,
                       AtomicBoolean sharedCancel, String agentTag, StreamHub.Hub hub) {
            this.emitter = emitter;
            this.transcript = transcript;
            this.shared = sharedCancel;
            this.agentTag = agentTag;
            this.hub = hub;
        }

        /** 事件推流并记入 transcript（历史回放）。 */
        public void send(Map<String, Object> event) {
            event = tag(event);
            if (transcript != null) transcript.add(event);
            if (hub != null) hub.record(event);
            doSend(event);
        }

        /** 仅推流（delta 类事件不落库，防 DB 膨胀）；hub 缓冲仍记录（供 /live 重放）。 */
        public void sendLive(Map<String, Object> event) {
            Map<String, Object> tagged = tag(event);
            if (hub != null) hub.record(tagged);
            doSend(tagged);
        }

        public boolean cancelled() {
            return cancelled.get() || (shared != null && shared.get());
        }

        private Map<String, Object> tag(Map<String, Object> event) {
            if (agentTag == null || event.containsKey("agent")) {
                return event;
            }
            Map<String, Object> tagged = new LinkedHashMap<>(event);
            tagged.put("agent", agentTag);
            return tagged;
        }

        private void doSend(Map<String, Object> event) {
            // 取消后仍照常尝试推送（停止的收尾事件——（已停止）结论/工具结果——要送达仍在连接的客户端）；
            // 客户端已断开时 emitter.send 抛异常走 catch：shared 非空仅忽略，shared 为空（旧 /api/task）视为断开取消
            if (broken.get() || !SafeSseEmitter.isAlive(emitter)) {
                return; // 连接已死，继续写只会刷屏报错；事件已进 hub 缓冲
            }
            try {
                emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                broken.set(true);
                // 有 shared 标志（会话任务/子 Agent）：断开不再取消，任务后台继续跑完；
                // 无 shared（旧 /api/task 入口）：保持旧语义，发送失败视为客户端断开
                if (shared == null) {
                    cancelled.set(true);
                }
                log.debug("SSE 发送失败（客户端可能已断开/停止）: {}", e.getMessage());
            }
        }
    }
}
