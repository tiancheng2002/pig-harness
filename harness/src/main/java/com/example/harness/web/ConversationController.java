package com.example.harness.web;

import com.example.harness.core.TaskDispatcher;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.memory.ProjectStore;
import com.example.harness.model.Conversation;
import com.example.harness.model.ConversationSummary;
import com.example.harness.model.StoredMessage;
import com.example.harness.model.Task;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话管理 API（M1 补充文档 §3.1）：
 * 新建 / 列表 / 元信息 / 消息读取 / 会话内发送（SSE 续聊）/ 重命名 / 删除。
 */
@RestController
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    public record MessageRequest(String prompt, String root, String modelId, Boolean deepThink,
                                 String approval, String skillId, java.util.List<String> images,
                                 java.util.List<String> filePaths, String planText) {
    }

    public record RenameRequest(String title) {
    }

    public record ApprovalRequest(String callId, Boolean approve) {
    }

    /** 澄清提问应答体（计划模式）：answer 为用户所选选项文本或自由输入。 */
    public record QuestionAnswerRequest(String callId, String answer) {
    }

    private final TaskDispatcher taskDispatcher;
    private final ConversationStore store;
    private final ProjectStore projectStore;
    private final com.example.harness.core.ApprovalGate approvalGate;
    private final com.example.harness.core.question.QuestionGate questionGate;
    private final com.example.harness.core.context.ContextService contextService;
    private final com.example.harness.core.ModelRegistry modelRegistry;
    private final com.example.harness.core.stream.StreamHub streamHub;
    private final com.example.harness.core.memory.UsageLogStore usageLogStore;
    private final ObjectMapper om;

    public ConversationController(TaskDispatcher taskDispatcher, ConversationStore store,
                                  ProjectStore projectStore,
                                  com.example.harness.core.ApprovalGate approvalGate,
                                  com.example.harness.core.question.QuestionGate questionGate,
                                  com.example.harness.core.context.ContextService contextService,
                                  com.example.harness.core.ModelRegistry modelRegistry,
                                  com.example.harness.core.stream.StreamHub streamHub,
                                  com.example.harness.core.memory.UsageLogStore usageLogStore,
                                  ObjectMapper om) {
        this.taskDispatcher = taskDispatcher;
        this.store = store;
        this.projectStore = projectStore;
        this.approvalGate = approvalGate;
        this.questionGate = questionGate;
        this.contextService = contextService;
        this.modelRegistry = modelRegistry;
        this.streamHub = streamHub;
        this.usageLogStore = usageLogStore;
        this.om = om;
    }

    public record CreateRequest(String projectId) {
    }

    /** 新建会话；body 可选 {projectId} 绑定项目（项目会话的沙箱根=项目文件夹）。 */
    @PostMapping("/api/conversations")
    public Conversation create(@RequestBody(required = false) CreateRequest req) {
        String projectId = req == null ? null : req.projectId();
        if (projectId != null && projectStore.get(projectId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目不存在: " + projectId);
        }
        Conversation c = store.create(projectId);
        log.info("[conv:{}] 新建会话{}", c.id(), projectId == null ? "" : "（项目 " + projectId + "）");
        return c;
    }

    @GetMapping("/api/conversations")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConversationSummary s : store.list()) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", s.id());
            item.put("title", s.title());
            item.put("updatedAt", s.updatedAt());
            item.put("messageCount", s.messageCount());
            item.put("projectId", s.projectId());
            item.put("projectName", s.projectName());
            // 运行中标志（体验升级七）：侧栏转圈、进入会话自动接实时流
            item.put("running", streamHub.isRunning(s.id()));
            out.add(item);
        }
        return out;
    }

    @GetMapping("/api/conversations/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return store.get(id).map(c -> {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", c.id());
            item.put("title", c.title());
            item.put("createdAt", c.createdAt());
            item.put("updatedAt", c.updatedAt());
            item.put("projectId", c.projectId());
            // 运行中标志：前端「编辑重发」进入编辑态前轮询此字段等服务端收尾
            item.put("running", streamHub.isRunning(id));
            if (c.projectId() != null) {
                projectStore.get(c.projectId()).ifPresent(p -> {
                    item.put("projectName", p.name());
                    item.put("rootPath", p.rootPath());
                });
            }
            return ResponseEntity.ok(item);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/conversations/{id}/messages")
    public ResponseEntity<List<Map<String, Object>>> messages(@PathVariable String id) {
        if (!store.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (StoredMessage m : store.messages(id)) {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("id", m.id());
            item.put("role", m.role());
            item.put("content", m.content() == null ? "" : m.content());
            item.put("events", parseEvents(m.events()));
            item.put("traceId", m.traceId());
            item.put("taskRunId", m.taskRunId());
            item.put("createdAt", m.createdAt() == null ? "" : m.createdAt());
            out.add(item);
        }
        return ResponseEntity.ok(out);
    }

    /** 在会话内发送消息：SSE 流（续聊，上下文含完整历史）。images 为可选图片 data URL 列表（≤4 张，单张 ≤4MB）。 */
    @PostMapping(value = "/api/conversations/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable String id, @RequestBody MessageRequest req) {
        java.util.List<String> images = validateImages(req.images());
        String promptText = req.prompt() == null ? "" : req.prompt().trim();
        if (promptText.isEmpty() && !images.isEmpty()) {
            promptText = "（用户发送了图片，请结合图片内容回应）";
        }
        if (promptText.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt 不能为空");
        }
        if (!store.exists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + id);
        }
        // 并发保护：同一会话同时只允许一个运行中任务（前端输入框在运行时已禁用）
        if (streamHub.isRunning(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话有任务正在执行，请先停止或等待完成");
        }
        SseEmitter emitter = new com.example.harness.core.stream.SafeSseEmitter(600_000L);
        // 项目会话：沙箱根切换到项目绑定的本地文件夹（root 来自数据库，客户端不可指定）
        String root = req.root();
        Conversation conv = store.get(id).orElse(null);
        if (conv != null && conv.projectId() != null) {
            root = projectStore.get(conv.projectId())
                    .map(p -> p.rootPath())
                    .orElse(root);
        }
        String taskId = UUID.randomUUID().toString();
        String approval = normalizeApproval(req.approval());
        String skillId = req.skillId() == null || req.skillId().isBlank() ? null : req.skillId().trim();
        promptText = withFileRefs(promptText, req.filePaths());
        Task task = new Task(taskId, promptText, root, id, LocalDateTime.now(),
                req.modelId(), Boolean.TRUE.equals(req.deepThink()), approval, skillId, images,
                req.planText() == null || req.planText().isBlank() ? null : req.planText());
        log.info("[{}] 会话消息: {}（conv:{}，root:{}，权限:{}{}{}）", taskId, task.prompt(), id,
                task.root(), approval, skillId == null ? "" : "，技能:" + skillId,
                images.isEmpty() ? "" : "，图片:" + images.size());
        // 事件枢纽：任务期间缓冲全部事件（含 delta），刷新/换标签页经 GET /live 重放续流；
        // 断开连接不再取消任务，停止走 POST /stop
        var hub = streamHub.begin(id);
        Thread.ofVirtual().name("agent-" + taskId).start(() -> {
            try {
                taskDispatcher.run(task, emitter, hub);
            } finally {
                streamHub.end(id);
            }
        });
        return emitter;
    }

    /**
     * 接入会话实时流（体验升级七）：任务运行中 → 先重放缓冲事件再继续实时推送，结束帧后关闭；
     * 无运行任务 → 发一帧 hub_idle 后关闭（前端据此回落到历史回放）。
     * 任何页面/标签页均可随时接入，与发起 POST 的客户端互不影响。
     */
    @GetMapping(value = "/api/conversations/{id}/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live(@PathVariable String id) {
        SseEmitter emitter = new com.example.harness.core.stream.SafeSseEmitter(600_000L);
        var hub = streamHub.get(id);
        if (hub == null) {
            Thread.ofVirtual().start(() -> {
                try {
                    emitter.send(SseEmitter.event().data(
                            Map.of("type", com.example.harness.core.stream.StreamHub.TYPE_IDLE),
                            MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (Exception ignore) {
                    // 客户端已断开
                }
            });
            return emitter;
        }
        // 原子地取快照并注册订阅者（与 record 的 buffer 锁互斥，保证重放与实时之间不漏事件）
        List<Map<String, Object>> snapshot;
        synchronized (hub.buffer) {
            snapshot = List.copyOf(hub.buffer);
            hub.subscribers.add(emitter);
        }
        Thread.ofVirtual().start(() -> {
            try {
                for (Map<String, Object> ev : snapshot) {
                    emitter.send(SseEmitter.event().data(ev, MediaType.APPLICATION_JSON));
                }
                if (!hub.running) {
                    emitter.complete();
                }
            } catch (Exception e) {
                hub.subscribers.remove(emitter);
            }
        });
        return emitter;
    }

    /** 显式停止会话当前任务：置取消标志，各执行循环尽快收尾（结论「（已停止）」照常落库）。 */
    @PostMapping("/api/conversations/{id}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String id) {
        boolean ok = streamHub.cancel(id);
        // 排水：挂起中的审批按「拒绝」、挂起中的提问按「未答复」收尾，任务立即进入停止收尾
        approvalGate.cancelAll(id);
        questionGate.cancelAll(id);
        return ResponseEntity.ok(Map.of("ok", ok));
    }

    /** 澄清提问应答（计划模式）：回传用户所选选项或自由输入，任务继续执行。 */
    @PostMapping("/api/conversations/{id}/question")
    public ResponseEntity<Map<String, Object>> answer(@PathVariable String id,
                                                      @RequestBody QuestionAnswerRequest req) {
        String callId = req.callId() == null ? "" : req.callId().trim();
        if (callId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "callId 不能为空");
        }
        String answer = req.answer() == null ? "" : req.answer().trim();
        if (answer.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer 不能为空");
        }
        boolean ok = questionGate.resolve(id, callId, answer);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "没有等待中的提问（可能已超时或任务已结束）"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 图片 data URL 校验：最多 4 张，必须 data:image/* 前缀，单张 ≤6,000,000 字符（约 4.5MB）。 */
    private static java.util.List<String> validateImages(java.util.List<String> images) {
        if (images == null || images.isEmpty()) {
            return java.util.List.of();
        }
        if (images.size() > 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单条消息最多附带 4 张图片");
        }
        for (String img : images) {
            if (img == null || !img.startsWith("data:image/")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片必须是 data:image/* 格式");
            }
            if (img.length() > 6_000_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单张图片过大（需小于约 4MB）");
            }
        }
        return images;
    }

    /** 上下文容量状态（前端徽标数据源）：估算下一次调用的基础上下文占模型上下文窗口的比例。 */
    /** P1 成本观测：会话累计用量（轮数/token/成本，按模型分组；计价表缺价时 cost=0 只算 token）。 */
    @GetMapping("/api/conversations/{id}/usage")
    public Map<String, Object> usage(@PathVariable String id) {
        if (store.get(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return usageLogStore.aggregate(id);
    }

    @GetMapping("/api/conversations/{id}/context")
    public ResponseEntity<Map<String, Object>> context(
            @PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(name = "modelId", required = false)
            String modelId) {
        if (!store.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        com.example.harness.core.context.ContextService.Status s =
                contextService.status(id, modelId);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("modelId", s.modelId());
        out.put("contextLength", s.contextLength());
        out.put("usedTokens", s.usedTokens());
        out.put("percent", s.percent());
        out.put("state", s.state());
        out.put("summarized", s.summarized());
        return ResponseEntity.ok(out);
    }

    /** 手动触发对话级压缩（LLM 摘要旧轮次），返回压缩前后估算 token。 */
    @PostMapping("/api/conversations/{id}/compact")
    public ResponseEntity<Map<String, Object>> compact(@PathVariable String id) {
        if (!store.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        var r = contextService.compactNow(id, null, modelRegistry.defaultClient());
        if (r == null) {
            return ResponseEntity.ok(Map.of("ok", false,
                    "reason", "没有可压缩的历史（逐字窗口内消息不足）"));
        }
        return ResponseEntity.ok(Map.of("ok", true,
                "before", r.before(), "after", r.after()));
    }

    /** 审批接口：完成 AgentLoop 挂起的写操作确认（请求审批模式）。 */
    @PostMapping("/api/conversations/{id}/approval")    public ResponseEntity<Map<String, Object>> approval(@PathVariable String id,
                                                        @RequestBody ApprovalRequest req) {
        String callId = req.callId() == null ? "" : req.callId().trim();
        if (callId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "callId 不能为空");
        }
        boolean ok = approvalGate.resolve(id, callId, Boolean.TRUE.equals(req.approve()));
        if (!ok) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("ok", false, "error", "没有等待中的审批请求（可能已超时）"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String normalizeApproval(String approval) {
        if (approval == null) return "auto";
        return switch (approval.trim().toLowerCase()) {
            case "ask" -> "ask";
            case "full" -> "full";
            case "plan" -> "plan";
            default -> "auto";
        };
    }

    /** 兼容旧客户端单独传入的 filePaths；新输入框已经把引用留在正文原位置。 */
    private static String withFileRefs(String prompt, List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return prompt;
        }
        java.util.LinkedHashSet<String> refs = new java.util.LinkedHashSet<>();
        for (String fp : filePaths) {
            if (refs.size() >= 10) break;
            String p = fp == null ? "" : fp.trim().replace('\\', '/');
            if (!p.isEmpty()) {
                refs.add(p);
            }
        }
        if (refs.isEmpty()) {
            return prompt;
        }
        StringBuilder sb = new StringBuilder(prompt);
        for (String r : refs) {
            if (r.contains("{") || r.contains("}") || r.contains("\n") || r.contains("\r")) continue;
            if (!prompt.contains("@{" + r + "}")) {
                sb.append("\n@{").append(r).append('}');
            }
        }
        return sb.toString();
    }

    @PatchMapping("/api/conversations/{id}")
    public ResponseEntity<Map<String, Object>> rename(@PathVariable String id,
                                                      @RequestBody RenameRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title 不能为空");
        }
        if (!store.rename(id, req.title())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/api/conversations/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        if (!store.delete(id)) {
            return ResponseEntity.notFound().build();
        }
        log.info("[conv:{}] 会话已删除", id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 从某条消息起截断（消息编辑重发：撤回最后一轮对话含回复，随后前端用编辑后的内容重新发送）。
     * 运行中拒绝（409）——必须先停止并等服务端收尾落库，避免「已停止」消息在删除后落库复活。
     */
    @DeleteMapping("/api/conversations/{id}/messages/{messageId}")
    public ResponseEntity<Map<String, Object>> truncateFrom(@PathVariable String id,
                                                            @PathVariable long messageId) {
        if (!store.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        if (streamHub.isRunning(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话有任务正在执行，请先停止");
        }
        int removed = store.deleteFrom(id, messageId);
        if (removed < 0) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "消息截断失败");
        }
        log.info("[conv:{}] 消息截断：从 {} 起（含）共删除 {} 条", id, messageId, removed);
        return ResponseEntity.ok(Map.of("ok", true, "removed", removed));
    }

    private JsonNode parseEvents(String eventsJson) {
        try {
            return eventsJson == null || eventsJson.isBlank()
                    ? om.createArrayNode() : om.readTree(eventsJson);
        } catch (Exception e) {
            return om.createArrayNode();
        }
    }
}
