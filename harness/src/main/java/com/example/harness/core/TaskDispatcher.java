package com.example.harness.core;

import com.example.harness.config.HarnessProperties;
import com.example.harness.config.RootResolver;
import com.example.harness.core.knowledge.KnowledgeExtractor;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.memory.KnowledgeStore;
import com.example.harness.core.memory.WorkingMemory;
import com.example.harness.core.multi.DecomposePlan;
import com.example.harness.core.multi.Orchestrator;
import com.example.harness.core.task.ExecutionResult;
import com.example.harness.core.task.LlmCallTracker;
import com.example.harness.core.task.RunStatus;
import com.example.harness.core.task.TaskFinalizer;
import com.example.harness.core.task.TaskOutcome;
import com.example.harness.core.task.TaskRunContext;
import com.example.harness.core.task.TaskRunService;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.LlmMessage;
import com.example.harness.model.Knowledge;
import com.example.harness.model.Task;
import com.example.harness.router.IntentRouter;
import com.example.harness.sandbox.Sandbox;
import com.example.harness.skill.Skill;
import com.example.harness.skill.SkillLibrary;
import com.example.harness.tools.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 任务统一入口：创建共享上下文 → 会话模式路由（CHAT 直答 / TASK）→ 命中技能/AgentLoop。
 * 按请求解析模型（modelId + deepThink）；结束后异步沉淀长期记忆（知识）。
 */
