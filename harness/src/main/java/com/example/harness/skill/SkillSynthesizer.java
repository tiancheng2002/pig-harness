package com.example.harness.skill;

import com.example.harness.core.ModelRegistry;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.ChatResult;
import com.example.harness.llm.LlmMessage;
import com.example.harness.model.StoredMessage;
import com.example.harness.model.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能合成器：把一次对话的执行轨迹（结合前几轮聊天上下文）抽象成可复用的草稿技能。
 * 参考 Anthropic skill-creator 的最佳实践：description 说清「做什么 + 什么时候用」，
 * triggers 取用户真实会打出的短语，name 用英文小写连字符。
 * LLM 不可用（Mock/失败）时降级为基于任务文本的确定性生成。
 */
@Component
public class SkillSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(SkillSynthesizer.class);
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-zA-Z0-9-]+");

    private final ModelRegistry modelRegistry;
    private final ObjectMapper om;
    private final com.example.harness.core.task.LlmCallTracker llmCallTracker;

    public SkillSynthesizer(ModelRegistry modelRegistry, ObjectMapper om,
                            com.example.harness.core.task.LlmCallTracker llmCallTracker) {
        this.modelRegistry = modelRegistry;
        this.om = om;
        this.llmCallTracker = llmCallTracker;
    }

    /** 生成结果：可直接用于渲染 SKILL.md 的元数据。testInput = 「相似但不相同」的校验输入。 */
    public record DraftMeta(String name, String description, List<String> triggers, String whenToUse, String testInput) {
    }

    /**
     * 合成草稿技能元数据。
     *
     * @param trace      本次任务的执行轨迹
     * @param recentMsgs 该会话最近若干条消息（含本轮之前几轮对话，供理解任务背景）
     */
    public DraftMeta synthesize(Trace trace, List<StoredMessage> recentMsgs) {
        String task = trace.task() == null ? "" : trace.task().trim();
        try {
            ChatClient client = llmCallTracker.tracked(modelRegistry.resolve(null, false),
                    null, "skill_synthesizer", null);
            List<LlmMessage> msgs = new ArrayList<>();
            msgs.add(LlmMessage.system("""
                    [ROLE=SKILL_WRITER] 你是技能工程师，负责把一次成功的任务执行轨迹抽象成可复用的技能（SKILL.md）元数据。
                    最佳实践：description 要让没看过执行轨迹的人一眼明白这个技能做什么、适用于什么场景；
                    triggers 是用户真实会打出的短语（不含斜杠，3~6 个，每个不超过 12 个字）；
                    name 用英文小写连字符（如 login-page-generator），不超过 32 个字符；
                    test_input 给出一个「相似但不相同」的用户请求（如原任务是登录页就给注册页），用于后续验证技能可泛化。
                    仅输出一个 JSON 对象，不要 markdown 代码块、不要解释：
                    {"name":"英文小写连字符名","description":"一句话中文描述（做什么+适用场景，40字以内）","triggers":["短语1","短语2"],"when_to_use":"什么用户输入应路由到此技能（一句话，20字以内）","test_input":"相似但不相同的用户请求"}
                    """));
            msgs.add(LlmMessage.user(buildContext(trace, recentMsgs)));
            ChatResult r = client.chat(msgs, List.of());
            String content = r.content() == null ? "" : r.content().trim();
            Matcher m = JSON_OBJECT.matcher(content);
            if (!m.find()) {
                log.warn("技能合成：LLM 输出不含 JSON，降级确定性生成");
                return fallbackMeta(task);
            }
            JsonNode node = om.readTree(m.group());
            String name = slug(node.path("name").asText(""));
            DraftMeta meta = new DraftMeta(
                    name.isBlank() ? fallbackName(task) : name,
                    cut(clean(node.path("description").asText("")), 80),
                    readTriggers(node),
                    cut(clean(node.path("when_to_use").asText("")), 60),
                    cut(clean(node.path("test_input").asText("")), 120));
            if (meta.description().isBlank()) {
                return fallbackMeta(task);
            }
            return meta;
        } catch (Exception e) {
            log.warn("技能合成 LLM 调用失败，降级确定性生成: {}", e.getMessage());
            return fallbackMeta(task);
        }
    }

    /** 组装 LLM 上下文：本轮任务 + 最近几轮对话 + 实际执行步骤。 */
    private String buildContext(Trace trace, List<StoredMessage> recentMsgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 本次任务（用户最后一条指令）==\n")
               .append(trace.task() == null ? "" : trace.task()).append('\n');
        if (recentMsgs != null && !recentMsgs.isEmpty()) {
            sb.append("\n== 相关对话上下文（越靠后越新）==\n");
            for (StoredMessage msg : recentMsgs) {
                String c = msg.content() == null ? "" : msg.content().replace("\n", " ");
                if (c.isBlank()) continue;
                sb.append(msg.role().equals("user") ? "用户: " : "助手: ")
                        .append(c, 0, Math.min(c.length(), 300)).append('\n');
            }
        }
        sb.append("\n== 实际执行的工具步骤 ==\n").append(stepsSummary(trace));
        sb.append("\n\n请据此生成技能元数据 JSON。");
        return sb.toString();
    }

    private String stepsSummary(Trace trace) {
        StringBuilder sb = new StringBuilder();
        try {
            List<?> steps = om.readValue(trace.steps() == null ? "[]" : trace.steps(), List.class);
            int i = 1;
            for (Object obj : steps) {
                if (obj instanceof java.util.Map<?, ?> m) {
                    sb.append(i++).append(". ").append(m.get("name")).append("(args=")
                            .append(m.get("args")).append(")\n");
                }
                if (i > 12) {
                    sb.append("...（共 ").append(steps.size()).append(" 步）\n");
                    break;
                }
            }
        } catch (Exception e) {
            sb.append("（步骤解析失败）\n");
        }
        if (sb.isEmpty()) sb.append("（无工具调用）\n");
        return sb.toString();
    }

    private List<String> readTriggers(JsonNode node) {
        List<String> out = new ArrayList<>();
        JsonNode arr = node.path("triggers");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                String v = clean(t.asText("")).replace("\"", "");
                if (!v.isBlank() && v.length() <= 24) out.add(v);
                if (out.size() >= 6) break;
            }
        }
        return out;
    }

    /** LLM 不可用时的兜底：直接用任务文本组织描述（不再出现「轨迹 #N」这类用户看不懂的表述）。 */
    private DraftMeta fallbackMeta(String task) {
        String shortTask = cut(task.replace('\n', ' ').trim(), 40);
        if (shortTask.isBlank()) shortTask = "自动化任务";
        return new DraftMeta(
                fallbackName(task),
                "把「" + shortTask + "」这类请求按验证过的步骤自动完成",
                List.of(cut(shortTask, 12)),
                "用户要求执行与「" + shortTask + "」类似的任务时",
                "再完成一次类似任务（换一个主题或名称）：" + shortTask);
    }

    private String fallbackName(String task) {
        String slug = slug(task.toLowerCase()
                .replace("创建", "create-").replace("生成", "generate-")
                .replace("修改", "edit-").replace("做", "make-"));
        if (slug.isBlank()) {
            return "draft-skill-" + System.currentTimeMillis() % 10000;
        }
        return slug.length() > 40 ? slug.substring(0, 40) : slug;
    }

    private String slug(String s) {
        return NON_SLUG.matcher(s.replaceAll("\\s+", "-")).replaceAll("")
                .replaceAll("-{2,}", "-").replaceAll("^-|-$", "").toLowerCase();
    }

    private String clean(String s) {
        return s == null ? "" : s.replace("\n", " ").replace("\"", "'").trim();
    }

    private String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 把合成元数据 + 执行轨迹渲染为完整 SKILL.md（frontmatter enabled:false 的草稿形态）。
     * 手动「存为技能」（SkillController.fromTrace）与自动合成流水线（EvolutionPipeline）共用此渲染。
     */
    public String buildSkillMarkdown(String name, DraftMeta meta, Trace trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(escape(name)).append("\n");
        sb.append("description: ").append(escape(meta.description())).append("\n");
        sb.append("triggers:\n");
        List<String> triggers = meta.triggers() == null || meta.triggers().isEmpty()
                ? List.of(escape(trace.task())) : meta.triggers();
        for (String t : triggers) {
            sb.append("  - \"").append(escape(t)).append("\"\n");
        }
        sb.append("when_to_use: ").append(escape(meta.whenToUse())).append("\n");
        sb.append("enabled: false\n");
        sb.append("version: 1\n");
        sb.append("---\n");
        sb.append("由对话轨迹自动合成的草稿技能：执行前建议先人工核对步骤与参数。\n");
        // 步骤体：尝试把 steps JSON 中的 toolCall 反序列后写出
        List<?> steps;
        try {
            steps = om.readValue(trace.steps() == null ? "[]" : trace.steps(), List.class);
        } catch (Exception e) {
            steps = List.of();
        }
        int idx = 1;
        for (Object obj : steps) {
            if (obj instanceof Map<?, ?> m) {
                Object nameObj = m.get("name");
                String tool = nameObj == null ? "unknown" : String.valueOf(nameObj);
                Object argsObj = m.get("args");
                String args = argsObj instanceof Map<?, ?> am ? formatArgs((Map<?, ?>) am) : "";
                sb.append(idx++).append(". ").append(tool).append("(").append(args).append(")\n");
            }
        }
        if (idx == 1) {
            sb.append("1. fileWrite(path=\"output.md\", content=\"").append(escape(trace.task())).append("\")\n");
        }
        return sb.toString();
    }

    private String formatArgs(Map<?, ?> args) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<?, ?> e : args.entrySet()) {
            String key = String.valueOf(e.getKey());
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            parts.add(key + "=\"" + escape(value) + "\"");
        }
        return String.join(", ", parts);
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
