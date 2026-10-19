package com.example.harness.tools;

import com.example.harness.sandbox.Sandbox;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * docxWrite（F7）：把结构化内容渲染为真实 .docx（Apache POI）。
 * content 支持两种格式：
 *  1) 块数组: [{"type":"heading","text":"..","level":1},
 *              {"type":"paragraph","text":".."},
 *              {"type":"bullet","items":[".."]},
 *              {"type":"table","rows":[["h1","h2"],["a","b"]]}]
 *  2) Markdown 字符串（自动解析 #/##/###、- 列表、| 表格）
 * 同样受沙箱约束。
 */
@Component
public class DocxWriteTool implements Tool {

    private record Block(String type, String text, int level, List<String> items, List<List<String>> rows) {
    }

    @Override
    public String name() {
        return "docxWrite";
    }

    @Override
    public String description() {
        return "把结构化内容生成为 Word .docx 文档（含标题/段落/列表/表格），保存到本地项目根沙箱内。"
                + "content 可传块数组（type: heading|paragraph|bullet|table）或 Markdown 字符串。";
    }

    @Override
    public String parametersJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": { "type": "string", "description": "相对项目根的输出路径，例如 report.docx" },
                    "title": { "type": "string", "description": "文档主标题" },
                    "content": {
                      "description": "文档内容：块数组 [{type:heading|paragraph|bullet|table,...}] 或 Markdown 字符串",
                      "oneOf": [
                        { "type": "array", "items": { "type": "object" } },
                        { "type": "string" }
                      ]
                    }
                  },
                  "required": ["path", "content"]
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
        if (!target.toString().toLowerCase().endsWith(".docx")) {
            target = target.resolveSibling(target.getFileName() + ".docx");
        }

        List<Block> blocks = parseContent(args.get("content"));
        String title = args.get("title") instanceof String t && !t.isBlank() ? t : "";

        try (XWPFDocument doc = new XWPFDocument()) {
            if (!title.isBlank()) {
                XWPFParagraph p = doc.createParagraph();
                p.setAlignment(ParagraphAlignment.CENTER);
                p.setSpacingAfter(200);
                XWPFRun r = p.createRun();
                r.setText(title);
                r.setBold(true);
                r.setFontSize(20);
            }
            for (Block b : blocks) {
                render(doc, b);
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (OutputStream os = Files.newOutputStream(target)) {
                doc.write(os);
            }
        } catch (IOException e) {
            return ToolResult.error("docx 生成失败: " + e.getMessage());
        }

        String rel = sandbox.relativize(target);
        ctx.memory().putVariable("lastFile", rel);
        try {
            return ToolResult.success("已生成 " + rel + " (" + Files.size(target) + " 字节，含 "
                    + blocks.size() + " 个内容块)", rel);
        } catch (IOException e) {
            return ToolResult.success("已生成 " + rel, rel);
        }
    }

    // ---------- 内容解析 ----------

    @SuppressWarnings("unchecked")
    private List<Block> parseContent(Object content) {
        if (content instanceof List<?> list) {
            List<Block> blocks = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    blocks.add(blockOf((Map<String, Object>) m));
                }
            }
            return blocks;
        }
        if (content instanceof String md) {
            return parseMarkdown(md);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Block blockOf(Map<String, Object> m) {
        String type = String.valueOf(m.getOrDefault("type", "paragraph"));
        String text = m.get("text") instanceof String s ? s : "";
        int level = m.get("level") instanceof Number n ? n.intValue() : 1;
        List<String> items = new ArrayList<>();
        if (m.get("items") instanceof List<?> l) {
            l.forEach(i -> items.add(String.valueOf(i)));
        }
        List<List<String>> rows = new ArrayList<>();
        if (m.get("rows") instanceof List<?> rl) {
            for (Object row : rl) {
                if (row instanceof List<?> cells) {
                    List<String> r = new ArrayList<>();
                    cells.forEach(c -> r.add(String.valueOf(c)));
                    rows.add(r);
                }
            }
        }
        return new Block(type, text, level, items, rows);
    }

    /** Markdown-lite：# 标题、- 列表、| 表格、其余段落。 */
    private List<Block> parseMarkdown(String md) {
        List<Block> blocks = new ArrayList<>();
        List<String> bullets = null;
        List<List<String>> table = null;
        for (String raw : md.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                bullets = flushBullets(blocks, bullets);
                table = flushTable(blocks, table);
                continue;
            }
            if (line.startsWith("### ")) {
                bullets = flushBullets(blocks, bullets);
                table = flushTable(blocks, table);
                blocks.add(new Block("heading", line.substring(4), 3, null, null));
            } else if (line.startsWith("## ")) {
                bullets = flushBullets(blocks, bullets);
                table = flushTable(blocks, table);
                blocks.add(new Block("heading", line.substring(3), 2, null, null));
            } else if (line.startsWith("# ")) {
                bullets = flushBullets(blocks, bullets);
                table = flushTable(blocks, table);
                blocks.add(new Block("heading", line.substring(2), 1, null, null));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                table = flushTable(blocks, table);
                if (bullets == null) bullets = new ArrayList<>();
                bullets.add(line.substring(2));
            } else if (line.startsWith("|") && line.endsWith("|") && line.length() > 2) {
                bullets = flushBullets(blocks, bullets);
                String[] cells = line.substring(1, line.length() - 1).split("\\|");
                boolean separator = true;
                for (String c : cells) {
                    if (!c.replaceAll("[-:\\s]", "").isEmpty()) {
                        separator = false;
                        break;
                    }
                }
                if (separator) continue; // |---|---|
                if (table == null) table = new ArrayList<>();
                List<String> row = new ArrayList<>();
                for (String c : cells) row.add(c.trim());
                table.add(row);
            } else {
                bullets = flushBullets(blocks, bullets);
                table = flushTable(blocks, table);
                blocks.add(new Block("paragraph", line, 0, null, null));
            }
        }
        flushBullets(blocks, bullets);
        flushTable(blocks, table);
        return blocks;
    }

    private List<String> flushBullets(List<Block> blocks, List<String> bullets) {
        if (bullets != null && !bullets.isEmpty()) {
            blocks.add(new Block("bullet", null, 0, new ArrayList<>(bullets), null));
        }
        return null;
    }

    private List<List<String>> flushTable(List<Block> blocks, List<List<String>> table) {
        if (table != null && !table.isEmpty()) {
            blocks.add(new Block("table", null, 0, null, new ArrayList<>(table)));
        }
        return null;
    }

    // ---------- 渲染 ----------

    private void render(XWPFDocument doc, Block b) {
        switch (b.type()) {
            case "heading" -> {
                XWPFParagraph p = doc.createParagraph();
                p.setSpacingBefore(160);
                p.setSpacingAfter(80);
                XWPFRun r = p.createRun();
                r.setText(b.text());
                r.setBold(true);
                r.setFontSize(Math.max(12, 18 - Math.min(b.level(), 3) * 2));
            }
            case "bullet" -> {
                if (b.items() == null) return;
                for (String item : b.items()) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(360);
                    XWPFRun r = p.createRun();
                    r.setText("• " + item);
                    r.setFontSize(11);
                }
            }
            case "table" -> {
                if (b.rows() == null || b.rows().isEmpty()) return;
                XWPFTable table = doc.createTable(b.rows().size(), maxCols(b.rows()));
                table.setWidth("100%");
                for (int i = 0; i < b.rows().size(); i++) {
                    List<String> row = b.rows().get(i);
                    XWPFTableRow tr = table.getRow(i);
                    for (int j = 0; j < table.getRow(i).getTableCells().size(); j++) {
                        String v = j < row.size() ? row.get(j) : "";
                        var cell = tr.getCell(j);
                        cell.setText(v);
                        if (i == 0) {
                            cell.getParagraphs().forEach(pp ->
                                    pp.getRuns().forEach(r -> r.setBold(true)));
                        }
                    }
                }
            }
            default -> {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun();
                r.setText(b.text());
                r.setFontSize(11);
            }
        }
    }

    private int maxCols(List<List<String>> rows) {
        return rows.stream().mapToInt(List::size).max().orElse(1);
    }
}
