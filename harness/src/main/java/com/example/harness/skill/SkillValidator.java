package com.example.harness.skill;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.ModelRegistry;
import com.example.harness.core.ToolRegistry;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.ChatResult;
import com.example.harness.llm.LlmMessage;
import com.example.harness.llm.LlmToolCall;
import com.example.harness.llm.LlmToolSpec;
import com.example.harness.sandbox.Sandbox;
import com.example.harness.tools.ToolContext;
import com.example.harness.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * P0 自进化：技能草稿隔离沙箱校验。
 * 在 workspace/.validator/draft-{id}/ 临时目录中用「相似但不相同」的 test_input 真正执行草稿，
 * 产出 5 项检查报告；执行后清理临时目录，绝不写用户工作区可见位置。
 * steps 型按步骤经 ToolRegistry 执行；prompt 型用受限工具清单跑一个小型工具循环。
 */
@Component
public class SkillValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillValidator.class);

    /** 校验执行器可用的工具白名单：只读 + 文件产出；bash/fileDelete/mcp 一律不可用。 */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "fileWrite", "fileEdit", "fileRead", "listFiles", "docxWrite");
    private static final Set<String> DANGEROUS_HINTS = Set.of("fileDelete", "bash");
    private static final int PROMPT_MAX_ROUNDS = 4;

    private final ToolRegistry toolRegistry;
    private final ParameterFiller parameterFiller;
    private final ModelRegistry modelRegistry;
    private final HarnessProperties props;
    private final ObjectMapper om;
    private final com.example.harness.core.task.LlmCallTracker llmCallTracker;

    public SkillValidator(ToolRegistry toolRegistry, ParameterFiller parameterFiller,
                          ModelRegistry modelRegistry, HarnessProperties props, ObjectMapper om,
                          com.example.harness.core.task.LlmCallTracker llmCallTracker) {
        this.toolRegistry = toolRegistry;
        this.parameterFiller = parameterFiller;
        this.modelRegistry = modelRegistry;
        this.props = props;
        this.om = om;
        this.llmCallTracker = llmCallTracker;
    }

    /** 校验报告：5 项检查 + 产出文件 + 人读问题清单。 */
    public record ValidationReport(
            boolean executesWithoutError,
            boolean producesExpectedFiles,
            boolean outputStructurallyValid,
            boolean noDangerousOps,
            boolean parameterizedNotHardcoded,
            List<String> producedFiles,
            List<String> issues,
            long durationMs) {

        public boolean allPassed() {
            return executesWithoutError && producesExpectedFiles && outputStructurallyValid
                    && noDangerousOps && parameterizedNotHardcoded;
        }
    }

    /**
     * 校验一份草稿 SKILL.md。
     *
     * @param draftId   草稿 id（决定临时目录名）
     * @param content   SKILL.md 全文
     * @param testInput 「相似但不相同」的校验输入
     */
    public ValidationReport validate(long draftId, String content, String testInput) {
        long start = System.currentTimeMillis();
        List<String> issues = new ArrayList<>();
        Path tempRoot = tempDir(draftId);
        boolean execOk = true;
        boolean noDangerous = true;
        boolean structValid = true;
        List<String> produced = new ArrayList<>();
        try {
            Files.createDirectories(tempRoot);
            Sandbox sandbox = new Sandbox(tempRoot.toString());
            ToolContext ctx = new ToolContext(sandbox, new com.example.harness.core.memory.WorkingMemory(null), false);

            SkillParser.Parsed parsed = SkillParser.parse("draft-" + draftId,
                    tempRoot.resolve("SKILL.md").toString(), content);
            boolean stepsMode = "steps".equalsIgnoreCase(parsed.mode()) && !parsed.steps().isEmpty();
            // 5 项之一：parameterized_not_hardcoded（对全文做绝对路径扫描）
            boolean paramOk = checkNotHardcoded(content, issues);

            if (stepsMode) {
                Skill asSkill = toSkill(parsed, content);
                Map<String, String> params = parameterFiller.fill(asSkill,
                        testInput == null || testInput.isBlank() ? parsed.name() : testInput,
                        llmCallTracker.tracked(modelRegistry.resolve(null, false),
                                null, "parameter_filler", null));
                for (SkillStep step : parsed.steps()) {
                    if (isDangerous(step.tool())) {
                        noDangerous = false;
                        issues.add("步骤 " + step.index() + " 使用了校验不允许的工具: " + step.tool());
                        continue;
                    }
                    Map<String, Object> args = fillArgs(step.args(), params);
                    ToolResult r = toolRegistry.execute(step.tool(), args, ctx);
                    if (!r.ok()) {
                        execOk = false;
                        issues.add("步骤 " + step.index() + "（" + step.tool() + "）执行失败: "
                                + cut(r.output(), 200));
                    } else if (r.file() != null) {
                        produced.add(r.file());
                    }
                }
            } else {
                // prompt 型：受限工具清单 + 小型工具循环（不经过 AgentLoop/审批）
                PromptRun pr = runPromptMode(parsed, content, testInput, ctx, issues, produced);
                execOk = pr.ok();
                if (pr.dangerous()) noDangerous = false;
                if (!pr.ok()) issues.add("提示词技能执行未正常完成");
            }

            // 汇总真实产出的文件（工具回传 + 目录扫描兜底）
            produced.addAll(walkFiles(tempRoot));
            produced = produced.stream().distinct().toList();
            boolean expectsFiles = stepsMode
                    ? parsed.steps().stream().anyMatch(s -> isWriteTool(s.tool()))
                    : true; // prompt 型无法预知产出，按「执行无错即通过」，结构校验管质量
            boolean producesOk = !expectsFiles || !produced.isEmpty();
            if (expectsFiles && produced.isEmpty()) {
                issues.add("预期产出文件但未检测到任何文件");
            }
            structValid = checkStructural(tempRoot, produced, issues);

            return new ValidationReport(execOk, producesOk, structValid, noDangerous, paramOk,
                    produced, issues, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("技能草稿校验异常: {}", e.getMessage());
            issues.add("校验执行异常: " + cut(e.getMessage(), 200));
            return new ValidationReport(false, false, false, noDangerous, false,
                    produced, issues, System.currentTimeMillis() - start);
        } finally {
            cleanup(tempRoot);
        }
    }

    private record PromptRun(boolean ok, boolean dangerous) {
    }

    /** prompt 型小型工具循环：最多 4 轮，工具结果手工回灌。 */
    private PromptRun runPromptMode(SkillParser.Parsed parsed, String content, String testInput,
                                    ToolContext ctx, List<String> issues, List<String> produced) {
        boolean dangerous = false;
        try {
            ChatClient client = llmCallTracker.tracked(modelRegistry.resolve(null, false),
                    null, "skill_validator", null);
            String body = SkillParser.stripFrontmatter(content);
            String instruction = "【技能校验】请严格按照以下技能说明完成任务（这是一次可泛化性验证，用给定的测试输入执行）：\n"
                    + "======== 技能说明（" + parsed.name() + "）========\n"
                    + body + "\n======== 技能说明结束 ========\n"
                    + "测试输入（当作用户请求执行）：" + (testInput == null ? parsed.name() : testInput) + "\n"
                    + "说明中未被替换的 {{参数}} 表示该值尚未确定，请结合输入语境自行决定合理取值。";
            List<LlmMessage> msgs = new ArrayList<>();
            msgs.add(LlmMessage.system("你是技能校验执行器，只使用提供的文件类工具完成任务，不要询问。"));
            msgs.add(LlmMessage.user(instruction));
            List<LlmToolSpec> specs = toolRegistry.specsFor(ALLOWED_TOOLS);
            boolean ok = true;
            for (int round = 0; round < PROMPT_MAX_ROUNDS; round++) {
                ChatResult r = client.chat(msgs, specs, new ChatClient.StreamListener() {
                });
                if (!r.hasToolCalls()) {
                    return new PromptRun(ok, dangerous); // 模型给出最终答复，执行结束
                }
                msgs.add(LlmMessage.assistant(r.content() == null ? "" : r.content(), r.toolCalls()));
                for (LlmToolCall call : r.toolCalls()) {
                    if (isDangerous(call.name())) {
                        dangerous = true;
                        issues.add("提示词技能试图调用校验不允许的工具: " + call.name());
                        msgs.add(LlmMessage.tool(call.id(), "ERROR: 校验环境禁止使用该工具"));
                        ok = false;
                        continue;
                    }
                    Map<String, Object> args = parseArgs(call.arguments());
                    ToolResult tr = toolRegistry.execute(call.name(), args, ctx);
                    if (!tr.ok()) {
                        ok = false;
                        issues.add("工具 " + call.name() + " 执行失败: " + cut(tr.output(), 200));
                    } else if (tr.file() != null) {
                        produced.add(tr.file());
                    }
                    msgs.add(LlmMessage.tool(call.id(), tr.toContextString()));
                }
            }
            return new PromptRun(ok, dangerous);
        } catch (Exception e) {
            issues.add("提示词技能执行异常: " + cut(e.getMessage(), 200));
            return new PromptRun(false, dangerous);
        }
    }

    /** 全文绝对路径扫描（parameterized_not_hardcoded）。 */
    private boolean checkNotHardcoded(String content, List<String> issues) {
        java.util.regex.Pattern abs = java.util.regex.Pattern
                .compile("[A-Za-z]:[\\\\/][^\\s\"']*|/Users/[^\\s\"']*|/home/[^\\s\"']*");
        java.util.regex.Matcher m = abs.matcher(content == null ? "" : content);
        if (m.find()) {
            issues.add("草稿包含写死的绝对路径: " + cut(m.group(), 80));
            return false;
        }
        return true;
    }

    /** 按扩展名做基本结构校验。 */
    private boolean checkStructural(Path root, List<String> produced, List<String> issues) {
        boolean all = true;
        for (String rel : produced) {
            try {
                Path p = root.resolve(rel);
                if (!Files.exists(p)) p = Paths.get(rel); // 工具回传的可能是绝对路径
                if (!Files.exists(p) || Files.size(p) == 0) {
                    issues.add("产出文件为空或不存在: " + rel);
                    all = false;
                    continue;
                }
                String lower = rel.toLowerCase();
                if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                    String head = Files.readString(p).toLowerCase();
                    if (!head.contains("<html") && !head.contains("<!doctype") && !head.contains("<body")) {
                        issues.add("HTML 结构可疑: " + rel);
                        all = false;
                    }
                } else if (lower.endsWith(".docx")) {
                    try (var in = Files.newInputStream(p)) {
                        byte[] magic = new byte[2];
                        if (in.read(magic) != 2 || magic[0] != 'P' || magic[1] != 'K') {
                            issues.add("docx 文件头非法: " + rel);
                            all = false;
                        }
                    }
                } else if (lower.endsWith(".json")) {
                    om.readTree(Files.readString(p));
                }
                // 其余扩展名只要求非空
            } catch (Exception e) {
                issues.add("结构校验失败 " + rel + ": " + cut(e.getMessage(), 120));
                all = false;
            }
        }
        return all;
    }

    private List<String> walkFiles(Path root) {
        List<String> out = new ArrayList<>();
        if (!Files.exists(root)) return out;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("SKILL.md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(p -> out.add(root.relativize(p).toString().replace('\\', '/')));
        } catch (Exception ignore) {
            // 目录已清理等情况
        }
        return out;
    }

    private boolean isDangerous(String tool) {
        if (tool == null) return false;
        return DANGEROUS_HINTS.contains(tool) || tool.startsWith("mcp__") || tool.equals("spawnAgent");
    }

    private boolean isWriteTool(String tool) {
        return "fileWrite".equals(tool) || "docxWrite".equals(tool) || "fileEdit".equals(tool);
    }

    private Skill toSkill(SkillParser.Parsed p, String content) {
        return new Skill(p.id(), p.name(), p.description(), p.triggers(), p.whenToUse(), content,
                "user", p.mode(), p.filePath(), true, 1,
                java.time.LocalDateTime.now().toString(), java.time.LocalDateTime.now().toString(),
                p.steps(), p.params());
    }

    private Map<String, Object> fillArgs(Map<String, String> args, Map<String, String> params) {
        Map<String, Object> out = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : args.entrySet()) {
            String value = e.getValue();
            for (Map.Entry<String, String> p : params.entrySet()) {
                value = value.replace("{{" + p.getKey() + "}}", p.getValue());
            }
            out.put(e.getKey(), value);
        }
        return out;
    }

    private Map<String, Object> parseArgs(String json) {
        try {
            return om.readValue(json == null || json.isBlank() ? "{}" : json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Path tempDir(long draftId) {
        String root = props.getRoot();
        Path base = (root == null || root.isBlank())
                ? Paths.get(System.getProperty("user.dir"), "workspace")
                : Paths.get(root);
        return base.toAbsolutePath().normalize().resolve(".validator").resolve("draft-" + draftId);
    }

    private void cleanup(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignore) {
                    // Windows 文件占用等；尽力删除
                }
            });
        } catch (Exception e) {
            log.warn("清理校验临时目录失败 {}: {}", dir, e.getMessage());
        }
    }

    private String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
