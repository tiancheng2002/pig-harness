package com.example.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock LLM：未配置 API Key 时的本地演示实现。
 * 依据任务关键词模拟真实 Agent 的行为序列：
 * 模式路由(CHAT) → 规划(JSON) → 工具调用 → 观察结果 → （失败则反思重试）→ 结论。
 * 文本按分片回调模拟流式打字机效果。
 */
public class MockChatClient implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(MockChatClient.class);
    private static final Pattern PLAN_MARKER = Pattern.compile("\\[ROLE=PLANNER\\]");
    private static final Pattern CHAT_MARKER = Pattern.compile("\\[MODE=CHAT\\]");
    private static final Pattern CLASSIFIER_MARKER = Pattern.compile("\\[ROLE=CLASSIFIER\\]");
    private static final Pattern SKILL_WRITER_MARKER = Pattern.compile("\\[ROLE=SKILL_WRITER\\]");
    private static final Pattern DECOMPOSER_MARKER = Pattern.compile("\\[ROLE=DECOMPOSER\\]");
    private static final Pattern AGGREGATOR_MARKER = Pattern.compile("\\[ROLE=AGGREGATOR\\]");
    private static final Pattern COMPACTOR_MARKER = Pattern.compile("\\[ROLE=COMPACTOR\\]");
    /** 交付动词（拆分触发门槛：≥2 类才可能并行，宁可少扇出） */
    private static final Pattern DELIVER_VERB = Pattern.compile(
            "生成|创建|新建|做一个|做个|编写|写一份|产出|设计|写");
    private static final Pattern FILE_NAME = Pattern.compile("([\\w\\-.]+\\.(?:html|md|txt|docx|json|csv|java|js|py))", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPE_PATH = Pattern.compile("(\\.\\.[/\\\\])+[\\w\\-.]+");
    private static final Pattern TASKY = Pattern.compile(
            "创建|新建|生成|写入|修改|编辑|删除|读取|查看|列出|保存|写一?个?|做个|做一?个?|文件|docx|word|html|md\\b|bash|命令行|执行命令",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper om = new ObjectMapper();

    /** 技能合成（Mock）：从合成上下文里取「本次任务」首行，生成草稿元数据 JSON。
     *  test_input 刻意包含触发词短语——保证无 Embedding Key 时（关键词路由按子串匹配）闭环演示可命中。 */
    private String mockSkillMeta(String context) {
        String task = context == null ? "" : context.trim();
        int idx = task.indexOf("== 本次任务");
        if (idx >= 0) {
            int lineStart = task.indexOf('\n', idx);
            int lineEnd = task.indexOf('\n', lineStart + 1);
            task = task.substring(lineStart + 1, lineEnd > lineStart ? lineEnd : task.length()).trim();
        }
        String safe = task.replace("\"", "'").replace("\n", " ").trim();
        String shortTask = safe.length() > 36 ? safe.substring(0, 36) : safe;
        String trigger = safe.length() > 12 ? safe.substring(0, 12) : safe;
        return "{\"name\":\"draft-skill-" + (Math.abs(safe.hashCode()) % 10000) + "\","
                + "\"description\":\"把「" + shortTask + "」这类请求按验证过的步骤自动完成\","
                + "\"triggers\":[\"" + trigger + "\"],"
                + "\"when_to_use\":\"用户要求执行与「" + shortTask + "」类似的任务时\","
                + "\"test_input\":\"请再做一次这个任务：" + trigger + "\"}";
    }

    @Override
    public String modelName() {
        return "mock";
    }

    public ChatResult chat(List<LlmMessage> messages, List<LlmToolSpec> tools, StreamListener listener) {
        ChatResult r = chatInner(messages, tools, listener);
        // Mock 无真实 usage：按启发式估算填充，保证上下文展示与压缩链路在演示模式全链路可跑
        return new ChatResult(r.content(), r.reasoning(), r.toolCalls(),
                new ChatResult.Usage(TokenEstimator.estimateMessages(messages),
                        TokenEstimator.estimate(r.content())));
    }

    private ChatResult chatInner(List<LlmMessage> messages, List<LlmToolSpec> tools,
                                 StreamListener listener) {
        try {
            String sys = messages.isEmpty() ? "" : messages.get(0).content();
            if (sys != null && "system".equals(messages.get(0).role())) {
                // 模式路由的直答分支：对话模式直接流式回复
                if (CHAT_MARKER.matcher(sys).find()) {
                    String reply = chatReply(lastUser(messages));
                    return streamOut(reply, null, listener);
                }
                // 意图分类器分支：按任务关键词返回 CHAT/TASK
                if (CLASSIFIER_MARKER.matcher(sys).find()) {
                    String input = lastUser(messages);
                    return new ChatResult(TASKY.matcher(input).find() ? "TASK" : "CHAT", List.of());
                }
                // 规划器分支：返回预置计划 JSON（不流式）
                if (PLAN_MARKER.matcher(sys).find()) {
                    return new ChatResult(planJson(lastUser(messages)), List.of());
                }
                // 技能合成器分支：从上下文提取任务文本，生成草稿技能元数据 JSON
                if (SKILL_WRITER_MARKER.matcher(sys).find()) {
                    return new ChatResult(mockSkillMeta(lastUser(messages)), List.of());
                }
                // 多 Agent 拆分器分支（M4）：多产物任务返回固定子任务 DAG，否则 single
                if (DECOMPOSER_MARKER.matcher(sys).find()) {
                    return new ChatResult(decomposeJson(lastUser(messages)), List.of());
                }
                // 多 Agent 聚合器分支（M4）：按各子任务结果输出模板结论（流式）
                if (AGGREGATOR_MARKER.matcher(sys).find()) {
                    return streamOut(aggregateReply(lastUser(messages)), null, List.of(), listener);
                }
                // 对话级压缩器分支：返回固定结构化摘要（上下文压缩的演示路径）
                if (COMPACTOR_MARKER.matcher(sys).find()) {
                    return new ChatResult(mockCompactSummary(lastUser(messages)), List.of());
                }
            }

            String task = lastUser(messages);
            LlmMessage lastTool = null;
            int toolMsgCount = 0;
            for (LlmMessage m : messages) {
                if ("tool".equals(m.role())) {
                    toolMsgCount++;
                    lastTool = m;
                }
            }

            // 子智能体执行分支：「代码探索」的指令演示——先 listFiles 浏览项目，再输出调研结论
            if (task.contains("[子智能体:代码探索]")) {
                String toolOut = lastTool != null && lastTool.content() != null ? lastTool.content() : "";
                if (toolMsgCount == 0) {
                    return toolCall("listFiles", Map.of("dir", ".", "recursive", true));
                }
                return streamOut("（Mock 调研）已浏览项目结构（" + truncateText(toolOut, 160)
                        + "）。定位到与任务相关的关键文件，可直接 fileRead/grep 深入，无写入操作。",
                        null, List.of(), listener);
            }

            // 计划模式演示分支（[MODE=PLAN] 标记）：listFiles 调研 → askUser 澄清 → 输出结构化计划文档
            if (sys.contains("[MODE=PLAN]") && !task.contains("[子智能体:")) {
                if (toolMsgCount == 0) {
                    String reasoning = "（Mock 计划模式）先浏览项目结构，再就关键分歧向你澄清，最后产出计划文档。";
                    return streamOut(null, reasoning, List.of(new LlmToolCall(
                            "call_mock_" + System.nanoTime() % 100000, "listFiles",
                            om.writeValueAsString(Map.of("dir", ".")))), listener);
                }
                if (toolMsgCount == 1) {
                    return toolCall("askUser", Map.of(
                            "question", "页面计划采用哪种风格？（计划模式演示问题）",
                            "options", List.of(
                                    "简约黑白：单色系 + 大量留白",
                                    "卡片式：圆角卡片分组内容",
                                    "仪表盘式：侧栏 + 数据面板"),
                            "recommended", 0));
                }
                String answerText = lastTool != null && lastTool.content() != null ? lastTool.content() : "";
                String confirmed = answerText.replace("用户答复：", "").trim();
                return streamOut(planDocument(confirmed), null, List.of(), listener);
            }

            // 首轮：发起工具调用（附带一段模拟思考）
            if (toolMsgCount == 0) {
                // spawnAgent 演示分支：工具清单含 spawnAgent 且任务要求调研/探索项目代码时，先派「代码探索」
                boolean hasSpawnAgent = tools.stream().anyMatch(t -> "spawnAgent".equals(t.name()));
                String lowTask = task.toLowerCase();
                boolean exploreAsk = (task.contains("探索") || task.contains("调研") || task.contains("搜索代码")
                        || lowTask.contains("explore")) && (task.contains("代码") || task.contains("项目") || lowTask.contains("code"));
                if (hasSpawnAgent && exploreAsk) {
                    String reasoning = "（Mock 思考）任务要求先了解项目代码。派出「代码探索」子智能体做只读调研，拿到结论后再作答。";
                    return streamOut(null, reasoning, List.of(new LlmToolCall(
                            "call_mock_" + System.nanoTime() % 100000, "spawnAgent",
                            om.writeValueAsString(Map.of("agent", "代码探索", "task", task)))), listener);
                }
                // MCP 演示分支：工具清单含 mcp__ 工具且任务明确要求调用时，按远端工具名发起调用
                var mcpTool = tools.stream().filter(t -> t.name().startsWith("mcp__")).findFirst();
                String low = task.toLowerCase();
                if (mcpTool.isPresent() && (low.contains("mcp") || task.contains("调用") || task.contains("echo"))) {
                    String tn = mcpTool.get().name();
                    String lastSegment = tn.substring(tn.lastIndexOf("__") + 2);
                    Map<String, Object> mcpArgs = "echo".equals(lastSegment)
                            ? Map.of("text", echoTextOf(task)) : Map.of();
                    String reasoning = "（Mock 思考）任务要求调用 MCP 工具 " + tn + "，按参数发起调用并观察返回。";
                    return streamOut(null, reasoning, List.of(new LlmToolCall(
                            "call_mock_" + System.nanoTime() % 100000, tn, om.writeValueAsString(mcpArgs))), listener);
                }
                // P1 codeSearch 演示分支：工具清单含 codeSearch 且任务明确要求语义检索时，发起语义查询
                boolean hasCodeSearch = tools.stream().anyMatch(t -> "codeSearch".equals(t.name()));
                if (hasCodeSearch && (low.contains("codesearch") || task.contains("语义检索") || task.contains("语义搜索"))) {
                    String reasoning = "（Mock 思考）任务要求按语义定位代码，用 codeSearch 在项目索引中检索。";
                    return streamOut(null, reasoning, List.of(new LlmToolCall(
                            "call_mock_" + System.nanoTime() % 100000, "codeSearch",
                            om.writeValueAsString(Map.of("query", task)))), listener);
                }
                ChatResult action = firstAction(task);
                String reasoning = mockReasoning(task);
                return streamOut(action.content(), reasoning, action.toolCalls(), listener);
            }
            boolean lastFailed = lastTool != null && lastTool.content() != null
                    && lastTool.content().startsWith("ERROR");
            // 仅在首次失败后 reflect 并用唯一安全路径重试一次
            if (lastFailed && toolMsgCount == 1) {
                return recoveryAction(task);
            }
            // 子智能体结论回灌：基于调研结果总结作答
            String lastToolText0 = lastTool != null && lastTool.content() != null ? lastTool.content() : "";
            if (lastToolText0.contains("【子智能体")) {
                return streamOut("✅ 已派出「代码探索」子智能体完成调研，结论如下：\n\n"
                        + truncateText(lastToolText0, 500) + "\n\n（Mock 演示：spawnAgent 派发 → 泳道执行 → 结论回灌全链路。）",
                        null, List.of(), listener);
            }
            // 写文本文件成功后 → fileEdit 演示增量修改 → fileRead 校验（演示 observe）
            if (!lastFailed && "write".equals(kindOf(task))) {
                String path = extractPath(task, "login.html");
                if (toolMsgCount == 1) {
                    return toolCall("fileEdit", Map.of(
                            "path", path,
                            "old_string", "忘记密码请联系管理员",
                            "new_string", "忘记密码请联系管理员（演示：由 fileEdit 增量修改）"));
                }
                if (toolMsgCount == 2) {
                    return toolCall("fileRead", Map.of("path", path));
                }
            }
            // codeSearch 结果回灌 → 总结检索结论
            String lastToolTextPre = lastTool != null && lastTool.content() != null ? lastTool.content() : "";
            if (lastToolTextPre.contains("语义检索") || lastToolTextPre.contains("codeSearch")) {
                return streamOut("✅ 已用 codeSearch 完成代码语义检索，结果：\n"
                        + truncateText(lastToolTextPre, 400), null, List.of(), listener);
            }
            // 否则给出最终结论
            String lastToolText = lastTool != null && lastTool.content() != null ? lastTool.content() : "";
            if (lastToolText.contains("echo: ")) {
                return streamOut("✅ 已调用 MCP echo 工具，返回内容："
                        + truncateText(lastToolText, 120), null, List.of(), listener);
            }
            return streamOut(conclusion(task), null, List.of(), listener);
        } catch (Exception e) {
            log.warn("Mock 异常: {}", e.getMessage());
            return new ChatResult("Mock 内部异常: " + e.getMessage(), List.of());
        }
    }

    private String truncateText(String s, int max) {
        s = s == null ? "" : s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Mock 计划模式：结构化计划文档（E2E 断言「实施清单/验证/待确认」等结构关键词）。 */
    private String planDocument(String confirmed) {
        return "## 实施计划（Mock 演示）\n\n"
                + "### 1. 目标\n按你确认的方向「" + truncateText(confirmed, 60) + "」产出可执行计划。\n\n"
                + "### 2. 现状调研结论\n- 已用 listFiles 浏览工作区结构，无同名冲突文件。\n\n"
                + "### 3. 分步实施清单\n"
                + "1. 创建 demo.html（标题 + 正文结构）；\n"
                + "2. 创建 style.css（简约风格，与页面配套）；\n"
                + "3. fileRead 回查两份文件确认写入内容。\n\n"
                + "### 4. 风险与验证方式\n"
                + "- 风险：同名文件覆盖 → 实施前先 listFiles 复查。\n"
                + "- 验证：fileRead 逐份回查 + 浏览器打开 demo.html 确认渲染。\n\n"
                + "### 5. 待确认事项\n无——关键分歧已在澄清阶段确认。";
    }

    /** 从任务文本里提取 echo 参数：优先 text= 后的片段，否则默认 ping。 */
    private String echoTextOf(String task) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("text[=：:]\\s*([^，。,；;\"']+)")
                .matcher(task == null ? "" : task);
        return m.find() ? m.group(1).trim() : "ping";
    }

    /** 对话压缩（Mock）：统计待摘要内容的轮次规模，输出固定结构化摘要。 */
    private String mockCompactSummary(String payload) {
        int turns = 0;
        for (String line : (payload == null ? "" : payload).split("\n")) {
            if (line.startsWith("用户:") || line.startsWith("助手:")) {
                turns++;
            }
        }
        return """
                （Mock 摘要）此前共约 %d 条对话轮次。要点：
                - 用户此前下达过文件操作类任务，助手已完成规划、执行与结果汇总
                - 已产出的文件与关键结论以各轮 done 事件为准
                - 未尽事宜与用户偏好在此演示摘要中不保留
                """.formatted(turns);
    }

    // ---------- 多 Agent 拆分 / 聚合（M4 Mock） ----------

    /**
     * 拆分（Mock）：优先取任务文本中出现的文件名（≤3 个）；无文件名时按多产物关键词
     * 合成固定拆分（登录页→login.html、文档→docs/api.md、测试→tests/test-plan.md、报告→report.md）。
     * 门槛（体验升级七收紧）：≥2 类交付动词且 ≥3 个产物才 parallel——生成两三个简单小文件的
     * 任务由主 Agent 顺序完成更省，扇出保留给真正的大任务。
     */
    private String decomposeJson(String task) {
        List<String> files = new ArrayList<>();
        Matcher m = FILE_NAME.matcher(task == null ? "" : task);
        while (m.find() && files.size() < 3) {
            String f = m.group(1).toLowerCase();
            if (!files.contains(f)) {
                files.add(f);
            }
        }
        // 触发门槛：≥2 类交付动词才认为存在多个并行交付物（修改/查询类任务一律 single）
        java.util.Set<String> verbs = new java.util.HashSet<>();
        Matcher vm = DELIVER_VERB.matcher(task == null ? "" : task);
        while (vm.find()) {
            verbs.add(vm.group());
        }
        if (verbs.size() < 2) {
            return "{\"mode\":\"single\",\"subtasks\":[]}";
        }
        if (files.size() < 3) {
            String t = task == null ? "" : task;
            if ((t.contains("登录") || t.contains("页面") || t.contains("网页"))
                    && !files.contains("login.html")) {
                files.add("login.html");
            }
            if ((t.contains("文档") || t.toLowerCase().contains("api")) && !files.contains("api-docs.md")) {
                files.add("api-docs.md");
            }
            if (t.contains("测试") && !files.contains("test-plan.md")) {
                files.add("test-plan.md");
            }
            if ((t.contains("报告") || t.contains("周报")) && !files.contains("report.md")) {
                files.add("report.md");
            }
            if (files.size() > 3) {
                files = files.subList(0, 3);
            }
        }
        if (files.size() < 3) {
            return "{\"mode\":\"single\",\"subtasks\":[]}";
        }
        StringBuilder sb = new StringBuilder("{\"mode\":\"parallel\",\"subtasks\":[");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            // description 不引用任务原文：避免子 Agent 误提取兄弟任务的文件名
            sb.append("{\"id\":\"s").append(i + 1)
                    .append("\",\"description\":\"子任务 ").append(i + 1)
                    .append("：生成文件 ").append(files.get(i))
                    .append("，内容围绕本次用户任务的主题，写 3-5 行说明")
                    .append("\",\"depends_on\":[],\"output_paths\":[\"")
                    .append(files.get(i)).append("\"],\"suggested_tools\":[\"fileWrite\"]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 聚合（Mock）：从聚合 payload 统计子任务与产出文件，输出模板结论（经 streamOut 流式）。 */
    private String aggregateReply(String payload) {
        int total = 0;
        int ok = 0;
        List<String> files = new ArrayList<>();
        for (String line : (payload == null ? "" : payload).split("\n")) {
            String t = line.trim();
            if (t.startsWith("- sub-") && t.contains("（")) {
                total++;
                if (t.contains("success") || t.contains("partial")) {
                    ok++;
                }
            }
            if (t.startsWith("产出文件：") && !t.contains("无")) {
                String f = t.substring("产出文件：".length()).trim();
                if (!f.isEmpty()) {
                    files.add(f);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 多 Agent 协作完成\n\n共派发 **").append(total)
                .append("** 个子 Agent，成功 **").append(ok).append("** 个。\n\n");
        if (!files.isEmpty()) {
            sb.append("### 产出文件\n");
            for (String f : files) {
                sb.append("- ").append(f).append('\n');
            }
            sb.append('\n');
        }
        sb.append("（Mock 演示模式：以上为多 Agent 拆分 → 并行执行 → 聚合的全链路演示。）");
        return sb.toString();
    }

    // ---------- 流式模拟 ----------

    /** 把最终文本分片回调，模拟打字机；不改变返回结果。 */
    private ChatResult streamOut(String content, String reasoning, List<LlmToolCall> calls,
                                 StreamListener listener) {
        if (listener != null) {
            emitChunks(reasoning, 8, 15, listener::onReasoning);
            int chunk = content == null || content.length() <= 600 ? 6 : 24;
            long pause = content == null || content.length() <= 600 ? 18 : 8;
            emitChunks(content, chunk, pause, listener::onText);
        }
        return new ChatResult(content, reasoning, calls);
    }

    private ChatResult streamOut(String content, String reasoning, StreamListener listener) {
        return streamOut(content, reasoning, List.of(), listener);
    }

    private void emitChunks(String text, int size, long pauseMs, java.util.function.Consumer<String> sink) {
        if (text == null || text.isEmpty()) return;
        try {
            for (int i = 0; i < text.length(); i += size) {
                sink.accept(text.substring(i, Math.min(text.length(), i + size)));
                Thread.sleep(pauseMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 行为判定 ----------

    private String kindOf(String task) {
        String t = task.toLowerCase();
        if (ESCAPE_PATH.matcher(task).find()) return "escape";
        if (t.contains("删除") && FILE_NAME.matcher(task).find()) return "delete";
        if (t.contains("列出") || t.contains("有哪些文件") || t.contains("目录里") || t.contains("目录下")) return "list";
        if (t.contains(".docx") || t.contains("word") || t.contains("周报") || t.contains("报告")) return "docx";
        if ((t.contains("读") || t.contains("查看") || t.contains("读取")) && FILE_NAME.matcher(task).find()) return "read";
        if (t.contains("bash") || t.contains("命令行") || t.contains("执行命令")) return "shell";
        if (t.contains(".html") || t.contains("登录") || t.contains("页面") || t.contains("网页") || t.contains("login")) return "write";
        return "generic";
    }

    private String extractPath(String task, String fallback) {
        Matcher m = FILE_NAME.matcher(task);
        if (m.find()) return m.group(1).replace('\\', '/');
        return fallback;
    }

    /** 从任务文本提取要执行的命令（「执行/运行命令[:：] 后面的部分」），缺省回退 echo 演示。 */
    private String shellCommandOf(String task) {
        Matcher m = java.util.regex.Pattern
                .compile("(?:执行|运行)(?:命令|shell|bash)?[:：]?\\s*(.+)")
                .matcher(task == null ? "" : task);
        if (m.find()) {
            String cmd = m.group(1).trim();
            if (!cmd.isEmpty()) {
                return cmd.length() > 200 ? cmd.substring(0, 200) : cmd;
            }
        }
        return "echo harness-mock-shell-ok";
    }

    private String mockReasoning(String task) {
        return switch (kindOf(task)) {
            case "escape" -> "（Mock 思考）用户要求写入越界路径，按规则先尝试原路径，预期沙箱会拒绝，随后调整策略。";
            case "docx" -> "（Mock 思考）这是文档生成任务，我将组织标题/进展/风险三部分结构，调用 docxWrite 生成。";
            case "read" -> "（Mock 思考）用户想查看文件内容，先调用 fileRead 读取，再总结回答。";
            case "write" -> "（Mock 思考）这是页面生成任务，我先规划表单结构，调用 fileWrite 创建，再用 fileEdit/fileRead 验证。";
            case "list" -> "（Mock 思考）用户想浏览沙箱内容，调用 listFiles 列出目录。";
            case "shell" -> "（Mock 思考）用户要执行命令，调用 bash 运行并观察输出与退出码。";
            case "delete" -> "（Mock 思考）用户要求删除文件，调用 fileDelete，若目录非空会被拒绝。";
            default -> "（Mock 思考）通用任务，我将结果整理后写入文件并总结。";
        };
    }

    private String chatReply(String input) {
        String t = input == null ? "" : input.toLowerCase();
        if (t.contains("你是谁") || t.contains("能做什么") || t.contains("介绍") || t.contains("什么功能")) {
            return """
                    我是运行在你本机的 **Harness 助手**（当前为 Mock 演示模式）。我可以：

                    - 📝 在沙箱目录内创建/修改文件（fileWrite / fileEdit / fileDelete）
                    - 📖 读取并分析项目文件（fileRead，支持分页）
                    - 📂 浏览目录结构（listFiles）
                    - 📄 生成真实 Word 文档（docxWrite）
                    - ⌨️ 执行命令（bash：编译、测试、git、查看进程等调试操作）
                    - 🔁 自动规划多步任务并展示执行轨迹

                    需要我动手操作文件时，直接用任务式描述（如「帮我创建一个登录页」）即可。
                    """;
        }
        if (t.contains("谢谢") || t.contains("感谢")) {
            return "不客气！有其他任务（写页面、改文件、生成文档）随时告诉我。😊";
        }
        return """
                你好！我是本机 Harness 助手，闲聊/问答可以直接问我。

                需要动手干活（创建页面、改文件、生成 Word 报告等）时，直接描述任务，
                我会先规划再逐步执行，产出文件后给你可点击的预览链接。
                """;
    }

    private ChatResult firstAction(String task) throws Exception {
        String kind = kindOf(task);
        switch (kind) {
            case "escape" -> {
                Matcher m = ESCAPE_PATH.matcher(task);
                String path = m.find() ? m.group() : "../../outside.txt";
                return toolCall("fileWrite", Map.of(
                        "path", path,
                        "content", "# outside\n\n演示越界写入 —— 该请求应被沙箱拒绝。\n"));
            }
            case "docx" -> {
                String path = extractPath(task, "report.docx");
                List<Object> blocks = weeklyReportBlocks(task);
                return toolCall("docxWrite", Map.of(
                        "path", path,
                        "title", titleOf(task),
                        "content", blocks));
            }
            case "read" -> {
                return toolCall("fileRead", Map.of("path", extractPath(task, "readme.md")));
            }
            case "list" -> {
                return toolCall("listFiles", Map.of("dir", ".", "recursive", true));
            }
            case "delete" -> {
                return toolCall("fileDelete", Map.of("path", extractPath(task, "about.html")));
            }
            case "shell" -> {
                return toolCall("bash", Map.of("command", shellCommandOf(task)));
            }
            case "write" -> {
                String path = extractPath(task, "login.html");
                return toolCall("fileWrite", Map.of("path", path, "content", loginHtml(task)));
            }
            default -> {
                // 路径优先取任务/子任务描述中的文件名（多 Agent 时各子任务产出不同文件）
                return toolCall("fileWrite", Map.of(
                        "path", extractPath(task, "readme.md"),
                        "content", "# 任务记录\n\n" + task + "\n\n（由 Harness Mock 模式生成）\n"));
            }
        }
    }

    private ChatResult recoveryAction(String task) throws Exception {
        String kind = kindOf(task);
        String suffix = Long.toString(System.currentTimeMillis() % 100000);
        if ("escape".equals(kind)) {
            return toolCall("fileWrite", Map.of(
                    "path", "outside-" + suffix + ".txt",
                    "content", "# outside\n\n原路径越界被拒后，改为沙箱内的安全路径。\n"));
        }
        return toolCall("fileWrite", Map.of(
                "path", "recovered-" + suffix + ".md",
                "content", "# 重试产物\n\n上次调用失败，已调整策略使用唯一路径。\n\n任务: " + task + "\n"));
    }

    private ChatResult toolCall(String name, Map<String, Object> args) throws Exception {
        return new ChatResult(null, List.of(new LlmToolCall(
                "call_mock_" + System.nanoTime() % 100000, name, om.writeValueAsString(args))));
    }

    // ---------- 规划 ----------

    private String planJson(String task) throws Exception {
        List<Object> steps = new ArrayList<>();
        switch (kindOf(task)) {
            case "escape" -> {
                steps.add(step("分析目标路径，尝试写入指定文件", "fileWrite", "执行用户要求的写入"));
                steps.add(step("若越界被拒，调整为沙箱内安全路径重试", "fileWrite", "reflect 重试"));
                steps.add(step("汇总结论", null, "收尾"));
            }
            case "docx" -> {
                steps.add(step("确定报告结构与章节", null, "先规划内容"));
                steps.add(step("调用 docxWrite 生成 Word 文档（含标题/段落/表格）", "docxWrite", "生成 docx"));
                steps.add(step("汇总结论", null, "收尾"));
            }
            case "read" -> {
                steps.add(step("定位目标文件", null, "确定路径"));
                steps.add(step("调用 fileRead 读取内容", "fileRead", "读取文件"));
                steps.add(step("汇总结论", null, "收尾"));
            }
            case "list" -> {
                steps.add(step("调用 listFiles 浏览沙箱目录", "listFiles", "列目录"));
                steps.add(step("汇总文件清单与大小", null, "收尾"));
            }
            case "delete" -> {
                steps.add(step("调用 fileDelete 删除目标文件", "fileDelete", "删除文件"));
                steps.add(step("确认删除结果并汇总", null, "收尾"));
            }
            case "shell" -> {
                steps.add(step("确定要执行的命令与工作目录", null, "准备命令"));
                steps.add(step("调用 bash 执行命令并观察输出", "bash", "执行命令"));
                steps.add(step("根据输出汇总结论", null, "收尾"));
            }
            case "write" -> {
                steps.add(step("分析页面需求，确定结构（表单/输入框/按钮）", null, "先规划"));
                steps.add(step("调用 fileWrite 创建 " + extractPath(task, "login.html"), "fileWrite", "生成页面"));
                steps.add(step("调用 fileEdit 增量修改提示文案并 fileRead 校验", "fileEdit", "观察产物"));
                steps.add(step("汇总结论", null, "收尾"));
            }
            default -> {
                steps.add(step("分析任务意图", null, "理解需求"));
                steps.add(step("调用 fileWrite 将结果写入 readme.md", "fileWrite", "落地产物"));
                steps.add(step("汇总结论", null, "收尾"));
            }
        }
        return om.writeValueAsString(steps);
    }

    private Map<String, Object> step(String step, String tool, String reason) {
        return Map.of("step", step, "tool", tool == null ? "" : tool, "reason", reason);
    }

    // ---------- 结论 ----------

    private String conclusion(String task) {
        return switch (kindOf(task)) {
            case "escape" -> "⚠️ 原始路径越界被沙箱拒绝，已反思调整为工作根内的安全路径完成写入。";
            case "docx" -> "✅ 已生成 Word 文档 **" + extractPath(task, "report.docx")
                    + "**（含标题、段落与表格），可点击文件链接下载查看。";
            case "read" -> "✅ 已完成文件读取，内容见工具调用结果。";
            case "write" -> "✅ 已创建 **" + extractPath(task, "login.html")
                    + "**，并通过 fileEdit 增量修改 + fileRead 校验，包含所需表单元素，可点击文件链接预览。";
            case "list" -> "✅ 已列出沙箱内容，文件清单与大小见工具调用结果。";
            case "shell" -> "✅ 已通过 bash 执行命令，输出与退出码见工具调用记录。";
            case "delete" -> "✅ 已删除 " + extractPath(task, "about.html") + "。";
            default -> "✅ 任务完成，产出文件见上方文件链接。";
        };
    }

    private String titleOf(String task) {
        if (task.contains("周报")) return "本周工作周报";
        if (task.contains("报告")) return "工作报告";
        return "Harness 文档";
    }

    // ---------- 内容模板 ----------

    private String loginHtml(String task) {
        String title = task.contains("登录") ? "用户登录" : "页面";
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <style>
                  * { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
                  body { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg,#667eea,#764ba2); }
                  .card { background: #fff; border-radius: 12px; padding: 40px 36px; width: 360px; box-shadow: 0 20px 60px rgba(0,0,0,.3); }
                  h1 { font-size: 22px; text-align: center; color: #333; margin-bottom: 24px; }
                  .field { margin-bottom: 16px; }
                  label { display: block; font-size: 13px; color: #666; margin-bottom: 6px; }
                  input { width: 100%%; padding: 10px 12px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 14px; outline: none; transition: border .2s; }
                  input:focus { border-color: #667eea; }
                  button { width: 100%%; padding: 11px; background: linear-gradient(135deg,#667eea,#764ba2); color: #fff; border: none; border-radius: 6px; font-size: 15px; cursor: pointer; margin-top: 8px; }
                  button:hover { opacity: .92; }
                  .tip { text-align: center; font-size: 12px; color: #999; margin-top: 16px; }
                </style>
                </head>
                <body>
                  <form class="card" action="/login" method="post" onsubmit="return handleSubmit(event)">
                    <h1>%s</h1>
                    <div class="field">
                      <label for="username">用户名</label>
                      <input id="username" name="username" type="text" placeholder="请输入用户名" required>
                    </div>
                    <div class="field">
                      <label for="password">密码</label>
                      <input id="password" name="password" type="password" placeholder="请输入密码" required>
                    </div>
                    <button type="submit">登 录</button>
                    <p class="tip">忘记密码请联系管理员</p>
                  </form>
                  <script>
                    function handleSubmit(e) {
                      e.preventDefault();
                      alert('登录请求已提交（演示页面）');
                      return false;
                    }
                  </script>
                </body>
                </html>
                """.formatted(title, title);
    }

    private List<Object> weeklyReportBlocks(String task) {
        List<Object> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "paragraph", "text",
                "本周围绕核心目标稳步推进，整体进度符合预期，各项关键任务按计划完成。"));
        blocks.add(Map.of("type", "heading", "text", "本周进展", "level", 2));
        blocks.add(Map.of("type", "bullet", "items", List.of(
                "完成登录页面开发与联调，包含用户名/密码输入与提交按钮",
                "打通 Agent Harness 主链路：规划 → 执行 → 观察 → 反思",
                "落地 SQLite 执行轨迹存储，可查询历史任务")));
        blocks.add(Map.of("type", "heading", "text", "风险", "level", 2));
        blocks.add(Map.of("type", "table", "rows", List.of(
                List.of("风险项", "影响", "应对措施"),
                List.of("接口联调进度依赖第三方", "可能阻塞上线", "提前对齐时间表并准备 mock 方案"),
                List.of("需求变更频繁", "返工成本上升", "建立变更评审机制"),
                List.of("沙箱策略与审批流程待完善", "覆盖文件存在误操作风险", "已支持覆盖写入，配合 fileEdit 精确修改"))));
        return blocks;
    }

    private String lastUser(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage m = messages.get(i);
            if ("user".equals(m.role()) && m.content() != null) return m.content();
        }
        return "";
    }
}
