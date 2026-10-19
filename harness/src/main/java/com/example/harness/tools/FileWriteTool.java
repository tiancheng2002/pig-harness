package com.example.harness.tools;

import com.example.harness.sandbox.Sandbox;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * fileWrite（F6）：在沙箱内创建或覆盖写入文本文件。
 * - 越界路径（绝对路径 / ../ 逃逸）被沙箱拒绝
 * - 同名文件直接覆盖（重复执行任务可重跑；精确修改请用 fileEdit）
 */
@Component
public class FileWriteTool implements Tool {

    @Override
    public String name() {
        return "fileWrite";
    }

    @Override
    public String description() {
        return "在本地项目根（沙箱）内创建或覆盖写入文本文件，如 html/md/源码。路径默认是相对项目根的相对路径；"
                + "同名文件会直接覆盖。若只想修改已有文件的一小段，请改用 fileEdit。";
    }

    @Override
    public String parametersJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": { "type": "string", "description": "相对项目根的文件路径，例如 login.html" },
                    "content": { "type": "string", "description": "文件的完整内容" }
                  },
                  "required": ["path", "content"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        Object pathObj = args.get("path");
        Object contentObj = args.get("content");
        if (!(pathObj instanceof String path) || path.isBlank()) {
            return ToolResult.error("缺少必填参数 path");
        }
        if (!(contentObj instanceof String content)) {
            return ToolResult.error("缺少必填参数 content");
        }

        Sandbox sandbox = ctx.sandbox();
        Path target = ctx.resolve(path);   // 越界抛 SecurityException（由 Registry 转 error）；完全访问时放行绝对路径
        if (Files.isDirectory(target)) {
            return ToolResult.error("目标是一个目录: " + path);
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.error("写入失败: " + e.getMessage());
        }
        String rel = sandbox.relativize(target);
        ctx.memory().putVariable("lastFile", rel);
        ctx.memory().putVariable("lastPath", rel);
        return ToolResult.success("已写入 " + rel + " (" + content.getBytes(StandardCharsets.UTF_8).length + " 字节)", rel);
    }
}
