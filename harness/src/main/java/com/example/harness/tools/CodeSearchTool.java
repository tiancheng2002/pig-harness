package com.example.harness.tools;

import com.example.harness.core.codeindex.CodeChunksStore;
import com.example.harness.core.codeindex.CodeIndexService;
import com.example.harness.embedding.EmbeddingClient;
import com.example.harness.embedding.EmbeddingIndex;
import com.example.harness.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * P1 codeSearch：代码语义检索（只读，免审批）。
 * 在项目语义索引（CodeIndexService 懒构建 + 增量维护）中按自然语言查询定位代码块，
 * 返回 path:startLine-endLine + 相似度 + 首两行预览；大项目先语义检索再 fileRead/grep 精读，
 * 替代盲目 listFiles。无 Embedding Key 时明确报错指引改用 grep（不静默空结果）。
 */
@Component
public class CodeSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CodeSearchTool.class);
    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_TOP_K = 20;

    private final CodeIndexService indexService;
    private final CodeChunksStore store;
    private final EmbeddingClient embeddingClient;

    public CodeSearchTool(CodeIndexService indexService, CodeChunksStore store,
                          EmbeddingClient embeddingClient) {
        this.indexService = indexService;
        this.store = store;
        this.embeddingClient = embeddingClient;
    }

    @Override
    public String name() {
        return "codeSearch";
    }

    @Override
    public String description() {
        return "按自然语言语义检索项目代码（如「用户登录的校验逻辑在哪」「处理支付的类」），"
                + "返回 path:起止行 + 相似度 + 代码预览。比 grep 更适合不知道确切关键词时定位代码；"
                + "首次调用会自动构建项目索引（稍慢），之后增量维护。命中后用 fileRead 精读上下文。";
    }

    @Override
    public String parametersJson() {
        return """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"自然语言查询（描述要找的逻辑/功能，而非精确关键词）"},
                  "path_prefix":{"type":"string","description":"限定检索的目录前缀（相对项目根，如 src/main/java），可选"},
                  "top_k":{"type":"integer","description":"返回条数，默认 8，上限 20"}
                },"required":["query"]}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String query = args.get("query") instanceof String s && !s.isBlank() ? s.trim() : null;
        if (query == null) {
            return ToolResult.error("缺少必填参数 query");
        }
        if (!indexService.embeddingAvailable()) {
            return ToolResult.error("codeSearch 需要 Embedding 能力（当前未配置 harness.embedding.api-key）。"
                    + "请改用 grep 工具按关键词搜索，或在配置中补充 Embedding 端点后重试。");
        }
        Sandbox sandbox = ctx.sandbox();
        String scope = indexService.scopeKey(sandbox);
        int topK = args.get("top_k") instanceof Number n && n.intValue() > 0
                ? Math.min(n.intValue(), MAX_TOP_K) : DEFAULT_TOP_K;
        String prefix = args.get("path_prefix") instanceof String p && !p.isBlank()
                ? p.replace('\\', '/').trim() : null;

        // 懒构建：空索引导全量建（同步有界）；重复调用零成本（hash 去重）
        int total = store.countByScope(scope);
        CodeIndexService.IndexResult built = null;
        if (total == 0) {
            built = indexService.buildIndex(sandbox);
            total = built.chunks();
        }
        if (total == 0) {
            return ToolResult.success("项目内没有可索引的源码文件（支持 java/js/ts/py/go 等代码与配置格式）。");
        }

        float[] qv;
        try {
            qv = embeddingClient.embed(query);
        } catch (Exception e) {
            return ToolResult.error("查询向量化失败: " + e.getMessage());
        }

        List<CodeChunksStore.Chunk> chunks = store.listByScope(scope);
        record Hit(CodeChunksStore.Chunk c, double score) {
        }
        List<Hit> hits = new java.util.ArrayList<>();
        for (CodeChunksStore.Chunk c : chunks) {
            if (prefix != null && !c.path().startsWith(prefix)) continue;
            double score = EmbeddingIndex.cosine(qv, c.vector());
            if (score > 0.30) { // 过滤明显无关块
                hits.add(new Hit(c, score));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());

        StringBuilder sb = new StringBuilder();
        if (built != null && built.incomplete()) {
            sb.append("（提示：项目较大，索引达上限未完整覆盖，建议用 path_prefix 缩小范围）\n");
        }
        if (hits.isEmpty()) {
            sb.append("语义检索无高相关结果（索引 ").append(total).append(" 块）。")
                    .append("可改用 grep 精确关键词搜索，或换一种描述再试。");
            return ToolResult.success(sb.toString());
        }
        sb.append("语义检索 Top").append(Math.min(hits.size(), topK)).append("（共索引 ")
                .append(total).append(" 块）：\n");
        for (int i = 0; i < hits.size() && i < topK; i++) {
            CodeChunksStore.Chunk c = hits.get(i).c();
            sb.append(String.format("%d. %s:%d-%d（相关度 %.2f）%n", i + 1, c.path(),
                    c.startLine(), c.endLine(), hits.get(i).score()));
            sb.append("   ").append(preview(c.content())).append('\n');
        }
        sb.append("请用 fileRead 读取命中的文件区间确认上下文后再修改。");
        return ToolResult.success(sb.toString());
    }

    /** 块内前两个非空行拼接为预览（截 120 字符）。 */
    private String preview(String content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
            if (sb.length() >= 120) break;
        }
        String s = sb.toString();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
