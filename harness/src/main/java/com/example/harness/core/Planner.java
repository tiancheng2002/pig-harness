package com.example.harness.core;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.multi.DecomposePlan;
import com.example.harness.core.multi.SubAgentConfig;
import com.example.harness.core.multi.SubTask;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.ChatResult;
import com.example.harness.llm.LlmMessage;
import com.example.harness.model.PlanStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规划器（F4）：LLM 将自然语言任务拆成有序步骤（使用本次请求选定的模型）。
 * 步骤中的工具名校验：不在 ToolRegistry 中的置空并标注。
 * M4：decompose 把任务拆为子任务 DAG；任何异常/不满足触发门槛都降级 single（绝不抛错）。
 */
@Component
public class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private final ObjectMapper om = new ObjectMapper();
    private final int maxFanout;

    public Planner(HarnessProperties props) {
        this.maxFanout = Math.max(2, props.getMultiAgent().getMaxFanout());
    }

    public List<PlanStep> plan(String task, Set<String> toolNames, ChatClient client) {
        return plan(task, toolNames, client, null);
    }

    public List<PlanStep> plan(String task, Set<String> toolNames, ChatClient client,
                               WorkspaceSnapshotService.Snapshot workspace) {
        String sys = "[ROLE=PLANNER] 你是任务规划器。将用户任务拆解为 2-4 个有序步骤。"
                + "仅输出 JSON 数组（不要任何其他文本、不要代码块标记），每个元素形如："
                + "{\"step\":\"步骤描述\",\"tool\":\"建议工具名或 null\",\"reason\":\"原因\"}。"
                + "可用工具：" + String.join(", ", toolNames) + "。\n"
                + "必须依据 WORKSPACE_SNAPSHOT 规划，不得凭用户症状虚构仓库中不存在的前端、框架或文件。"
                + "代码修复默认遵循 定位→最小修改→验证；若请求与仓库事实可能错配，"
                + "先安排最少量代码调研，再用 askUser 澄清，不得擅自改修另一个缺陷。"
                + "计划追求高信息密度而不是工具次数：先搜索定位，再批量读取最相关文件，"
                + "验证只安排一次；不要安排读取测试报告来重复确认已经成功的退出码，"
                + "也不要默认启动服务做端口冒烟。只覆盖用户明确提出的问题，不要自行扩大范围。";
        try {
            ChatResult r = client.chat(
                    List.of(LlmMessage.system(sys + (workspace == null ? "" : "\n" + workspace.toPrompt())),
                            LlmMessage.user(task)), List.of());
            return parseSteps(r.content(), toolNames);
        } catch (Exception e) {
            log.warn("规划失败，降级为单步执行: {}", e.getMessage());
            return List.of(new PlanStep(1, "直接执行任务（规划降级）", null, "规划失败 fallback"));
        }
    }

    private List<PlanStep> parseSteps(String content, Set<String> toolNames) {
        List<PlanStep> steps = new ArrayList<>();
        try {
            String json = extractJsonArray(content);
            JsonNode arr = om.readTree(json);
            int index = 1;
            for (JsonNode n : arr) {
                String step = n.path("step").asText(n.path("description").asText(""));
                String tool = n.path("tool").asText(null);
                String reason = n.path("reason").asText("");
                if (step.isBlank()) continue;
                if (tool != null && (tool.isBlank() || "null".equalsIgnoreCase(tool))) tool = null;
                if (tool != null && !toolNames.contains(tool)) {
                    reason = (reason.isBlank() ? "" : reason + "；") + "建议的工具未注册，已标记";
                    tool = null;
                }
                steps.add(new PlanStep(index++, step, tool, reason));
                if (steps.size() >= 10) break;
            }
        } catch (Exception e) {
            log.warn("计划解析失败: {}", e.getMessage());
        }
        if (steps.isEmpty()) {
            steps.add(new PlanStep(1, "直接执行任务", null, "解析降级"));
        }
        return steps;
    }

    /**
     * 多 Agent 拆分（M4-F2）：一次 LLM 调用输出子任务 DAG。
     * 降级规则（任一命中即 parallel=false，调用方走单 Agent 现状路径）：
     * 解析失败 / 有效子任务截断后 <2 / 依赖成环 / 输出路径跨子任务重叠 / 纯依赖链（最大波宽 <2）。
     */
    public DecomposePlan decompose(String task, Set<String> toolNames, ChatClient client) {
        String sys = "[ROLE=DECOMPOSER] 你是多 Agent 任务拆分器。判断用户任务能否拆成可并行的独立子任务。"
                + "仅输出一个 JSON 对象（不要任何其他文本、不要代码块标记），形如：\n"
                + "{\"mode\":\"parallel|single\",\"subtasks\":[{\"id\":\"s1\",\"description\":\"...\","
                + "\"depends_on\":[],\"output_paths\":[],\"suggested_tools\":[]}]}\n"
                + "要求：\n"
                + "1) 仅当任务包含 ≥2 个「各自需要多步加工、可独立交付」的实质性工作时才用 parallel；"
                + "顺序任务、单一任务、拆不动的一律 {\"mode\":\"single\",\"subtasks\":[]}；\n"
                + "2) description 必须自包含（子 Agent 看不到其它上下文），并写明要产出哪个文件；\n"
                + "3) output_paths 为相对路径，各子任务之间绝不能重叠；依赖用 depends_on 引用其它子任务 id；\n"
                + "4) 子任务最多 " + maxFanout + " 个；建议的工具从可用工具中选："
                + String.join(", ", toolNames) + "；\n"
                + "5) 判据：扇出有真实收益才 parallel——每个子任务都值得一个独立 Agent（各自要多轮工具调用）才拆；"
                + "内容简单的小文件由主 Agent 顺序写完更快更省，用 single。"
                + "「存在任一波次能同时执行 ≥2 个子任务」只是必要条件不是充分条件；\n"
                + "正例：做登录页 + API 文档 + 单元测试 → 3 个 parallel 子任务，"
                + "输出分别为 login.html / docs/api.md / tests/test-plan.md。\n"
                + "正例2：基于 source-note.md 分别撰写两份侧重点不同的深度分析报告 → parallel："
                + "s1 读源文件提炼要点；s2、s3 各自 depends_on [s1]，并行成文两份报告（内容实质性、各自多轮加工）。\n"
                + "反例1：生成两个文件 a-demo.md 和 b-demo.md，各写 3 行文件用途说明 → single"
                + "（内容简单的几个小文件，主 Agent 顺序完成即可，扇出纯属浪费）；\n"
                + "反例2：先读 a.txt，再据此生成 b.md → single（只有一个后续产出，纯顺序链）。";
        try {
            ChatResult r = client.chat(
                    List.of(LlmMessage.system(sys), LlmMessage.user(task)), List.of());
            DecomposePlan plan = validate(r.content());
            log.info("拆分结果: parallel={}，{}（{}）", plan.parallel(),
                    plan.subtasks().size(), plan.reason());
            return plan;
        } catch (Exception e) {
            log.warn("拆分调用失败，降级单 Agent: {}", e.getMessage());
            return DecomposePlan.single("拆分调用失败");
        }
    }

    /** Schema 校验 + 触发门槛判定；任何不满足都返回 single，绝不抛错。 */
    private DecomposePlan validate(String content) {
        if (content == null || content.isBlank()) {
            return DecomposePlan.single("拆分输出为空");
        }
        try {
            String json = extractJsonObject(content);
            JsonNode root = om.readTree(json);
            if (!"parallel".equalsIgnoreCase(root.path("mode").asText())) {
                return DecomposePlan.single("模型判定为顺序任务");
            }
            Map<String, SubTask> byId = new LinkedHashMap<>();
            for (JsonNode n : root.path("subtasks")) {
                String desc = n.path("description").asText("").trim();
                if (desc.isBlank()) continue;
                String id = n.path("id").asText("").trim();
                if (id.isBlank() || byId.containsKey(id)) {
                    id = "s" + (byId.size() + 1);
                }
                List<String> deps = new ArrayList<>();
                for (JsonNode d : n.path("depends_on")) {
                    String dep = d.asText("").trim();
                    if (!dep.isBlank() && !dep.equals(id) && !deps.contains(dep)) {
                        deps.add(dep);
                    }
                }
                List<String> outs = new ArrayList<>();
                for (JsonNode p : n.path("output_paths")) {
                    String out = p.asText("").trim();
                    if (!out.isBlank() && !outs.contains(out)) {
                        outs.add(out);
                    }
                }
                List<String> tools = new ArrayList<>();
                for (JsonNode t : n.path("suggested_tools")) {
                    String tool = t.asText("").trim();
                    if (!tool.isBlank() && !tools.contains(tool)) {
                        tools.add(tool);
                    }
                }
                byId.put(id, new SubTask(id, desc, deps, outs, tools));
                if (byId.size() >= 100) break; // 防御性上限
            }
            // 丢弃引用不存在子任务的依赖（截断同样会丢失被引者）
            Set<String> ids = byId.keySet();
            Map<String, SubTask> fixed = new LinkedHashMap<>();
            byId.forEach((id, st) -> fixed.put(id, new SubTask(st.id(), st.description(),
                    st.dependsOn().stream().filter(ids::contains).toList(),
                    st.outputPaths(), st.suggestedTools())));
            List<SubTask> subtasks = new ArrayList<>(fixed.values());
            // 成本护栏：超过 maxFanout 直接截断
            if (subtasks.size() > maxFanout) {
                subtasks = new ArrayList<>(subtasks.subList(0, maxFanout));
                Set<String> kept = subtasks.stream().map(SubTask::id)
                        .collect(java.util.stream.Collectors.toSet());
                subtasks = subtasks.stream()
                        .map(st -> new SubTask(st.id(), st.description(),
                                st.dependsOn().stream().filter(kept::contains).toList(),
                                st.outputPaths(), st.suggestedTools()))
                        .toList();
            }
            if (subtasks.size() < 2) {
                return DecomposePlan.single("有效子任务不足 2 个");
            }
            if (hasCycle(subtasks)) {
                return DecomposePlan.single("依赖成环");
            }
            if (overlappingOutputs(subtasks)) {
                return DecomposePlan.single("子任务输出路径重叠");
            }
            if (maxWaveWidth(subtasks) < 2) {
                return DecomposePlan.single("纯依赖链，无并行收益");
            }
            return new DecomposePlan(true, subtasks, "拆分通过校验");
        } catch (Exception e) {
            log.warn("拆分解析失败: {}", e.getMessage());
            return DecomposePlan.single("拆分解析失败");
        }
    }

    /** Kahn 成环检测（依赖表已先过滤为已知 id）。 */
    private boolean hasCycle(List<SubTask> tasks) {
        Map<String, Integer> indeg = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (SubTask t : tasks) {
            indeg.putIfAbsent(t.id(), 0);
            for (String d : t.dependsOn()) {
                adj.computeIfAbsent(d, k -> new ArrayList<>()).add(t.id());
                indeg.merge(t.id(), 1, Integer::sum);
            }
        }
        List<String> cur = tasks.stream().filter(t -> indeg.get(t.id()) == 0)
                .map(SubTask::id).toList();
        Set<String> seen = new HashSet<>(cur);
        while (!cur.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String id : cur) {
                for (String nb : adj.getOrDefault(id, List.of())) {
                    if (indeg.merge(nb, -1, Integer::sum) == 0) {
                        next.add(nb);
                    }
                }
            }
            seen.addAll(next);
            cur = next;
        }
        return seen.size() < tasks.size();
    }

    /** 最大波宽（Kahn 分层）：宽度 1 = 纯依赖链，不值得并行。 */
    private int maxWaveWidth(List<SubTask> tasks) {
        Map<String, Integer> indeg = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (SubTask t : tasks) {
            indeg.putIfAbsent(t.id(), 0);
            for (String d : t.dependsOn()) {
                adj.computeIfAbsent(d, k -> new ArrayList<>()).add(t.id());
                indeg.merge(t.id(), 1, Integer::sum);
            }
        }
        List<String> cur = tasks.stream().filter(t -> indeg.get(t.id()) == 0)
                .map(SubTask::id).toList();
        int width = cur.size();
        while (!cur.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String id : cur) {
                for (String nb : adj.getOrDefault(id, List.of())) {
                    if (indeg.merge(nb, -1, Integer::sum) == 0) {
                        next.add(nb);
                    }
                }
            }
            width = Math.max(width, next.size());
            cur = next;
        }
        return width;
    }

    /** 计划期冲突预防（M4-F7）：归一化后的输出路径跨子任务重叠即拒绝并行。 */
    private boolean overlappingOutputs(List<SubTask> tasks) {
        Set<String> seen = new HashSet<>();
        for (SubTask t : tasks) {
            for (String p : t.outputPaths()) {
                if (!seen.add(SubAgentConfig.normalize(p))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String extractJsonArray(String content) {
        if (content == null) return "[]";
        String s = content.replace("```json", "").replace("```", "").trim();
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end <= start) return "[]";
        return s.substring(start, end + 1);
    }

    private String extractJsonObject(String content) {
        if (content == null) return "{}";
        String s = content.replace("```json", "").replace("```", "").trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return "{}";
        return s.substring(start, end + 1);
    }
}
