package com.example.harness.tools;

import com.example.harness.sandbox.Sandbox;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * listFiles：浏览沙箱内目录结构，让 Agent 能发现已有文件而不是凭空猜路径。
 */
@Component
public class ListFilesTool implements Tool {

    private static final int MAX_ENTRIES = 200;
    private static final int MAX_DEPTH = 4;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "target", "node_modules", "build", "dist", ".idea", ".vscode", "__pycache__");

    @Override
    public String name() {
        return "listFiles";
    }

    @Override
    public String description() {
        return "列出本地项目根（沙箱）内的文件与目录（相对路径 + 大小）。"
                + "dir 默认为根目录，recursive=true 时递归子目录（最深 " + MAX_DEPTH + " 层）。"
                + "默认跳过 .git/target/node_modules/build/dist 等生成目录。"
                + "在创建或修改文件前，先用它了解沙箱里已有哪些文件。";
    }

    @Override
    public String parametersJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "dir": { "type": "string", "description": "相对项目根的目录路径，默认根目录" },
                    "recursive": { "type": "boolean", "description": "是否递归子目录，默认 false" }
                  },
                  "required": []
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String dir = args.get("dir") instanceof String d && !d.isBlank() ? d : ".";
        boolean recursive = Boolean.TRUE.equals(args.get("recursive"));

        Sandbox sandbox = ctx.sandbox();
        Path base = ctx.resolve(dir);
        if (!Files.exists(base)) {
            return ToolResult.error("目录不存在: " + dir + "（可先用默认根目录浏览）");
        }
        if (!Files.isDirectory(base)) {
            return ToolResult.error("不是目录: " + dir);
        }

        List<String> entries = new ArrayList<>();
        int depth = recursive ? MAX_DEPTH : 1;
        try {
            Files.walkFileTree(base, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), depth,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(base) && SKIP_DIRS.contains(String.valueOf(dir.getFileName()))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (!dir.equals(base)) entries.add(displayPath(sandbox.relativize(dir)) + "/");
                            return entries.size() >= MAX_ENTRIES
                                    ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String rel = displayPath(sandbox.relativize(file));
                            entries.add(rel + " (" + humanSize(attrs.size()) + ")");
                            return entries.size() >= MAX_ENTRIES
                                    ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }
                    });
            entries.sort(Comparator.comparing(String::toLowerCase));
        } catch (IOException e) {
            return ToolResult.error("列目录失败: " + e.getMessage());
        }

        if (entries.isEmpty()) {
            return ToolResult.success("目录为空: " + sandbox.relativize(base));
        }
        String note = entries.size() >= MAX_ENTRIES
                ? "\n...[已达 " + MAX_ENTRIES + " 条上限，请缩小范围（指定 dir 或关闭 recursive）]" : "";
        return ToolResult.success("沙箱内容（" + entries.size() + " 项）：\n"
                + String.join("\n", entries) + note);
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private String displayPath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
