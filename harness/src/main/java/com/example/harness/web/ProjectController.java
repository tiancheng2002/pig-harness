package com.example.harness.web;

import com.example.harness.core.git.GitService;
import com.example.harness.core.memory.ProjectStore;
import com.example.harness.model.GitStatus;
import com.example.harness.model.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 项目模式 API：
 * - 项目 CRUD（绑定本地文件夹）
 * - 项目内文件联想（输入框 @ 引用）
 * - 项目 Git 状态 / 单文件 diff / 提交 / 推送（悬浮面板数据源）
 */
@RestController
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    public record CreateRequest(String name, String rootPath) {
    }

    public record CommitRequest(String message) {
    }

    /** 项目内文件命中项（@ 引用）：path 为相对项目根、/ 分隔。 */
    public record FileHit(String name, String path) {
    }

    private final ProjectStore store;
    private final GitService git;

    public ProjectController(ProjectStore store, GitService git) {
        this.store = store;
        this.git = git;
    }

    @GetMapping("/api/projects")
    public List<Map<String, Object>> list() {
        return store.list();
    }

    @PostMapping("/api/projects")
    public Project create(@RequestBody CreateRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        String rootPath = req.rootPath() == null ? "" : req.rootPath().trim();
        if (rootPath.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rootPath（本地文件夹路径）不能为空");
        }
        if (name.isEmpty()) {
            name = Paths.get(rootPath).getFileName() != null
                    ? Paths.get(rootPath).getFileName().toString() : "项目";
        }
        Path root;
        try {
            root = Paths.get(rootPath).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路径不合法: " + rootPath);
        }
        if (!Files.isDirectory(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件夹不存在: " + root);
        }
        Project p = store.create(name, root.toString());
        log.info("[project:{}] 绑定项目 {} → {}", p.id(), p.name(), p.rootPath());
        return p;
    }

    @DeleteMapping("/api/projects/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        if (!store.delete(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ---------- 文件搜索（@ 引用） ----------

    /** 遍历深度 / 返回条数 / 候选池与访问文件数上限（防病态大仓拖慢输入框联想）。 */
    private static final int MAX_DEPTH = 16;
    private static final int MAX_RESULTS = 10;
    private static final int MAX_CANDIDATES = 300;
    private static final int MAX_VISITED_FILES = 20000;

    /** 联想时跳过的重目录（构建产物 / 依赖 / 版本库 / IDE 元数据）。 */
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "bower_components", "vendor", "Pods",
            "target", "build", "dist", "out", ".idea", ".vscode", ".next", ".nuxt",
            "coverage", "__pycache__", ".venv", "venv", "env", ".gradle", ".mvn",
            "bin", "obj", ".cache", ".pytest_cache", ".terraform", "logs", "tmp");

    /**
     * 项目内文件联想（前端输入框 @ 引用）：按名称/相对路径模糊匹配，最多返回 10 条。
     * 排序：文件名前缀命中 → 文件名包含 → 仅路径包含，同级按相对路径升序；q 为空即「从前到后」。
     */
    @GetMapping("/api/projects/{id}/files")
    public Map<String, Object> files(@PathVariable String id,
                                     @RequestParam(defaultValue = "") String q) {
        Project p = require(id);
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Path root = Path.of(p.rootPath());
        List<Path> matched = new ArrayList<>();
        boolean[] truncated = {false};
        int[] visited = {0};
        try {
            // 不跟随符号链接（EMPTY 套路避免循环），访问文件数超限即终止
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root)
                            && IGNORED_DIRS.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visited[0] > MAX_VISITED_FILES) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = file.getFileName().toString();
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (query.isEmpty()
                            || name.toLowerCase(Locale.ROOT).contains(query)
                            || rel.toLowerCase(Locale.ROOT).contains(query)) {
                        if (matched.size() >= MAX_CANDIDATES) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        matched.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // 无权限/被占用的文件直接跳过
                }
            });
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "遍历项目文件失败: " + e.getMessage());
        }
        record Candidate(String name, String path, int rank) {
        }
        List<FileHit> hits = matched.stream()
                .map(f -> {
                    String name = f.getFileName().toString();
                    String lowerName = name.toLowerCase(Locale.ROOT);
                    String rel = root.relativize(f).toString().replace('\\', '/');
                    int rank = lowerName.startsWith(query) ? 0
                            : lowerName.contains(query) ? 1 : 2;
                    return new Candidate(name, rel, rank);
                })
                .sorted(Comparator.<Candidate>comparingInt(c -> c.rank())
                        .thenComparing(c -> c.path(), String.CASE_INSENSITIVE_ORDER))
                .limit(MAX_RESULTS)
                .map(c -> new FileHit(c.name(), c.path()))
                .toList();
        boolean more = truncated[0] || matched.size() > MAX_RESULTS;
        return Map.of("files", hits, "truncated", more);
    }

    // ---------- Git ----------

    @GetMapping("/api/projects/{id}/git")
    public GitStatus gitStatus(@PathVariable String id) {
        Project p = require(id);
        return git.status(Path.of(p.rootPath()));
    }

    @GetMapping("/api/projects/{id}/git/diff")
    public Map<String, Object> gitDiff(@PathVariable String id, @RequestParam String path) {
        Project p = require(id);
        String diff = git.diff(Path.of(p.rootPath()), path);
        return Map.of("path", path, "diff", diff);
    }

    @PostMapping("/api/projects/{id}/git/commit")
    public Map<String, Object> gitCommit(@PathVariable String id, @RequestBody CommitRequest req) {
        Project p = require(id);
        String message = req.message() == null ? "" : req.message().trim();
        if (message.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提交信息不能为空");
        }
        String[] result = git.commit(Path.of(p.rootPath()), message);
        log.info("[project:{}] 提交: {}", id, result[1]);
        return Map.of("ok", Boolean.parseBoolean(result[0]), "message", result[1]);
    }

    @PostMapping("/api/projects/{id}/git/push")
    public Map<String, Object> gitPush(@PathVariable String id) {
        Project p = require(id);
        String[] result = git.push(Path.of(p.rootPath()));
        return Map.of("ok", Boolean.parseBoolean(result[0]), "message", result[1]);
    }

    private Project require(String id) {
        return store.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在: " + id));
    }
}
