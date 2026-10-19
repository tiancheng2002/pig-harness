package com.example.harness.web;

import com.example.harness.core.git.GitService;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.memory.ProjectStore;
import com.example.harness.core.memory.UsageLogStore;
import com.example.harness.model.Conversation;
import com.example.harness.model.GitStatus;
import com.example.harness.model.StoredMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P2-F4 执行报告导出：把一次任务的完整轨迹（任务原文/计划/工具/文件/变更/用量/结论）
 * 渲染成可分享的 Markdown。数据源全部来自落库消息（回放真相源），历史轮次同样可导出。
 */
@RestController
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ConversationStore store;
    private final ProjectStore projectStore;
    private final GitService gitService;
    private final UsageLogStore usageLogStore;
    private final com.example.harness.core.task.TaskRunStore taskRunStore;
    private final ObjectMapper om;

    public ReportController(ConversationStore store, ProjectStore projectStore,
                            GitService gitService, UsageLogStore usageLogStore, ObjectMapper om,
                            com.example.harness.core.task.TaskRunStore taskRunStore) {
        this.store = store;
        this.projectStore = projectStore;
        this.gitService = gitService;
        this.usageLogStore = usageLogStore;
        this.om = om;
        this.taskRunStore = taskRunStore;
    }

    /**
     * GET /api/conversations/{id}/report?messageId=
     * messageId = 轮次的用户消息 id（缺省 = 最后一个已完成轮次）；返回 text/markdown 下载。
     */
    @GetMapping("/api/conversations/{id}/report")
    public ResponseEntity<byte[]> report(@PathVariable String id,
                                         @RequestParam(name = "messageId", required = false) Long messageId) {
        Conversation conv = store.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        List<StoredMessage> messages = store.messages(id);
        List<Turn> turns = buildTurns(messages);
        if (turns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话还没有任何轮次");
        }
        Turn turn;
        if (messageId != null) {
            turn = turns.stream()
                    .filter(t -> t.userId == messageId || t.assistantId != null && t.assistantId == messageId)
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "轮次不存在: " + messageId));
        } else {
            turn = turns.stream().filter(t -> t.assistantId != null).reduce((a, b) -> b).orElse(null);
            if (turn == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有已完成的轮次（最后一轮仍在执行或未产出结论）");
            }
        }
        Map<String,Object> run = taskRunStore.listConversation(id, turn.userId > 0 ? turn.userId : turn.assistantId)
                .stream().findFirst().orElse(null);
        String markdown = run != null && run.get("outcomeJson") instanceof String s && !s.isBlank()
                ? renderOutcome(conv, turn, run, s) : render(conv.id(), conv, turns, turn);
        String filename = "harness-report-" + id + (turn.userId > 0 ? "-m" + turn.userId : "") + ".md";
        log.info("[conv:{}] 导出执行报告（消息 {}）", id, turn.userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(markdown.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 轮次模型（与前端 buildTurns 同构） ----------

    private static final class Turn {
        long userId;
        String userContent = "";
        String userAt = "";
        Long assistantId;
        String conclusion;
        String assistantAt;
        List<Map<String, Object>> events = new ArrayList<>();
    }

    private List<Turn> buildTurns(List<StoredMessage> messages) {
        List<Turn> turns = new ArrayList<>();
        for (StoredMessage m : messages) {
            if ("user".equals(m.role())) {
                Turn t = new Turn();
                t.userId = m.id() == null ? 0 : m.id();
                t.userContent = m.content() == null ? "" : m.content();
                t.userAt = m.createdAt() == null ? "" : m.createdAt();
                turns.add(t);
            } else if ("assistant".equals(m.role())) {
                Turn last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
                if (last != null && last.assistantId == null) {
                    last.assistantId = m.id();
                    last.conclusion = m.content() == null ? "" : m.content();
                    last.assistantAt = m.createdAt() == null ? "" : m.createdAt();
                    last.events = parseEvents(m.events());
                } else {
                    Turn t = new Turn();
                    t.userId = 0;
                    t.assistantId = m.id();
                    t.conclusion = m.content() == null ? "" : m.content();
                    t.assistantAt = m.createdAt() == null ? "" : m.createdAt();
                    t.events = parseEvents(m.events());
                    turns.add(t);
                }
            }
        }
        return turns;
    }

    private List<Map<String, Object>> parseEvents(String eventsJson) {
        try {
            if (eventsJson == null || eventsJson.isBlank()) return List.of();
            JsonNode arr = om.readTree(eventsJson);
            List<Map<String, Object>> out = new ArrayList<>();
            if (arr.isArray()) {
                arr.forEach(n -> out.add(om.convertValue(n, Map.class)));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------- Markdown 渲染 ----------

    private String renderOutcome(Conversation conv, Turn turn, Map<String,Object> run, String outcomeJson) {
        try {
            JsonNode o = om.readTree(outcomeJson);
            StringBuilder sb = new StringBuilder();
            sb.append("# 执行报告：").append(md(truncate(firstLine(turn.userContent), 60))).append("\n\n");
            sb.append("- **Run ID**：`").append(run.get("id")).append("`\n");
            sb.append("- **最终状态**：").append(o.path("status").asText("—")).append("\n");
            Object failureReason=run.get("errorSummary");
            sb.append("- **状态原因**：").append(failureReason==null||String.valueOf(failureReason).isBlank()?statusReason(o):md(String.valueOf(failureReason))).append("\n");
            sb.append("- **模式**：").append(o.path("mode").asText("—")).append("\n");
            sb.append("- **时间**：").append(turn.userAt.isEmpty()?"—":turn.userAt.replace('T',' ')).append("\n\n");
            sb.append("## 判定摘要\n\n").append(o.path("summary").asText("（无摘要）")).append("\n\n");
            sb.append("## 原始需求\n\n").append(turn.userContent.isBlank()?"（无正文）\n":blockQuote(turn.userContent)).append("\n");
            JsonNode plan=o.path("plan");
            sb.append("\n## 计划完成情况\n\n- 总计：").append(plan.path("total").asInt())
                    .append("，完成：").append(plan.path("completed").asInt()).append("，失败：")
                    .append(plan.path("failed").asInt()).append("\n");
            sb.append("\n## 已知文件变更\n\n");
            if(o.path("changedFiles").isEmpty()) sb.append("（没有已知文件变更）\n");
            else for(JsonNode f:o.path("changedFiles")) sb.append("- `").append(f.path("path").asText()).append("` · ")
                    .append(f.path("type").asText()).append(" +").append(f.path("additions").asInt())
                    .append(" −").append(f.path("deletions").asInt()).append("\n");
            if(!o.path("changeSummaryComplete").asBoolean(false)) sb.append("\n> 文件归因不完整：Shell/MCP 直接修改可能未被完整统计。\n");
            sb.append("\n- **变更完整性**：").append(o.path("changeSummaryComplete").asBoolean(false)?"完整":"不完整").append("\n");
            sb.append("\n## 验证结果\n\n");
            if(o.path("verifications").isEmpty()) sb.append("（未配置验证）\n");
            else for(JsonNode v:o.path("verifications")) sb.append("- ").append("PASSED".equals(v.path("status").asText())?"✅ ":"❌ ")
                    .append(md(v.path("name").asText())).append("（").append(v.path("durationMs").asLong()).append(" ms）\n");
            appendArraySection(sb,"子 Agent","failedAgents",o);
            appendArraySection(sb,"未完成事项","unresolved",o);
            appendArraySection(sb,"风险","risks",o);
            JsonNode m=o.path("metrics");
            sb.append("\n## 任务指标\n\n- 墙钟耗时：").append(m.path("wallDurationMs").asLong()).append(" ms\n")
                    .append("- 首 Token：").append(m.path("firstTokenMs").isNumber()?m.path("firstTokenMs").asLong()+" ms":"—").append("\n")
                    .append("- 审批等待：").append(m.path("approvalWaitMs").asLong()).append(" ms\n")
                    .append("- 模型调用：").append(m.path("llmCalls").asInt()).append("，工具调用：").append(m.path("toolCalls").asInt()).append("\n")
                    .append("- Token：prompt ").append(m.path("promptTokens").asLong()).append(" / completion ").append(m.path("completionTokens").asLong());
            if(m.path("estimatedCalls").asInt()>0)sb.append("（含 ").append(m.path("estimatedCalls").asInt()).append(" 次估算）");
            sb.append("\n- 成本：").append(m.path("costUsd").isNumber()?"$"+String.format(java.util.Locale.ROOT,"%.4f",m.path("costUsd").asDouble()):"未配置").append("\n");
            sb.append("\n## 最终结论\n\n").append(turn.conclusion==null||turn.conclusion.isBlank()?o.path("summary").asText():turn.conclusion.trim()).append("\n");
            sb.append("\n---\n\n*报告生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" ｜ 由 Pig Harness 自动导出*\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("TaskOutcome 报告解析失败，回退旧报告: {}",e.getMessage());
            return render(conv.id(),conv,List.of(turn),turn);
        }
    }

    private String statusReason(JsonNode outcome){return switch(outcome.path("status").asText("FAILED")){case "SUCCESS"->"正常完成";case "PARTIAL"->"存在未完成计划、验证失败、关键子 Agent 失败或步数耗尽";case "STOPPED"->"用户停止";case "INTERRUPTED"->"服务重启恢复时发现未完成运行";default->"执行失败或没有生成有效结论";};}
    private void appendArraySection(StringBuilder sb,String title,String field,JsonNode outcome){JsonNode a=outcome.path(field);sb.append("\n## ").append(title).append("\n\n");if(a.isEmpty()){sb.append("（无）\n");return;}for(JsonNode n:a){if("failedAgents".equals(field)&&n.isObject())sb.append("- ").append(md(n.path("agentId").asText("unknown"))).append(" · ").append(md(n.path("status").asText())).append("：").append(md(n.path("summary").asText())).append("\n");else sb.append("- ").append(md(n.isTextual()?n.asText():n.toString())).append("\n");}}

    private String render(String conversationId, Conversation conv, List<Turn> turns, Turn turn) {
        StringBuilder sb = new StringBuilder();
        String title = firstLine(turn.userContent);
        sb.append("# 执行报告：").append(md(title.isEmpty() ? "（无正文消息）" : truncate(title, 60))).append("\n\n");

        // 概览：会话 / 模式 / 模型 / 耗时
        sb.append("- **会话**：").append(md(conv.title())).append("（`").append(conv.id()).append("`）\n");
        String mode = detectMode(turn.events);
        sb.append("- **模式**：").append(mode).append("\n");
        String model = turnModel(conversationId, turns, turn);
        sb.append("- **模型**：").append(model).append("\n");
        String dur = durationOf(turn);
        if (!dur.isEmpty()) {
            sb.append("- **耗时**：").append(dur).append("\n");
        }
        sb.append("- **时间**：").append(turn.userAt.isEmpty() ? "—" : turn.userAt.replace('T', ' ')).append("\n\n");

        // 任务原文
        sb.append("## 任务原文\n\n");
        if (turn.userContent.isBlank()) {
            sb.append("（该轮没有用户正文）\n");
        } else {
            sb.append(blockQuote(turn.userContent)).append("\n");
        }

        // 计划（plan + plan_step 状态）
        List<Map<String, Object>> plan = eventsOf(turn.events, "plan")
                .stream().filter(e -> e.get("steps") instanceof List<?>)
                .findFirst().map(e -> (List<Map<String, Object>>) e.get("steps")).orElse(List.of());
        if (!plan.isEmpty()) {
            Map<Integer, String> stepStatus = new LinkedHashMap<>();
            for (Map<String, Object> ev : eventsOf(turn.events, "plan_step")) {
                Object idx = ev.get("index");
                if (idx instanceof Number n) {
                    stepStatus.put(n.intValue(), String.valueOf(ev.getOrDefault("status", "done")));
                }
            }
            sb.append("\n## 执行计划（").append(plan.size()).append(" 步）\n\n");
            for (Map<String, Object> step : plan) {
                int idx = step.get("index") instanceof Number n ? n.intValue() : 0;
                String status = stepStatus.getOrDefault(idx, "pending");
                String mark = switch (status) {
                    case "done" -> "✅";
                    case "failed" -> "❌";
                    default -> "⬜";
                };
                sb.append(mark).append(" ").append(md(String.valueOf(step.getOrDefault("step", "")))).append("\n");
            }
        }

        // 工具调用表
        List<Map<String, Object>> toolCalls = eventsOf(turn.events, "tool_call");
        if (!toolCalls.isEmpty()) {
            long failed = toolCalls.stream().filter(t -> !Boolean.TRUE.equals(t.get("ok"))).count();
            sb.append("\n## 工具调用（").append(toolCalls.size()).append(" 次")
                    .append(failed > 0 ? "，" + failed + " 次失败" : "").append("）\n\n");
            sb.append("| # | 工具 | 目标 | 结果 |\n|---|------|------|------|\n");
            int i = 1;
            for (Map<String, Object> t : toolCalls) {
                boolean ok = Boolean.TRUE.equals(t.get("ok"));
                sb.append("| ").append(i++).append(" | ").append(md(str(t.get("name"))))
                        .append(" | ").append(md(truncate(toolTarget(t), 80)))
                        .append(" | ").append(ok ? "✅" : "❌").append(" |\n");
            }
        }

        // 产出文件
        List<Map<String, Object>> files = eventsOf(turn.events, "file");
        if (!files.isEmpty()) {
            sb.append("\n## 产出文件（").append(files.size()).append(" 个）\n\n");
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> f : files) {
                String path = str(f.get("path"));
                if (path.isEmpty() || !seen.add(path)) continue;
                Object size = f.get("size");
                sb.append("- `").append(path).append("`");
                if (size instanceof Number n && n.longValue() >= 0) {
                    sb.append("（").append(humanSize(n.longValue())).append("）");
                }
                sb.append("\n");
            }
        }

        // 验证钩子（P1）
        List<Map<String, Object>> hooks = eventsOf(turn.events, "hook");
        if (!hooks.isEmpty()) {
            sb.append("\n## 交付前验证\n\n");
            for (Map<String, Object> h : hooks) {
                boolean ok = Boolean.TRUE.equals(h.get("ok"));
                sb.append("- ").append(ok ? "✅" : "❌").append(" ")
                        .append(md(str(h.getOrDefault("name", "verify"))));
                Object ms = h.get("durationMs");
                if (ms instanceof Number n) sb.append("（").append(n.longValue()).append(" ms）");
                sb.append("\n");
            }
        }

        // 子 Agent（M4 扇出 / spawnAgent）
        List<Map<String, Object>> spawns = eventsOf(turn.events, "agent_spawn");
        if (!spawns.isEmpty()) {
            sb.append("\n## 子智能体\n\n");
            Map<String, Map<String, Object>> dones = new LinkedHashMap<>();
            for (Map<String, Object> d : eventsOf(turn.events, "agent_done")) {
                dones.put(str(d.get("agent")), d);
            }
            for (Map<String, Object> s : spawns) {
                String agent = str(s.get("agent"));
                sb.append("- **").append(md(agent)).append("**：").append(md(truncate(str(s.get("task")), 100))).append("\n");
                Map<String, Object> done = dones.get(agent);
                if (done != null) {
                    sb.append("  - 状态：").append(md(str(done.getOrDefault("status", "?"))));
                    String summary = str(done.get("summary"));
                    if (!summary.isEmpty()) sb.append("，").append(md(truncate(summary, 160)));
                    sb.append("\n");
                }
            }
        }

        // 变更摘要（仅项目会话：事件触碰文件 ∩ 当前 git status）
        String diffSection = diffSummary(conv, turn);
        if (!diffSection.isEmpty()) {
            sb.append("\n## 变更摘要\n\n").append(diffSection).append("\n");
        }

        // 最终结论
        sb.append("\n## 最终结论\n\n");
        sb.append(turn.conclusion == null || turn.conclusion.isBlank()
                ? "（该轮没有结论）\n" : turn.conclusion.trim()).append("\n");

        // 用量（usage_log 时间窗归属，有数据才带）
        String usage = usageSection(conversationId, turns, turn);
        if (!usage.isEmpty()) {
            sb.append("\n## LLM 用量\n\n").append(usage).append("\n");
        }

        sb.append("\n---\n\n*报告生成时间：")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append(" ｜ 由 Pig Harness 自动导出*\n");
        return sb.toString();
    }

    /** 模式判定：plan_mode 事件 > 技能事件 > 有 plan 事件=任务执行 > 其余=对话直答。 */
    private static String detectMode(List<Map<String, Object>> events) {
        boolean planMode = events.stream().anyMatch(e -> "plan_mode".equals(e.get("type")));
        if (planMode) return "计划模式（只读调研 → 计划文档）";
        List<Map<String, Object>> skills = eventsOf(events, "skill");
        if (!skills.isEmpty()) {
            return "技能执行（/" + str(skills.get(0).getOrDefault("name", skills.get(0).get("id"))) + "）";
        }
        boolean hasPlan = events.stream().anyMatch(e -> "plan".equals(e.get("type")));
        return hasPlan ? "任务执行（规划 → 工具循环）" : "对话直答（CHAT）";
    }

    /** 轮次模型：usage_log 按时间窗归属（报告回放时 model 档案不可考，以流水为准）。 */
    private String turnModel(String conversationId, List<Turn> turns, Turn turn) {
        List<Map<String, Object>> rows = usageRowsInWindow(conversationId, turns, turn);
        Set<String> models = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            models.add(str(r.get("model")));
        }
        if (models.isEmpty()) {
            return "—（无用量记录）";
        }
        return String.join("、", models);
    }

    private String usageSection(String conversationId, List<Turn> turns, Turn turn) {
        List<Map<String, Object>> rows = usageRowsInWindow(conversationId, turns, turn);
        if (rows.isEmpty()) return "";
        long p = 0, c = 0;
        int estimated = 0;
        for (Map<String, Object> r : rows) {
            p += ((Number) r.getOrDefault("promptTokens", 0)).longValue();
            c += ((Number) r.getOrDefault("completionTokens", 0)).longValue();
            if (Boolean.TRUE.equals(r.get("estimated"))) estimated++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- LLM 调用 ").append(rows.size()).append(" 轮：prompt ").append(p)
                .append(" tokens / completion ").append(c).append(" tokens\n");
        if (estimated > 0) {
            sb.append("- 其中 ").append(estimated).append(" 轮为估算值（端点未返回真实 usage）\n");
        }
        return sb.toString();
    }

    /** 把 usage_log 行按时间窗归属到轮次：[本轮用户消息时间, 下一轮用户消息时间)。 */
    private List<Map<String, Object>> usageRowsInWindow(String conversationId, List<Turn> turns, Turn turn) {
        List<Map<String, Object>> all = usageLogStore.listRows(conversationId);
        String start = turn.userAt;
        String end = null;
        boolean found = false;
        for (Turn t : turns) {
            if (t == turn) {
                found = true;
                continue;
            }
            if (found && t.userAt != null && !t.userAt.isBlank()) {
                end = t.userAt;
                break;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : all) {
            String at = str(r.get("createdAt"));
            if (!start.isBlank() && at.compareTo(start) < 0) continue;
            if (end != null && at.compareTo(end) >= 0) continue;
            out.add(r);
        }
        return out;
    }

    /** 项目会话：本轮触碰文件与当前 git status 求交集，给出 ±行数（对齐前端 TurnDiffBar 口径）。 */
    private String diffSummary(Conversation conv, Turn turn) {
        if (conv.projectId() == null) return "";
        String rootPath = projectStore.get(conv.projectId()).map(p -> p.rootPath()).orElse(null);
        if (rootPath == null) return "";
        Set<String> touched = new LinkedHashSet<>();
        for (Map<String, Object> f : eventsOf(turn.events, "file")) {
            String p = str(f.get("path"));
            if (!p.isEmpty()) touched.add(p.replace('\\', '/'));
        }
        Set<String> toolNames = Set.of("fileWrite", "fileEdit", "fileDelete", "docxWrite");
        for (Map<String, Object> t : eventsOf(turn.events, "tool_call")) {
            if (toolNames.contains(str(t.get("name")))) {
                Map<String, Object> args = t.get("args") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : Map.of();
                String p = str(args.get("path"));
                if (!p.isEmpty()) touched.add(p.replace('\\', '/'));
            }
        }
        if (touched.isEmpty()) return "";
        GitStatus st = gitService.status(java.nio.file.Path.of(rootPath));
        if (!st.repo()) {
            return "*（项目当前不是 Git 仓库，无法统计行级变更；触碰文件见「产出文件」清单）*";
        }
        Map<String, GitStatus.Change> byPath = new LinkedHashMap<>();
        for (GitStatus.Change ch : st.changes()) {
            byPath.put(ch.path(), ch);
        }
        List<String> lines = new ArrayList<>();
        long add = 0, del = 0;
        int hits = 0;
        for (String p : touched) {
            GitStatus.Change ch = byPath.get(p);
            if (ch == null) continue; // 已提交/未变化
            hits++;
            add += ch.additions();
            del += ch.deletions();
            lines.add("- `" + p + "`（" + ch.label() + "，+" + ch.additions() + " −" + ch.deletions() + "）");
        }
        if (hits == 0) {
            return "*本轮触碰的 " + touched.size() + " 个文件当前无未提交变更（可能已提交或与状态一致）*";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(hits).append(" 个文件有未提交变更（+").append(add).append(" −").append(del).append("）：\n\n");
        for (String l : lines) sb.append(l).append("\n");
        sb.append("\n> 以导出时的 `git status` 为准；此前已提交的变更不在统计内。");
        return sb.toString();
    }

    // ---------- 小工具 ----------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> eventsOf(List<Map<String, Object>> events, String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            if (type.equals(e.get("type"))) out.add(e);
        }
        return out;
    }

    /** 与前端 toolTargetOf 同口径：path/dir/command/url/query/keywords。 */
    private static String toolTarget(Map<String, Object> t) {
        Map<String, Object> args = t.get("args") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        for (String k : new String[]{"path", "dir", "command", "url", "query", "keywords"}) {
            Object v = args.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v).replaceAll("\\s+", " ");
            }
        }
        return "";
    }

    private static String durationOf(Turn turn) {
        try {
            if (turn.userAt.isBlank() || turn.assistantAt == null || turn.assistantAt.isBlank()) return "";
            LocalDateTime s = LocalDateTime.parse(turn.userAt);
            LocalDateTime e = LocalDateTime.parse(turn.assistantAt);
            long sec = Math.max(0, Duration.between(s, e).getSeconds());
            return sec >= 60 ? (sec / 60) + " 分 " + (sec % 60) + " 秒" : sec + " 秒";
        } catch (Exception ex) {
            return "";
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\n")) {
            if (!line.isBlank()) return line.trim();
        }
        return "";
    }

    private static String blockQuote(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.trim().split("\n", -1)) {
            sb.append("> ").append(line).append("\n");
        }
        return sb.toString();
    }

    /** 表格/正文内联安全：去换行、转义竖线。 */
    private static String md(String s) {
        return s == null ? "" : s.replace("\r", "").replace("\n", " ").replace("|", "\\|");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String humanSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / 1024.0 / 1024.0);
    }
}
