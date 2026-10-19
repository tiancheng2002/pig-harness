package com.example.harness.core.codeindex;

import com.example.harness.config.HarnessProperties;
import com.example.harness.embedding.EmbeddingClient;
import com.example.harness.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * P1 代码语义索引服务：把工作根内的源码切为 ≤80 行语义块并批量向量化入库。
 * - 懒构建：codeSearch 首次调用时同步全量建（有界：2000 文件 / 3000 块），增量按文件重切；
 * - 分块边界：Java/JS/TS/JSX/TSX/Python 优先在顶层符号行断开（正则级，不引 tree-sitter）；
 * - 跳过目录与 GrepTool 一致（node_modules/.git/target…）+ 隐藏目录；
 * - 无 Embedding Key 时上层工具负责报错降级，本服务不做键检查。
 */
@Component
public class CodeIndexService {

    private static final Logger log = LoggerFactory.getLogger(CodeIndexService.class);

    static final int MAX_FILES = 2000;
    static final int MAX_CHUNKS = 3000;
    private static final int TARGET_LINES = 40;
    private static final int MAX_LINES = 80;
    private static final long MAX_FILE_BYTES = 200_000;
    private static final Set<String> CODE_EXTS = Set.of(
            "java", "js", "jsx", "ts", "tsx", "py", "go", "rs", "c", "h", "cpp", "hpp",
            "cs", "rb", "php", "kt", "swift", "vue", "sql", "sh", "yml", "yaml", "toml",
            "xml", "json", "gradle", "md");
    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", ".git", "target", "build", "dist", ".idea", ".vscode",
            "__pycache__", ".validator", "attachments");
    /** 顶层符号行（优先分块边界）。 */
    private static final java.util.regex.Pattern SYMBOL_LINE = java.util.regex.Pattern.compile(
            "^(public|private|protected|static|final|abstract|class|interface|enum|record|function|def|export|import|package|async)\\b|^\\}\\s*$");

    private final CodeChunksStore store;
    private final EmbeddingClient embeddingClient;
    private final HarnessProperties props;

    public CodeIndexService(CodeChunksStore store, EmbeddingClient embeddingClient,
                            HarnessProperties props) {
        this.store = store;
        this.embeddingClient = embeddingClient;
        this.props = props;
    }

    public record IndexResult(int files, int chunks, boolean incomplete) {
    }

    /** Embedding 是否可用（无 Key 时 codeSearch 应报错指引 grep）。 */
    public boolean embeddingAvailable() {
        String key = props.getEmbedding().getApiKey();
        return key != null && !key.isBlank();
    }

    /** 工作根指纹（scope）。 */
    public String scopeKey(Sandbox sandbox) {
        return scopeKey(sandbox.root());
    }

    public String scopeKey(Path root) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(root.toAbsolutePath().normalize().toString()
                            .toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return "scope-" + Math.abs(root.toString().hashCode());
        }
    }

    /**
     * 全量构建（幂等：content_hash 相同的文件跳过）。返回本次构建统计。
     * 任一文件向量失败即中止（保已写入部分），调用方据此报错。
     */
    public synchronized IndexResult buildIndex(Sandbox sandbox) {
        Path root = sandbox.root();
        String scope = scopeKey(sandbox);
        Map<String, String> known = store.hashesByScope(scope);
        List<Path> files = new ArrayList<>();
        boolean incomplete = false;
        try (Stream<Path> stream = Files.walk(root, 10)) {
            var it = stream.sorted().iterator();
            while (it.hasNext() && files.size() < MAX_FILES) {
                Path p = it.next();
                if (Files.isDirectory(p)) continue;
                if (!isIndexable(p, root)) continue;
                files.add(p);
            }
            if (it.hasNext()) incomplete = true;
        } catch (Exception e) {
            log.warn("代码索引扫描失败 {}: {}", root, e.getMessage());
        }

        int built = 0;
        int chunkTotal = store.countByScope(scope);
        List<String> batchTexts = new ArrayList<>();
        List<CodeChunksStore.Chunk> batchChunks = new ArrayList<>();
        for (Path p : files) {
            String rel = sandbox.relativize(p).replace('\\', '/');
            try {
                String content = Files.readString(p, StandardCharsets.UTF_8);
                String hash = sha256(content);
                if (hash.equals(known.get(rel))) continue; // 无变化
                List<CodeChunksStore.Chunk> chunks = chunkFile(rel, content, hash);
                if (chunkTotal + chunks.size() > MAX_CHUNKS) {
                    incomplete = true;
                    break;
                }
                chunkTotal += chunks.size();
                built++;
                for (CodeChunksStore.Chunk c : chunks) {
                    batchTexts.add(c.content());
                    batchChunks.add(c);
                    if (batchTexts.size() >= 32) {
                        flush(scope, batchTexts, batchChunks);
                    }
                }
            } catch (Exception e) {
                log.debug("跳过无法读取的文件 {}: {}", rel, e.getMessage());
            }
        }
        if (!batchTexts.isEmpty()) {
            flush(scope, batchTexts, batchChunks);
        }
        log.info("代码索引构建完成（scope={}）：{} 个文件变更，共 {} 块{}", scope, built,
                store.countByScope(scope), incomplete ? "（达上限，索引不完整）" : "");
        return new IndexResult(built, store.countByScope(scope), incomplete);
    }

    /** 单文件增量重切（fileWrite/fileEdit/fileDelete 后调用；文件已删则清块）。 */
    public void invalidatePath(Sandbox sandbox, String relPath) {
        String scope = scopeKey(sandbox);
        String rel = relPath.replace('\\', '/');
        Path abs;
        try {
            abs = sandbox.resolve(rel);
        } catch (Exception e) {
            store.deleteByPath(scope, rel);
            return;
        }
        if (!Files.exists(abs) || !isIndexable(abs, sandbox.root())) {
            store.deleteByPath(scope, rel);
            return;
        }
        try {
            String content = Files.readString(abs, StandardCharsets.UTF_8);
            String hash = sha256(content);
            if (hash.equals(store.hashesByScope(scope).get(rel))) return; // 无变化
            List<CodeChunksStore.Chunk> chunks = chunkFile(rel, content, hash);
            embedChunks(scope, chunks);
            store.replacePath(scope, rel, chunks);
        } catch (Exception e) {
            log.warn("增量索引失败 {}: {}", rel, e.getMessage());
        }
    }

    private void flush(String scope, List<String> texts, List<CodeChunksStore.Chunk> chunks) {
        try {
            List<float[]> vectors = embeddingClient.embedBatch(new ArrayList<>(texts));
            // 按文件分组后整体替换（每个文件先删后插，保证增量语义一致）
            java.util.LinkedHashMap<String, List<CodeChunksStore.Chunk>> byPath = new java.util.LinkedHashMap<>();
            for (int i = 0; i < chunks.size(); i++) {
                CodeChunksStore.Chunk c = chunks.get(i);
                CodeChunksStore.Chunk withVec = new CodeChunksStore.Chunk(c.id(), scope, c.path(),
                        c.startLine(), c.endLine(), c.content(), vectors.get(i), c.contentHash());
                byPath.computeIfAbsent(c.path(), k -> new ArrayList<>()).add(withVec);
            }
            byPath.forEach((path, cs) -> store.replacePath(scope, path, cs));
        } finally {
            texts.clear();
            chunks.clear();
        }
    }

    /** 批量向量化 + 入库（增量路径用）。 */
    private void embedChunks(String scope, List<CodeChunksStore.Chunk> chunks) {
        List<String> texts = new ArrayList<>();
        for (CodeChunksStore.Chunk c : chunks) texts.add(c.content());
        List<float[]> vectors = embeddingClient.embedBatch(texts);
        for (int i = 0; i < chunks.size(); i++) {
            CodeChunksStore.Chunk c = chunks.get(i);
            chunks.set(i, new CodeChunksStore.Chunk(c.id(), c.scope(), c.path(), c.startLine(),
                    c.endLine(), c.content(), vectors.get(i), c.contentHash()));
        }
    }

    /** 是否值得索引的文件：扩展名白名单 + 大小上限 + 非 skip/隐藏目录。 */
    private boolean isIndexable(Path p, Path root) {
        String name = String.valueOf(p.getFileName());
        if (name.startsWith(".")) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        if (!CODE_EXTS.contains(name.substring(dot + 1).toLowerCase())) return false;
        // 路径中任一段命中 skip 目录则排除
        for (Path part : root.relativize(p)) {
            String seg = part.toString();
            if (seg.startsWith(".") || SKIP_DIRS.contains(seg)) return false;
        }
        try {
            return Files.size(p) <= MAX_FILE_BYTES;
        } catch (Exception e) {
            return false;
        }
    }

    /** 语义分块：目标 40 行、上限 80 行，优先在顶层符号行断开。 */
    List<CodeChunksStore.Chunk> chunkFile(String rel, String content, String hash) {
        String[] lines = content.split("\n", -1);
        List<CodeChunksStore.Chunk> out = new ArrayList<>();
        int start = 0;
        while (start < lines.length && lines[start].isBlank()) start++;
        while (start < lines.length) {
            int end = Math.min(lines.length, start + TARGET_LINES);
            // 在 [TARGET, MAX] 区间内找最近的顶层符号行作为边界
            for (int i = end; i < Math.min(lines.length, start + MAX_LINES); i++) {
                if (SYMBOL_LINE.matcher(lines[i].stripLeading().isEmpty() ? "" : lines[i]).find()
                        && lines[i].startsWith(lines[i].stripLeading())) { // 无缩进（顶层）
                    end = i;
                    break;
                }
            }
            // 末尾空行并入
            while (end < lines.length && lines[end].isBlank() && end - start < MAX_LINES) end++;
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) sb.append(lines[i]).append('\n');
            String block = sb.toString();
            if (!block.isBlank()) {
                out.add(new CodeChunksStore.Chunk(0, null, rel, start + 1, end, block, null, hash));
            }
            start = end;
        }
        return out;
    }

    private String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
