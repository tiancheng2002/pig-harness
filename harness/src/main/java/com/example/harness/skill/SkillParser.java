package com.example.harness.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 SKILL.md 解析器：frontmatter + 编号步骤 + 占位符提取。
 */
public class SkillParser {

    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);
    private static final Pattern STEP_LINE = Pattern.compile("^\\s*\\d+\\.\\s*(\\w+)\\s*\\((.*)\\)\\s*$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");

    public static Parsed parse(String id, String filePath, String content) {
        if (content == null) content = "";
        if (content.startsWith("\uFEFF")) content = content.substring(1); // 社区文件可能带 BOM
        String name = null;
        String description = null;
        List<String> triggers = new ArrayList<>();
        String whenToUse = null;
        String declaredMode = null;
        boolean enabled = true;
        int version = 1;
        String body = content;

        Matcher fm = FRONTMATTER.matcher(content.trim());
        if (fm.find()) {
            String front = fm.group(1);
            body = fm.group(2);
            Map<String, String> map = parseFrontmatter(front);
            name = map.get("name");
            description = map.get("description");
            whenToUse = map.get("when_to_use");
            declaredMode = normalizeMode(map.get("mode"));
            if (map.containsKey("enabled")) {
                enabled = Boolean.parseBoolean(map.get("enabled"));
            }
            if (map.containsKey("version")) {
                try {
                    version = Integer.parseInt(map.get("version"));
                } catch (NumberFormatException ignored) {
                }
            }
            triggers = parseTriggers(front);
        }

        List<SkillStep> steps = parseSteps(body);
        // 形态判定（M5-F1）：显式 mode 优先；缺省 auto——有步骤行即 steps，否则 prompt（散文指南）
        String mode = declaredMode != null ? declaredMode : (steps.isEmpty() ? "prompt" : "steps");

        Set<String> params = new LinkedHashSet<>();
        for (SkillStep step : steps) {
            for (String v : step.args().values()) {
                Matcher m = PLACEHOLDER.matcher(v);
                while (m.find()) params.add(m.group(1));
            }
        }
        // 散文正文的 {{placeholder}} 同样纳入填参范围（M5-F2；对 steps 模式无害）
        Matcher bodyPlaceholder = PLACEHOLDER.matcher(body);
        while (bodyPlaceholder.find()) params.add(bodyPlaceholder.group(1));

        return new Parsed(id, name, description, triggers, whenToUse, body.trim(),
                filePath, enabled, version, steps, params, mode);
    }

    /** mode 字段合法值归一：仅接受 steps / prompt，其余（含缺省）视为 auto（由解析结果决定）。 */
    private static String normalizeMode(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase()) {
            case "steps" -> "steps";
            case "prompt" -> "prompt";
            default -> null;
        };
    }

    /** 去掉 frontmatter，返回正文（提示词技能执行时注入用）。 */
    public static String stripFrontmatter(String content) {
        if (content == null || content.isBlank()) return "";
        return content.replaceAll("(?s)^---\\s*\\n.*?\\n---\\s*\\n", "").trim();
    }

    private static Map<String, String> parseFrontmatter(String front) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] lines = front.split("\\r?\\n");
        boolean inTriggers = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("triggers:")) {
                inTriggers = true;
                continue;
            }
            if (inTriggers) {
                if (trimmed.startsWith("- ")) {
                    continue; // triggers 单独处理
                } else {
                    inTriggers = false;
                }
            }
            int idx = trimmed.indexOf(':');
            if (idx > 0) {
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                map.put(key, value);
            }
        }
        return map;
    }

    private static List<String> parseTriggers(String front) {
        List<String> list = new ArrayList<>();
        String[] lines = front.split("\\r?\\n");
        boolean inTriggers = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("triggers:")) {
                String inline = trimmed.substring("triggers:".length()).trim();
                // 支持 inline 数组：triggers: [a, b, c]
                if (inline.startsWith("[") && inline.endsWith("]")) {
                    String inner = inline.substring(1, inline.length() - 1);
                    for (String v : inner.split(",")) {
                        v = v.trim();
                        if (v.startsWith("\"") && v.endsWith("\"")) {
                            v = v.substring(1, v.length() - 1);
                        }
                        if (!v.isBlank()) list.add(v);
                    }
                    return list;
                }
                inTriggers = true;
                continue;
            }
            if (inTriggers) {
                if (trimmed.startsWith("- ")) {
                    String v = trimmed.substring(2).trim();
                    if (v.startsWith("\"") && v.endsWith("\"")) {
                        v = v.substring(1, v.length() - 1);
                    }
                    if (!v.isBlank()) list.add(v);
                } else if (trimmed.isEmpty()) {
                    continue;
                } else {
                    break;
                }
            }
        }
        return list;
    }

    private static List<SkillStep> parseSteps(String body) {
        List<SkillStep> steps = new ArrayList<>();
        String[] lines = body.split("\\r?\\n");
        int idx = 1;
        for (String line : lines) {
            Matcher m = STEP_LINE.matcher(line.trim());
            if (!m.find()) continue;
            String tool = m.group(1);
            String argBody = m.group(2);
            Map<String, String> args = parseKvPairs(argBody);
            steps.add(new SkillStep(idx++, tool, args, line.trim()));
        }
        return steps;
    }

    /**
     * 手写线性扫描 key="value" 键值对（转义 \" 与 \n）。
     * 不用正则：技能文件的单行 content 值可达数 KB，正则 `(?:\\.|[^"\\])*`
     * 按字符递归回溯会导致 StackOverflowError。
     */
    private static Map<String, String> parseKvPairs(String argBody) {
        Map<String, String> args = new LinkedHashMap<>();
        int i = 0;
        int n = argBody.length();
        while (i < n) {
            // 扫描标识符 key
            if (!Character.isJavaIdentifierStart(argBody.charAt(i))) {
                i++;
                continue;
            }
            int keyStart = i;
            while (i < n && Character.isJavaIdentifierPart(argBody.charAt(i))) i++;
            String key = argBody.substring(keyStart, i);
            // 跳过空白，期待 '='
            while (i < n && Character.isWhitespace(argBody.charAt(i))) i++;
            if (i >= n || argBody.charAt(i) != '=') continue;
            i++;
            while (i < n && Character.isWhitespace(argBody.charAt(i))) i++;
            if (i >= n || argBody.charAt(i) != '"') continue; // 只接受双引号值
            i++;
            StringBuilder val = new StringBuilder();
            while (i < n) {
                char c = argBody.charAt(i);
                if (c == '\\' && i + 1 < n) {
                    char next = argBody.charAt(i + 1);
                    val.append(next == 'n' ? '\n' : next); // \" → "，\n → 换行，其余原样保留
                    i += 2;
                } else if (c == '"') {
                    i++;
                    break;
                } else {
                    val.append(c);
                    i++;
                }
            }
            args.put(key, val.toString());
        }
        return args;
    }

    public record Parsed(
            String id,
            String name,
            String description,
            List<String> triggers,
            String whenToUse,
            String source,
            String filePath,
            boolean enabled,
            int version,
            List<SkillStep> steps,
            Set<String> params,
            String mode
    ) {
    }
}