@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final ChatModeRouter chatModeRouter;
    private final ChatExecutor chatExecutor;
    private final IntentRouter intentRouter;
    private final SkillExecutor skillExecutor;
    private final SkillLibrary skillLibrary;
    private final AgentLoop agentLoop;
    private final Planner planner;
    private final Orchestrator orchestrator;
    private final ToolRegistry toolRegistry;
    private final ConversationStore conversationStore;
    private final RootResolver rootResolver;
    private final HarnessProperties props;
    private final ModelRegistry modelRegistry;
    private final KnowledgeStore knowledgeStore;
    private final KnowledgeExtractor knowledgeExtractor;
    private final com.example.harness.llm.VisionService visionService;
    private final com.example.harness.core.context.ContextService contextService;
    private final com.example.harness.core.evolution.EvolutionPipeline evolutionPipeline;
    private final TaskRunService taskRunService;
    private final TaskFinalizer taskFinalizer;
    private final LlmCallTracker llmCallTracker;

    public TaskDispatcher(ChatModeRouter chatModeRouter,
                          ChatExecutor chatExecutor,
                          IntentRouter intentRouter,
                          SkillExecutor skillExecutor,
                          SkillLibrary skillLibrary,
                          AgentLoop agentLoop,
                          Planner planner,
                          Orchestrator orchestrator,
                          ToolRegistry toolRegistry,
                          ConversationStore conversationStore,
                          RootResolver rootResolver,
                          HarnessProperties props,
                          ModelRegistry modelRegistry,
                          KnowledgeStore knowledgeStore,
                          KnowledgeExtractor knowledgeExtractor,
                          com.example.harness.llm.VisionService visionService,
                          com.example.harness.core.context.ContextService contextService,
                          com.example.harness.core.evolution.EvolutionPipeline evolutionPipeline,
                          TaskRunService taskRunService, TaskFinalizer taskFinalizer,
                          LlmCallTracker llmCallTracker) {
        this.chatModeRouter = chatModeRouter;
        this.chatExecutor = chatExecutor;
        this.intentRouter = intentRouter;
        this.skillExecutor = skillExecutor;
        this.skillLibrary = skillLibrary;
        this.agentLoop = agentLoop;
        this.planner = planner;
        this.orchestrator = orchestrator;
        this.toolRegistry = toolRegistry;
        this.conversationStore = conversationStore;
        this.rootResolver = rootResolver;
        this.props = props;
        this.modelRegistry = modelRegistry;
        this.knowledgeStore = knowledgeStore;
        this.knowledgeExtractor = knowledgeExtractor;
        this.visionService = visionService;
        this.contextService = contextService;
        this.evolutionPipeline = evolutionPipeline;
        this.taskRunService = taskRunService;
        this.taskFinalizer = taskFinalizer;
        this.llmCallTracker = llmCallTracker;
    }

    public void run(Task task, SseEmitter emitter) {
        run(task, emitter, null);
    }

    /**
     * 会话入口（体验升级七）：hub 非空时所有事件同步进会话事件缓冲（GET /live 重放续流），
     * hub.cancelFlag 为本次任务的取消标志（/stop 显式触发）。
     */
    public void run(Task task, SseEmitter emitter, com.example.harness.core.stream.StreamHub.Hub hub) {
        java.util.concurrent.atomic.AtomicBoolean cancelFlag = hub != null ? hub.cancelFlag : null;
        // 1. 创建本次任务共享的 WorkingMemory / Sandbox / ToolContext（完全访问模式放行沙箱外绝对路径）
        String rootDir = (task.root() == null || task.root().isBlank())
                ? rootResolver.defaultRoot().toString() : task.root();
        Sandbox sandbox = new Sandbox(rootDir);
        WorkingMemory memory = new WorkingMemory(task);
        boolean fullAccess = "full".equalsIgnoreCase(task.approval());
        String projectId = task.conversationId() == null ? null
                : conversationStore.get(task.conversationId()).map(com.example.harness.model.Conversation::projectId).orElse(null);
        TaskRunContext runContext = taskRunService.begin(task.id(), task.conversationId(), projectId,
                sandbox.root(), task.modelId());
        ToolContext ctx = new ToolContext(sandbox, memory, fullAccess, runContext, null);
        memory.putVariable("root", sandbox.root().toString());

        // 2. 按请求解析模型（modelId + 深度思考）；P1 轻模型路由：未显式选模型时 CHAT/技能分支用轻模型
        ChatClient client = modelRegistry.resolve(task.modelId(), task.deepThink());
        ChatClient lightClient = client;
        String lightModelId = props.getModelRouting().getLightModelId();
        if (lightModelId != null && !lightModelId.isBlank()
                && (task.modelId() == null || task.modelId().isBlank())) {
            try {
                ChatClient resolved = modelRegistry.resolve(lightModelId, false);
                if (resolved != client) {
                    lightClient = resolved;
                    log.info("[{}] 轻模型路由：CHAT/技能分支使用 {}", task.id(), lightModelId);
                }
            } catch (Exception e) {
                log.warn("轻模型 {} 解析失败，回退默认模型: {}", lightModelId, e.getMessage());
            }
        }
        List<Knowledge> knowledge = knowledgeStore.forPrompt(task.prompt(), projectId, 5);

        // 3. 会话模式：先快照历史（摘要 + 逐字窗口，不含本条消息），当前消息稍后持久化
        List<LlmMessage> history = List.of();
        // 并发安全容器：多 Agent 扇出时子线程会写入 transcript（单 Agent 路径行为不变）
        List<Map<String, Object>> transcript = Collections.synchronizedList(new ArrayList<>());
        boolean conversational = task.conversationId() != null && !task.conversationId().isBlank();
        if (conversational) {
            history = contextService.buildHistory(task.conversationId());
        }
        String originalPrompt = task.prompt();
        // P0 自进化：本轮是否走了技能复用路径（显式/意图命中）——走了就不再为它合成技能
        boolean usedSkill = false;
        ExecutionResult execution = null;
        TaskOutcome outcome = null;

        // 用户消息在任何模型/工具调用前落库，并与 Run 精确关联。
        if (conversational) {
            long userMessageId = conversationStore.addMessage(task.conversationId(), "user",
                    originalPrompt, null, null, runContext.enabled() ? runContext.runId() : null);
            taskRunService.linkUser(runContext, userMessageId < 0 ? null : userMessageId);
            conversationStore.autoTitle(task.conversationId(), originalPrompt);
        }

        try {
            // @ 文件引用保留在落库用户原文中，只对本轮模型输入原位展开。
            // 路由继续使用原文，避免文件源码影响 CHAT/TASK 分类。
            String routePrompt = task.prompt();
            if (projectId != null && task.prompt().contains("@{")) {
                task = task.withPrompt(FileReferenceExpander.expand(task.prompt(), sandbox.root()));
            }
            // 3.5 图片理解：随消息附带的图片先经视觉模型转述（vision 事件落库可回放），
            //     描述追加到 prompt 供后续链路使用；模式路由仍用原始 prompt，避免描述文本干扰分类
            if (task.images() != null && !task.images().isEmpty()) {
                int limit = Math.max(1, props.getVision().getMaxImages());
                StringBuilder sb = new StringBuilder();
                int n = Math.min(task.images().size(), limit);
                String attachKey = task.conversationId() == null || task.conversationId().isBlank()
                        ? task.id() : task.conversationId();
                for (int i = 0; i < n; i++) {
                    // 原图留存：落盘到默认工作区 attachments/（项目会话也不进项目根，避免污染 git status），
                    // 持久化 image 事件只存相对路径——回放经 /api/file（无会话参数=默认根）取图，文件被删则前端显示空白
                    String savedPath = saveAttachment(task.images().get(i), attachKey, i + 1);
                    if (savedPath != null) {
                        Map<String, Object> iev = Map.of("type", "image", "index", i + 1, "path", savedPath);
                        transcript.add(iev);
                        if (hub != null) hub.record(iev);
                        try {
                            emitter.send(SseEmitter.event().data(iev, MediaType.APPLICATION_JSON));
                        } catch (Exception ignore) {
                            // 客户端可能已断开，继续完成落库
                        }
                    }
                    String desc = visionService.describe(task.images().get(i), routePrompt, runContext);
                    Map<String, Object> ev = Map.of("type", "vision", "index", i + 1, "text", desc);
                    transcript.add(ev);
                    if (hub != null) hub.record(ev);
                    try {
                        emitter.send(SseEmitter.event().data(ev, MediaType.APPLICATION_JSON));
                    } catch (Exception ignore) {
                        // 客户端可能已断开，继续完成落库
                    }
                    sb.append("\n\n[图片 ").append(i + 1).append(" 内容描述]\n").append(desc);
                }
                if (task.images().size() > n) {
                    sb.append("\n\n（另有 ").append(task.images().size() - n)
                            .append(" 张图片超过单条消息上限，未处理）");
                }
                task = task.withPrompt(task.prompt() + sb);
                log.info("[{}] 图片理解完成（{} 张）", task.id(), n);
            }

            // 3.6 对话级上下文压缩（软阈值自动触发）：估算基础上下文超限时，先 LLM 摘要旧轮次
            //     并推进水位线，再进入后续链路；压缩事件落库可回放（context_compact）
            if (conversational) {
                com.example.harness.core.context.ConversationCompactor.CompactResult cr =
                        contextService.maybeCompact(task.conversationId(), task.modelId(),
                                llmCallTracker.tracked(client, runContext, "context_compactor", null));
                if (cr != null) {
                    Map<String, Object> ev = Map.of("type", "context_compact",
                            "before", cr.before(), "after", cr.after(), "scope", "conversation");
                    transcript.add(ev);
                    if (hub != null) hub.record(ev);
                    try {
                        emitter.send(SseEmitter.event().data(ev, MediaType.APPLICATION_JSON));
                    } catch (Exception ignore) {
                        // 客户端可能已断开
                    }
                    history = contextService.buildHistory(task.conversationId());
                    log.info("[{}] 对话上下文压缩完成：{}→{} tokens", task.id(), cr.before(), cr.after());
                }
            }

            // 3.8 计划实施（「执行此计划」）：计划文档随 planText 下发，注入执行提示词；
            // 落库的用户消息保持原文（3.7 已落），不把计划全文回显到对话气泡
            boolean planImplement = task.planText() != null && !task.planText().isBlank();
            if (planImplement) {
                task = task.withPrompt("请严格按照以下计划文档实施（计划已与我确认，直接开始执行，不要再输出计划）：\n\n"
                        + task.planText());
                log.info("[{}] 计划实施：已注入计划文档（{} 字符）", task.id(), task.planText().length());
            }

            // 4.5 计划模式（approval=plan）：强制任务路径——只读调研 + askUser 澄清 + 计划文档结论；
            // 跳过技能命中与多 Agent 拆分（技能含写步骤、拆分面向执行，都与只读承诺冲突），模式路由也不适用
            if ("plan".equalsIgnoreCase(task.approval()) && !planImplement) {
                Map<String, Object> modeEv = Map.of("type", "plan_mode");
                transcript.add(modeEv);
                if (hub != null) hub.record(modeEv);
                try {
                    emitter.send(SseEmitter.event().data(modeEv, MediaType.APPLICATION_JSON));
                } catch (Exception ignore) {
                    // 客户端可能已断开（事件已入 hub 缓冲，/live 可重放）
                }
                log.info("[{}] 计划模式：只读调研并产出计划文档", task.id());
                taskRunService.mode(runContext, "PLAN");
                execution = agentLoop.execute(task, emitter, client, knowledge, memory, sandbox,
                        ctx, history, transcript, null, hub).withMode("PLAN");
                return;
            }

            // 4. 显式技能调用（输入框 / 选择）：跳过模式路由与意图检索，直接执行该技能
            if (task.skillId() != null && !task.skillId().isBlank()) {
                Optional<Skill> picked = skillLibrary.get(task.skillId());
                if (picked.isEmpty()) {
                    log.warn("[{}] 显式技能不存在或已失效: {}", task.id(), task.skillId());
                    String conclusion = "技能不存在或已被删除（" + task.skillId() + "），请检查技能库。";
                    taskRunService.mode(runContext, "SKILL");
                    execution = ExecutionResult.failed(conclusion, null, "SKILL",
                            "SKILL_NOT_FOUND", conclusion);
                    return;
                }
                log.info("[{}] 显式执行技能: {}", task.id(), picked.get().name());
                usedSkill = true;
                taskRunService.mode(runContext, "SKILL");
                execution = skillExecutor.execute(picked.get(), task, emitter, lightClient, knowledge,
                        memory, sandbox, ctx, history, transcript, hub);
                return;
            }

            // 4.6 计划实施轮：跳过模式路由（「执行此计划」不该被当成闲聊）、技能命中与多 Agent 拆分
            //（计划文档本身就是步骤清单，按计划顺序执行最可预期），直接进入 AgentLoop
            if (planImplement) {
                log.info("[{}] 模式=TASK（计划实施），进入 AgentLoop", task.id());
                taskRunService.mode(runContext, "TASK");
                execution = agentLoop.execute(task, emitter, client, knowledge, memory, sandbox,
                        ctx, history, transcript, null, hub);
                return;
            }

            // 5. 会话模式路由（带上下文）：闲聊/问答直接流式回答，不进规划与循环
            ChatModeRouter.Mode mode = chatModeRouter.route(routePrompt, history,
                    llmCallTracker.tracked(lightClient, runContext, "chat_router", null));
            if (mode == ChatModeRouter.Mode.CHAT) {
                log.info("[{}] 模式=CHAT，直接回答", task.id());
                taskRunService.mode(runContext, "CHAT");
                execution = chatExecutor.execute(task, emitter,
                        llmCallTracker.tracked(lightClient, runContext, "chat", null),
                        knowledge, history, transcript, hub);
                return;
            }

            // 6. 任务模式：意图路由 → 命中技能 / 多 Agent 编排 / AgentLoop
            Optional<Skill> hit = intentRouter.route(task.prompt());
            if (hit.isPresent()) {
                log.info("[{}] 命中技能: {}", task.id(), hit.get().name());
                usedSkill = true;
                taskRunService.mode(runContext, "SKILL");
                execution = skillExecutor.execute(hit.get(), task, emitter, lightClient, knowledge,
                        memory, sandbox, ctx, history, transcript, hub);
            } else if (props.getMultiAgent().isEnabled() && props.getMultiAgent().isAutoDecompose()) {
                // M4：先拆分——通过触发门槛（≥2 个可并行子任务）才扇出，否则与单 Agent 现状一致；
                // auto-decompose=false 时跳过自动拆分（spawnAgent 按需派子智能体不受影响）
                DecomposePlan dp = planner.decompose(task.prompt(), toolRegistry.names(),
                        llmCallTracker.tracked(client, runContext, "decomposer", null));
                if (dp.parallel()) {
                    log.info("[{}] 多 Agent 扇出（{} 个子任务）", task.id(), dp.subtasks().size());
                    taskRunService.mode(runContext, "MULTI_AGENT");
                    execution = orchestrator.execute(task, emitter, client, knowledge, sandbox,
                            history, transcript, dp.subtasks(), runContext, hub);
                } else {
                    log.info("[{}] 模式=TASK，未命中技能，进入 AgentLoop（多 Agent 降级: {}）",
                            task.id(), dp.reason());
                    taskRunService.mode(runContext, "TASK");
                    execution = agentLoop.execute(task, emitter, client, knowledge, memory, sandbox,
                            ctx, history, transcript, null, hub);
                }
            } else {
                log.info("[{}] 模式=TASK，未命中技能，进入 AgentLoop", task.id());
                taskRunService.mode(runContext, "TASK");
                execution = agentLoop.execute(task, emitter, client, knowledge, memory, sandbox, ctx, history, transcript, null, hub);
            }
        } catch (Exception e) {
            log.error("[{}] TaskDispatcher 执行异常", task.id(), e);
            execution = ExecutionResult.failed("任务执行异常：" + e.getMessage(), null,
                    "TASK", "DISPATCH_ERROR", e.getMessage());
        } finally {
            if (execution == null) {
                execution = new ExecutionResult(hub != null && hub.cancelFlag.get()
                        ? RunStatus.STOPPED : RunStatus.FAILED,
                        hub != null && hub.cancelFlag.get() ? "（已停止）" : "任务未生成可用结论。",
                        null, "TASK", "NO_RESULT", "执行路径未返回结果", false);
            }
            outcome = taskFinalizer.finish(runContext, execution, emitter, transcript, hub);
            // 6. 自进化：异步沉淀长期记忆（仅会话模式，Mock 下自动跳过）
            if (conversational) {
                knowledgeExtractor.extractAsync(task.conversationId());
            }
            // 7. 自进化（P0）：成功任务聚类计数 → 达阈值自动合成技能草稿并校验（异步，异常不影响对话）
            try {
                evolutionPipeline.onTaskFinished(task, usedSkill, outcome);
            } catch (Exception e) {
                log.warn("进化流水线触发失败（忽略）: {}", e.getMessage());
            }
        }
    }

    /**
     * 把图片 data URL 落盘到默认工作区 attachments/{会话}/ 下，返回相对默认根的正斜杠路径；
     * 失败返回 null（不影响视觉转述）。文件名服务端生成（时间戳+UUID），路径不含用户输入。
     */
    private String saveAttachment(String dataUrl, String attachKey, int index) {
        try {
            int comma = dataUrl.indexOf(',');
            if (!dataUrl.startsWith("data:image/") || comma < 0) return null;
            String mime = dataUrl.substring(11, dataUrl.indexOf(';'));
            String ext = switch (mime) {
                case "png" -> "png";
                case "jpeg", "jpg" -> "jpg";
                case "gif" -> "gif";
                case "webp" -> "webp";
                case "bmp" -> "bmp";
                case "svg+xml" -> "svg";
                default -> "png";
            };
            byte[] bytes = Base64.getMimeDecoder().decode(dataUrl.substring(comma + 1));
            if (bytes.length == 0) return null;
            Path dir = rootResolver.defaultRoot().resolve("attachments").resolve(attachKey);
            Files.createDirectories(dir);
            String name = System.currentTimeMillis() + "-" + index + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
            Files.write(dir.resolve(name), bytes);
            return "attachments/" + attachKey + "/" + name;
        } catch (Exception e) {
            log.warn("图片留存失败（忽略，视觉转述不受影响）: {}", e.getMessage());
            return null;
        }
    }

    /** 供 ChatExecutor 等构造「已知用户背景」提示段。 */
    public static String knowledgePrompt(List<Knowledge> knowledge) {
        if (knowledge == null || knowledge.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("已确认的用户背景（仅供参考，以当前用户请求和项目文件为准，不要主动罗列）：\n");
        for (Knowledge k : knowledge) {
            String content = k.content() != null && k.content().length() > 120
                    ? k.content().substring(0, 120) + "…" : k.content();
            sb.append("- [").append(k.category()).append("] ").append(k.key())
                    .append("：").append(content).append('\n');
        }
        return sb.toString();
    }
}
