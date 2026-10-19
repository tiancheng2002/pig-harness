package com.example.harness.tools;

import com.example.harness.sandbox.Sandbox;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * fileEdit：在沙箱内对已有文本文件做精确的最小替换（类 Claude Code 的 Edit 工具）。
 * old_string 必须与文件内容逐字符一致（含缩进与换行）；多处匹配时需声明 replace_all 或扩大上下文。
 */
@Component
public class FileEditTool implements Tool {

    @Override
    public String name() {
        return "fileEdit";
    }

    @Override
    public String description() {
        return "精确修改本地项目根（沙箱）内已有的文本文件：用 new_string 替换 old_string。"
                + "old_string 必须与文件内容逐字符一致（含缩进与换行），修改前建议先 fileRead 拿到原文。"
                + "这是修改文件的首选方式，不要为改一小段而整份重写。";
    }

    @Override
    public String parametersJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": { "type": "string", "description": "相对项目根的文件路径" },
                    "old_string": { "type": "string", "description": "要被替换的原文片段（逐字符一致，含缩进换行）" },
                    "new_string": { "type": "string", "description": "替换后的新片段；与 old_string 相同则报错" },
                    "replace_all": { "type": "boolean", "description": "old_string 出现多次时是否全部替换，默认 false（仅唯一匹配时替换）" }
                  },
                  "required": ["path", "old_string", "new_string"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        Object pathObj = args.get("path");
        if (!(pathObj instanceof String path) || path.isBlank()) {
            return ToolResult.error("缺少必填参数 path");
        }
        if (!(args.get("old_string") instanceof String oldStr) || oldStr.isEmpty()) {
            return ToolResult.error("缺少必填参数 old_string");
        }
        if (!(args.get("new_string") instanceof String newStr)) {
            return ToolResult.error("缺少必填参数 new_string");
        }
        boolean replaceAll = Boolean.TRUE.equals(args.get("replace_all"));
        if (oldStr.equals(newStr)) {
            return ToolResult.error("old_string 与 new_string 相同，无需修改");
        }

        Sandbox sandbox = ctx.sandbox();
        Path target = ctx.resolve(path);
        if (!Files.exists(target)) {
            return ToolResult.error("文件不存在: " + path + "（新建文件请用 fileWrite）");
        }
        if (Files.isDirectory(target)) {
            return ToolResult.error("目标是一个目录: " + path);
        }

        String content;
        try {
            content = Files.readString(target, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return ToolResult.error("读取失败（可能是二进制文件）: " + e.getMessage());
        }

        // 行尾自适应：fileRead 展示的内容不含 \r，而 Windows 项目（autocrlf）文件实际是 CRLF——
        // 模型按 \n 构造的 old_string 会匹配失败。先精确匹配，未命中且文件为 CRLF 时把
        // old/new 的换行统一为 \r\n 重试（新写入的行也遵循文件现有行尾）。
        String eolAware = "";
        int occurrences = countOccurrences(content, oldStr);
        String oldEffective = oldStr;
        String newEffective = newStr;
        if (occurrences == 0 && oldStr.contains("\n") && content.contains("\r\n")) {
            oldEffective = oldStr.replace("\r\n", "\n").replace("\n", "\r\n");
            newEffective = newStr.replace("\r\n", "\n").replace("\n", "\r\n");
            occurrences = countOccurrences(content, oldEffective);
            if (occurrences > 0) eolAware = "（已自动适配 CRLF 行尾）";
        }
        if (occurrences == 0) {
            return ToolResult.error("old_string 在文件中未找到。请先 fileRead 获取原文，"
                    + "确保逐字符一致（注意缩进与换行）。");
        }
        if (occurrences > 1 && !replaceAll) {
            return ToolResult.error("old_string 出现 " + occurrences + " 处。"
                    + "请扩大上下文使其唯一，或设置 replace_all=true 全部替换。");
        }

        String updated = replaceAll
                ? content.replace(oldEffective, newEffective)
                : content.replaceFirst(java.util.regex.Pattern.quote(oldEffective),
                        java.util.regex.Matcher.quoteReplacement(newEffective));
        try {
            Files.writeString(target, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.error("写入失败: " + e.getMessage());
        }

        String rel = sandbox.relativize(target);
        ctx.memory().putVariable("lastFile", rel);
        ctx.memory().putVariable("lastPath", rel);
        return ToolResult.success("已替换 " + occurrences + " 处" + eolAware + "，"
                + rel + " 现为 " + updated.getBytes(StandardCharsets.UTF_8).length + " 字节", rel);
    }

    private int countOccurrences(String content, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
