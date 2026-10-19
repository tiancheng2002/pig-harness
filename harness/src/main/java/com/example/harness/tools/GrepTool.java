package com.example.harness.tools;

import com.example.harness.sandbox.Sandbox;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * grep：在沙箱内按正则/字面量搜索文本文件，返回「相对路径:行号: 行内容」清单（只读，免审批）。
 * 供主 Agent 与「代码探索」等只读子智能体定位代码，避免逐文件 fileRead 的浪费。
 */
@Component
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 50;
    private static final long MAX_FILE_BYTES = 1_500_000;
    private static final int MAX_LINE_CHARS = 240;
    private static final List<String> SKIP_DIRS = List.of(
            "node_modules", ".git", "target", "build", "dist", ".idea", ".vscode", "__pycache__");

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "在项目（沙箱）内搜索文件内容（正则或字面量），返回 相对路径:行号:内容 清单。"
                + "查找代码/配置中的关键词、函数名、类名时优先用它，比逐个 fileRead 高效。"
                + "自动跳过 node_modules/.git/target 等目录与二进制文件，最多返回 " + MAX_RESULTS + " 条。";
    }

    @Override
    public String parametersJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "pattern": { "type": "string", "description": "搜索内容（默认按正则解释，isRegex=false 时按字面量）" },
                    "path": { "type": "string", "description": "相对项目根的搜索起点（目录或单个文件），默认根目录" },
                    "glob": { "type": "string", "description": "文件名过滤，如 *.java、*.md（可选）" },
                    "isRegex": { "type": "boolean", "description": "pattern 是否为正则，默认 true；纯文本请传 false" }
                  },
                  "required": ["pattern"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String pattern = args.get("pattern") instanceof String s && !s.isBlank() ? s : null;
        if (pattern == null) {
            return ToolResult.error("缺少 pattern 参数");
        }
        boolean isRegex = !Boolean.FALSE.equals(args.get("isRegex"));
        String glob = args.get("glob") instanceof String g && !g.isBlank() ? g : null;
        Pattern regex;
        try {
            regex = Pattern.compile(isRegex ? pattern : Pattern.quote(pattern));
        } catch (Exception e) {
            return ToolResult.error("无效的正则表达式: " + e.getMessage());
        }

        String pathArg = args.get("path") instanceof String p && !p.isBlank() ? p : ".";
        Sandbox sandbox = ctx.sandbox();
        Path base;
        try {
            base = ctx.resolve(pathArg);
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.exists(base)) {
            return ToolResult.error("路径不存在: " + pathArg);
        }

        List<String> hits = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> stream = Files.isDirectory(base) ? Files.walk(base, 8) : Stream.of(base)) {
            var it = stream.sorted().iterator();
            while (it.hasNext() && hits.size() < MAX_RESULTS) {
                Path p = it.next();
                String fileName = String.valueOf(p.getFileName());
                if (Files.isDirectory(p)) {
                    continue;
                }
                if (SKIP_DIRS.stream().anyMatch(d -> p.getParent() != null
                        && p.getParent().toString().contains(d))) {
                    continue;
                }
                if (glob != null && !matchesGlob(fileName, glob)) {
                    continue;
                }
                try {
                    if (Files.size(p) > MAX_FILE_BYTES) continue;
                    if (!isText(p)) continue;
                } catch (IOException ignore) {
                    continue;
                }
                scanned++;
                scanFile(p, base, sandbox, regex, hits);
            }
        } catch (Exception e) {
            return ToolResult.error("搜索失败: " + e.getMessage());
        }
        if (hits.isEmpty()) {
            return ToolResult.success("未找到匹配「" + pattern + "」的内容（已扫描 " + scanned + " 个文件）。"
                    + "可尝试放宽 pattern、指定 glob 或缩小 path。");
        }
        StringBuilder sb = new StringBuilder("找到 ").append(hits.size()).append(" 处匹配");
        if (hits.size() >= MAX_RESULTS) {
            sb.append("（已达上限，结果可能不完整，请缩小搜索范围）");
        }
        sb.append("：\n");
        sb.append(String.join("\n", hits));
        return ToolResult.success(sb.toString());
    }

    /** 逐行匹配并收集「相对路径:行号: 行内容」（行内容截断、去 \t）。 */
    private void scanFile(Path file, Path base, Sandbox sandbox, Pattern regex, List<String> hits) {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int no = 0;
            while ((line = reader.readLine()) != null && hits.size() < MAX_RESULTS) {
                no++;
                Matcher m = regex.matcher(line);
                if (m.find()) {
                    String text = line.length() > MAX_LINE_CHARS
                            ? line.substring(0, MAX_LINE_CHARS) + "…" : line;
                    hits.add(sandbox.relativize(file) + ":" + no + ": " + text.replace('\t', ' '));
                }
            }
        } catch (Exception ignore) {
            // 单文件读取失败（权限/编码）不影响整体搜索
        }
    }

    /** 简易 glob（仅支持 * 与 ?），如 *.java。 */
    private boolean matchesGlob(String fileName, String glob) {
        StringBuilder rx = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> rx.append(".*");
                case '?' -> rx.append('.');
                default -> rx.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return fileName.matches(rx.toString());
    }

    /** 启发式二进制判断：首块出现 NUL 字节视为二进制。 */
    private boolean isText(Path p) throws IOException {
        try (var in = Files.newInputStream(p)) {
            byte[] buf = in.readNBytes(1024);
            for (byte b : buf) {
                if (b == 0) return false;
            }
        }
        return true;
    }
}
