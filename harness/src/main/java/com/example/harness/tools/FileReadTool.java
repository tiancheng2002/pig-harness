package com.example.harness.tools;

import com.example.harness.sandbox.Sandbox;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * fileRead（F6）：读取沙箱内文本文件，内容回灌 LLM 上下文。
 * 支持按行分页（offset/limit），大文件可分段读取；二进制文件给出友好错误。
 */
@Component
public class FileReadTool implements Tool {

    /** 单次返回的字符上限（超出按行截断并提示续读） */
    private static final int MAX_CHARS = 16000;
    /** 默认每次读取的行数 */
    private static final int DEFAULT_LIMIT = 400;
    private static final long MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_BATCH_FILES = 8;
    private static final int MAX_BATCH_CHARS = 32_000;

    @Override
    public String name() {
        return "fileRead";
    }

    @Override
    public String description() {
        return "读取本地项目根（沙箱）内的文本文件内容。路径默认是相对项目根的相对路径。"
                + "支持 offset（起始行，从 1 开始）与 limit（行数）分页读取大文件；"
                + "不传时默认返回前 " + DEFAULT_LIMIT + " 行。"
                + "已知多个相关文件时可传 paths 数组一次读取，最多 " + MAX_BATCH_FILES + " 个，减少往返。";
    }

    @Override
    public String parametersJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": { "type": "string", "description": "相对项目根的文件路径" },
                    "paths": { "type": "array", "items": { "type": "string" }, "maxItems": %d,
                               "description": "批量读取的相对路径；与 path 二选一" },
                    "offset": { "type": "integer", "description": "起始行号（从 1 开始），默认 1" },
                    "limit": { "type": "integer", "description": "本次读取的行数，默认 %d" }
                  }
                }
                """.formatted(MAX_BATCH_FILES, DEFAULT_LIMIT);
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        Object pathObj = args.get("path");
        Object pathsObj = args.get("paths");
        if (!(pathObj instanceof String) && pathsObj instanceof List<?> paths) {
            return readBatch(paths, args, ctx);
        }
        if (!(pathObj instanceof String path) || path.isBlank()) {
            return ToolResult.error("缺少 path，或提供非空 paths 数组");
        }
        int offset = args.get("offset") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        int limit = args.get("limit") instanceof Number n ? Math.max(1, n.intValue()) : DEFAULT_LIMIT;

        Sandbox sandbox = ctx.sandbox();
        Path target = ctx.resolve(path);
        if (!Files.exists(target)) {
            return ToolResult.error("文件不存在: " + path);
        }
        if (!Files.isRegularFile(target)) {
            return ToolResult.error("不是普通文件: " + path);
        }
        try {
            if (Files.size(target) > MAX_BYTES) {
                return ToolResult.error("文件过大（>8MB），拒绝读取: " + path);
            }
            if (isBinary(target)) {
                return ToolResult.error("这是二进制文件，无法按文本读取: " + sandbox.relativize(target));
            }
            List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
            int total = lines.size();
            int from = Math.min(offset - 1, total);
            int to = Math.min(from + limit, total);

            StringBuilder body = new StringBuilder();
            int used = 0;
            boolean charTruncated = false;
            for (int i = from; i < to; i++) {
                String line = lines.get(i);
                if (used + line.length() + 1 > MAX_CHARS) {
                    charTruncated = true;
                    to = i;
                    break;
                }
                body.append(line).append('\n');
                used += line.length() + 1;
            }
            if (body.isEmpty() && total > 0) {
                return ToolResult.error("读取范围为空（文件共 " + total + " 行，请求起始行 " + offset + "）");
            }
            String header = "读取成功（" + sandbox.relativize(target) + "）：共 " + total + " 行，"
                    + "返回第 " + (from + 1) + "–" + to + " 行";
            String tail = to < total || charTruncated
                    ? "\n...[未完，可用 offset=" + (to + 1) + " 继续读取]"
                    : "";
            return ToolResult.success(header + "：\n" + body + tail);
        } catch (IOException e) {
            return ToolResult.error("读取失败: " + e.getMessage());
        }
    }

    private ToolResult readBatch(List<?> rawPaths, Map<String, Object> args, ToolContext ctx) {
        List<String> paths = rawPaths.stream().filter(String.class::isInstance)
                .map(String.class::cast).filter(s -> !s.isBlank()).distinct().toList();
        if (paths.isEmpty()) return ToolResult.error("paths 不能为空");
        if (paths.size() > MAX_BATCH_FILES) {
            return ToolResult.error("单次最多批量读取 " + MAX_BATCH_FILES + " 个文件，请按相关性分组");
        }
        StringBuilder combined = new StringBuilder("批量读取 " + paths.size() + " 个文件：\n");
        boolean allOk = true;
        for (String path : paths) {
            Map<String,Object> one = new java.util.HashMap<>(args);
            one.remove("paths");
            one.put("path", path);
            ToolResult result = execute(one, ctx);
            allOk &= result.ok();
            String section = "\n===== " + path.replace('\\', '/') + " =====\n" + result.output() + "\n";
            if (combined.length() + section.length() > MAX_BATCH_CHARS) {
                combined.append("\n...[批量输出已达 ").append(MAX_BATCH_CHARS)
                        .append(" 字符，请缩小 paths 或用 offset 续读]");
                break;
            }
            combined.append(section);
        }
        return allOk ? ToolResult.success(combined.toString()) : ToolResult.error(combined.toString());
    }

    /** 前 8KB 出现 NUL 字节即判定为二进制。 */
    private boolean isBinary(Path target) throws IOException {
        try (InputStream in = Files.newInputStream(target)) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) return true;
            }
        }
        return false;
    }
}
