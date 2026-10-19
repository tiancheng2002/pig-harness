package com.example.harness.tools;

import com.example.harness.sandbox.Sandbox;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * fileDelete：删除沙箱内文件或空目录（目录非空时拒绝，防误删）。
 */
@Component
public class FileDeleteTool implements Tool {

    @Override
    public String name() {
        return "fileDelete";
    }

    @Override
    public String description() {
        return "删除本地项目根（沙箱）内的文件或空目录。路径默认是相对项目根的相对路径；"
                + "非空目录会被拒绝（防止误删整棵子树）。";
    }

    @Override
    public String parametersJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": { "type": "string", "description": "相对项目根的文件/目录路径" }
                  },
                  "required": ["path"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        Object pathObj = args.get("path");
        if (!(pathObj instanceof String path) || path.isBlank()) {
            return ToolResult.error("缺少必填参数 path");
        }
        Sandbox sandbox = ctx.sandbox();
        Path target = ctx.resolve(path);
        if (!Files.exists(target)) {
            return ToolResult.error("路径不存在: " + path);
        }
        try {
            Files.delete(target);
        } catch (java.nio.file.DirectoryNotEmptyException e) {
            return ToolResult.error("目录非空，拒绝删除: " + sandbox.relativize(target)
                    + "（如需清空请逐个 fileDelete）");
        } catch (Exception e) {
            return ToolResult.error("删除失败: " + e.getMessage());
        }
        return ToolResult.success("已删除 " + sandbox.relativize(target));
    }
}
