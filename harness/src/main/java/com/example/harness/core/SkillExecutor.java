package com.example.harness.core;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.task.ExecutionResult;
import com.example.harness.core.task.RunStatus;
import com.example.harness.core.memory.EpisodicStore;
import com.example.harness.core.memory.WorkingMemory;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.LlmMessage;
import com.example.harness.model.PlanStep;
import com.example.harness.model.Task;
import com.example.harness.model.ToolCall;
import com.example.harness.model.Trace;
import com.example.harness.sandbox.Sandbox;
import com.example.harness.skill.ParameterFiller;
import com.example.harness.skill.Skill;
import com.example.harness.skill.SkillParser;
import com.example.harness.skill.SkillStep;
import com.example.harness.core.ToolRegistry;
import com.example.harness.tools.ToolContext;
import com.example.harness.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能执行器（M5 双形态）：
 * - mode=steps：先填参，再顺序执行步骤并复用 M1 的沙箱与工具；
 * - mode=prompt：散文 SKILL.md 填参后作为最高优先级指令注入 AgentLoop，由 LLM 带着判断力执行。
 * 开始时发出 skill 事件（落库，回放可识别）；权限模式=请求审批时写操作先经用户确认。
 */
@Component
public class SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutor.class);

    /** 请求审批的等待上限（SSE 总超时 10 分钟，预留收尾时间）。 */
    private static final long APPROVAL_TIMEOUT_MS = 300_000;

    private final ToolRegistry toolRegistry;
    private final ParameterFiller parameterFiller;
    private final EpisodicStore episodicStore;
    private final com.example.harness.core.ApprovalGate approvalGate;
    private final AgentLoop agentLoop;
    private final com.example.harness.config.HarnessProperties props;
    private final ObjectMapper om;
    private final com.example.harness.skill.SkillStore skillStore;
    private final com.example.harness.core.task.TaskRunService taskRunService;
    private final com.example.harness.core.task.LlmCallTracker llmCallTracker;

    public SkillExecutor(ToolRegistry toolRegistry,
                         ParameterFiller parameterFiller,
                         EpisodicStore episodicStore,
                         com.example.harness.core.ApprovalGate approvalGate,
                         AgentLoop agentLoop,
                         com.example.harness.config.HarnessProperties props,
                         ObjectMapper om,
                         com.example.harness.skill.SkillStore skillStore,
                         com.example.harness.core.task.TaskRunService taskRunService,
                         com.example.harness.core.task.LlmCallTracker llmCallTracker) {
        this.toolRegistry = toolRegistry;
        this.parameterFiller = parameterFiller;
        this.episodicStore = episodicStore;
        this.approvalGate = approvalGate;
        this.agentLoop = agentLoop;
        this.props = props;
        this.om = om;
        this.skillStore = skillStore;
        this.taskRunService = taskRunService;
        this.llmCallTracker = llmCallTracker;
    }

    public ExecutionResult execute(Skill skill, Task task, SseEmitter emitter, ChatClient client,
                        List<com.example.harness.model.Knowledge> knowledge,
                        WorkingMemory memory, Sandbox sandbox, ToolContext ctx,
                        List<LlmMessage> history, List<Map<String, Object>> transcript) {
        return execute(skill, task, emitter, client, knowledge, memory, sandbox, ctx,
                history, transcript, null);
    }

    /** 带 Hub 的会话入口（体验升级七）：事件同步进会话缓冲；hub.cancelFlag 置位后尽快收尾。 */
    public ExecutionResult execute(Skill skill, Task task, SseEmitter emitter, ChatClient client,
                        List<com.example.harness.model.Knowledge> knowledge,
                        WorkingMemory memory, Sandbox sandbox, ToolContext ctx,
                        List<LlmMessage> history, List<Map<String, Object>> transcript,
                        com.example.harness.core.stream.StreamHub.Hub hub) {
        // 形态判定（M5-F1 降级规则）：声明 steps 但引用了未注册工具 → 按 prompt 执行
        // （社区技能正文常含 `1. tool(...)` 样例行，会被误判为步骤）
        boolean stepsExecutable = !skill.steps().isEmpty()
                && skill.steps().stream().allMatch(s -> toolRegistry.isRegistered(s.tool()));
        boolean promptMode = "prompt".equalsIgnoreCase(skill.mode()) || !stepsExecutable;
        if (!stepsExecutable && "steps".equalsIgnoreCase(skill.mode())) {
            log.warn("技能 [{}] 声明为 steps 但存在未注册工具，降级为 prompt 执行", skill.name());
        }
        // P0 使用统计：命中即计数（显式 chip / `/` 提及 / 意图路由统一入口在此）
        skillStore.incrementHit(skill.id());
        if (promptMode) {
            return executeGuided(skill, task, emitter, client, knowledge, memory, ctx, history, transcript, hub);
        }
        return executeSteps(skill, task, emitter, client, memory, sandbox, ctx, history, transcript, hub);
    }

    /** 提示词技能：填参 → 正文注入 AgentLoop（跳过 Planner，事件流/审批/落库全复用）。 */
    private ExecutionResult executeGuided(Skill skill, Task task, SseEmitter emitter, ChatClient client,
                               List<com.example.harness.model.Knowledge> knowledge,
                               WorkingMemory memory, ToolContext ctx,
                               List<LlmMessage> history, List<Map<String, Object>> transcript,
                               com.example.harness.core.stream.StreamHub.Hub hub) {
        try {
            Map<String, Object> skillEvent = new HashMap<>();
            skillEvent.put("type", "skill");
            skillEvent.put("id", skill.id());
            skillEvent.put("name", skill.name());
            send(emitter, skillEvent, transcript, hub);

            Map<String, String> params = parameterFiller.fill(skill, task.prompt(),
                    llmCallTracker.tracked(client, ctx.runContext(), "parameter_filler", ctx.agentId()));
            log.info("[{}] 提示词技能 [{}] 填参结果: {}", task.id(), skill.name(), params);
            String body = applyParams(SkillParser.stripFrontmatter(skill.source()), params);

            StringBuilder instruction = new StringBuilder();
            instruction.append("【技能执行】请严格遵循以下技能说明完成任务，技能说明是最高优先级约束：\n")
                    .append("======== 技能说明（").append(skill.name()).append("）========\n")
                    .append(body).append('\n')
                    .append("======== 技能说明结束 ========\n")
                    .append("用户任务：").append(task.prompt() == null ? "" : task.prompt()).append('\n')
                    .append("说明中未被替换的 {{参数}} 表示该值尚未确定，请结合任务语境自行决定合理取值，")
                    .append("不要把参数名或花括号原样写进产出文件。");
            return agentLoop.execute(task, emitter, client, knowledge, memory, ctx.sandbox(), ctx,
                    history, transcript, instruction.toString(), hub).withMode("SKILL");
        } catch (Exception e) {
            log.error("[{}] 提示词技能执行异常", task.id(), e);
            skillStore.incrementFail(skill.id());
            String conclusion = "技能执行异常：" + e.getMessage();
            return ExecutionResult.failed(conclusion, null, "SKILL", "SKILL_ERROR", e.getMessage());
        }
    }

    /** 正文占位符替换：未填上（空值/等于参数名）的保留 {{name}}，由模型按任务语境决策。 */
    private String applyParams(String body, Map<String, String> params) {
        String out = body;
        for (Map.Entry<String, String> e : params.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isBlank() || v.equals(e.getKey())) continue;
            out = out.replace("{{" + e.getKey() + "}}", v);
        }
        return out;
    }

    /** 步骤技能（原 M2 行为，保持不变）。 */
    private ExecutionResult executeSteps(Skill skill, Task task, SseEmitter emitter, ChatClient client,
                              WorkingMemory memory, Sandbox sandbox, ToolContext ctx,
                              List<LlmMessage> history, List<Map<String, Object>> transcript,
                              com.example.harness.core.stream.StreamHub.Hub hub) {
        boolean conversational = task.conversationId() != null && !task.conversationId().isBlank();
        boolean askApproval = "ask".equalsIgnoreCase(task.approval()) && conversational;
        boolean stopped = false;
        boolean anyFailed = false;
        try {
            // 0. 技能标识事件（持久化，历史回放可见）
            Map<String, Object> skillEvent = new HashMap<>();
            skillEvent.put("type", "skill");
            skillEvent.put("id", skill.id());
            skillEvent.put("name", skill.name());
            send(emitter, skillEvent, transcript, hub);

            // 1. 填参
            Map<String, String> params = parameterFiller.fill(skill, task.prompt(),
                    llmCallTracker.tracked(client, ctx.runContext(), "parameter_filler", ctx.agentId()));
            log.info("[{}] 技能 [{}] 填参结果: {}", task.id(), skill.name(), params);

            // 2. 生成计划事件
            List<PlanStep> planSteps = new ArrayList<>();
            for (SkillStep step : skill.steps()) {
                planSteps.add(new PlanStep(step.index(), step.rawLine(), step.tool(), "技能步骤"));
            }
            memory.setPlan(planSteps);
            send(emitter, Map.of("type", "plan", "steps", planSteps), transcript, hub);

            // 3. 顺序执行
            for (SkillStep step : skill.steps()) {
                if (hub != null && hub.cancelFlag.get()) {
                    stopped = true;
                    break;
                }
                Map<String, Object> filledArgs = fillArgs(step.args(), params);

                // 审批闸门：ask 模式的写操作，或 require-approval 开启时的外部工具（mcp__*）
                String callId = skill.id() + "-" + step.index();
                if (needsApproval(step.tool(), askApproval, conversational)) {
                    // 先注册后广播：审批事件先到客户端时，秒回的 POST 会在注册前落空（409 竞态）
                    approvalGate.register(task.conversationId(), callId);
                    Map<String, Object> req = new HashMap<>();
                    req.put("type", "approval_request");
                    req.put("callId", callId);
                    req.put("name", step.tool());
                    req.put("path", String.valueOf(filledArgs.getOrDefault("path", "")));
                    req.put("summary", "技能 " + skill.name() + " 第 " + step.index() + " 步");
                    send(emitter, req, transcript, hub);
                    log.info("[{}] 技能步骤等待审批: {}（call {}）", task.id(), step.tool(), callId);
                    if (ctx.runContext() != null) taskRunService.beginWait(ctx.runContext());
                    boolean approved;
                    try {
                        approved = approvalGate.await(task.conversationId(), callId, APPROVAL_TIMEOUT_MS);
                    } finally {
                        if (ctx.runContext() != null) taskRunService.endWait(ctx.runContext());
                    }
                    if (!approved) {
                        anyFailed = true;
                        send(emitter, Map.of("type", "reflect",
                                "reason", "用户拒绝了技能步骤 " + step.index() + "（" + step.tool()
                                        + "），已跳过。"), transcript, hub);
                        send(emitter, Map.of("type", "tool_call", "callId", callId,
                                "name", step.tool(), "args", filledArgs,
                                "result", "用户拒绝了此操作", "ok", false), transcript, hub);
                        toolRegistry.recordRejected(callId, step.tool(), filledArgs, ctx, "用户拒绝了此操作");
                        continue;
                    }
                }

                ToolResult result = toolRegistry.execute(callId, step.tool(), filledArgs, ctx);
                memory.addStep(new ToolCall(step.tool(), filledArgs,
                        truncate(result.output(), 2000), result.ok()));

                Map<String, Object> event = new HashMap<>();
                event.put("type", "tool_call");
                event.put("callId", callId);
                event.put("name", step.tool());
                event.put("args", filledArgs);
                event.put("result", truncate(result.output(), 4000));
                event.put("ok", result.ok());
                send(emitter, event, transcript, hub);

                if (result.ok()) {
                    if (result.file() != null) {
                        memory.addFile(result.file());
                        send(emitter, Map.of("type", "file", "path", result.file()), transcript, hub);
                    }
                } else {
                    anyFailed = true;
                    send(emitter, Map.of("type", "reflect",
                            "reason", "技能步骤 " + step.index() + "（" + step.tool() + "）失败："
                                    + truncate(result.output(), 300) + "。继续执行下一步。"), transcript, hub);
                }
            }

            String conclusion = stopped ? "（已停止）"
                    : "已按技能 " + skill.name() + " 执行 " + skill.steps().size() + " 步"
                    + (memory.files().isEmpty() ? "。" : "，生成文件：" + String.join(", ", memory.files()) + "。");
            long traceId = episodicStore.save(Trace.of(task.prompt(),
                    om.writeValueAsString(memory.steps()), conclusion));
            log.info("[{}] 技能执行完成: {}", task.id(), truncate(conclusion, 100));
            if (anyFailed && !stopped) {
                skillStore.incrementFail(skill.id());
            }
            if (stopped) return ExecutionResult.stopped(conclusion, traceId, "SKILL");
            return new ExecutionResult(anyFailed ? RunStatus.PARTIAL : RunStatus.SUCCESS,
                    conclusion, traceId, "SKILL", null, null, false);
        } catch (Exception e) {
            log.error("[{}] 技能执行异常", task.id(), e);
            skillStore.incrementFail(skill.id());
            String conclusion = "技能执行异常：" + e.getMessage();
            Long traceId = null;
            try {
                traceId = episodicStore.save(Trace.of(task.prompt(),
                        om.writeValueAsString(memory.steps()), "ERROR: " + e.getMessage()));
            } catch (Exception ignore) {
                log.warn("异常轨迹写入失败: {}", ignore.getMessage());
            }
            return ExecutionResult.failed(conclusion, traceId, "SKILL", "SKILL_ERROR", e.getMessage());
        }
    }

    /** 审批闸门判定：ask 模式的写操作，或 require-approval 开启时（会话内）的外部工具。 */
    private boolean needsApproval(String toolName, boolean askApproval, boolean conversational) {
        if (askApproval && ToolRegistry.MUTATING_TOOLS.contains(toolName)) return true;
        return props.getMcp().isRequireApproval()
                && toolName != null && toolName.startsWith("mcp__")
                && conversational;
    }

    private Map<String, Object> fillArgs(Map<String, String> args, Map<String, String> params) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, String> e : args.entrySet()) {
            String value = e.getValue();
            for (Map.Entry<String, String> p : params.entrySet()) {
                value = value.replace("{{" + p.getKey() + "}}", p.getValue());
            }
            out.put(e.getKey(), value);
        }
        return out;
    }

    private void send(SseEmitter emitter, Map<String, Object> event,
                      List<Map<String, Object>> transcript,
                      com.example.harness.core.stream.StreamHub.Hub hub) {
        transcript.add(event);
        if (hub != null) hub.record(event);
        try {
            emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }
}
