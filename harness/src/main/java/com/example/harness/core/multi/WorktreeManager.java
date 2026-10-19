package com.example.harness.core.multi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * P1 多 Agent git worktree 物理隔离：每个子任务在独立 worktree（系统临时目录，不污染项目）
 * 中工作，成功后 commit 并 merge 回主根；失败/取消直接清理。冲突以子任务版本覆盖并如实标注。
 * git 调用吸收 GitService 三陷阱：stdout/stderr 分离收集（CRLF 警告走 stderr）、
 * 不对整体输出 trim、--show-toplevel 与项目根做大小写不敏感比较。
 * create/merge 全程 synchronized——主根的 git index 不容忍并发写。
 */
@Component
public class WorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);

    public record MergeOutcome(boolean ok, List<String> conflicts, String error) {
    }

    /** 项目根是否为受管 git 仓库（toplevel 必须等于项目根，防父仓库误判）。
     *  git 输出为正斜杠路径（D:/a/b），须经 Path 规范化后再与项目根比较（Windows 反斜杠）。 */
    public boolean isGitRoot(Path root) {
        GitRun r = run(root, List.of("git", "rev-parse", "--show-toplevel"));
        if (!r.ok()) return false;
        String toplevel = r.stdout().strip();
        if (toplevel.isEmpty()) return false;
        try {
            Path top = Path.of(toplevel).toAbsolutePath().normalize();
            Path expected = root.toAbsolutePath().normalize();
            return top.equals(expected) || top.toString().equalsIgnoreCase(expected.toString());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 懒创建子任务 worktree（从当前 HEAD 分支——上游子任务的合并提交对下游可见）。
     * 返回 worktree 路径；失败返回 null（调用方回退共享沙箱现状）。
     */
    public synchronized Path create(Path mainRoot, String subTaskId) {
        try {
            Path base = tempBase(mainRoot);
            Files.createDirectories(base);
            String safe = subTaskId.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path wt = base.resolve(safe);
            deletePathSilently(wt); // 残留清理（上次异常退出可能遗留目录）
            GitRun r = run(mainRoot, List.of("git", "worktree", "add",
                    "-b", branchOf(subTaskId), wt.toString()));
            if (!r.ok()) {
                log.warn("创建 worktree 失败（{}）: {}", subTaskId, r.firstLine());
                return null;
            }
            log.info("子任务 {} 使用独立 worktree: {}", subTaskId, wt);
            return wt;
        } catch (Exception e) {
            log.warn("创建 worktree 异常（{}）: {}", subTaskId, e.getMessage());
            return null;
        }
    }

    /**
     * 成功路径收尾：worktree 内 add -A + commit → 主根 merge --no-ff。
     * 冲突时以 worktree（--theirs）版本覆盖并完成提交，返回冲突文件清单（空 = 无冲突）。
     */
    public synchronized MergeOutcome commitAndMerge(Path mainRoot, Path worktree, String subTaskId) {
        String branch = branchOf(subTaskId);
        GitRun add = run(worktree, List.of("git", "add", "-A"));
        if (!add.ok()) {
            return new MergeOutcome(false, List.of(), "worktree git add 失败: " + add.firstLine());
        }
        GitRun commit = run(worktree, List.of("git", "commit", "-m",
                "harness: 子任务 " + subTaskId + " 产出", "--no-verify"));
        boolean hasCommit = commit.ok();
        if (!hasCommit && !commit.stdout().contains("nothing to commit")
                && !commit.stderr().contains("nothing to commit")) {
            return new MergeOutcome(false, List.of(), "worktree commit 失败: " + commit.firstLine());
        }
        if (!hasCommit) {
            return new MergeOutcome(true, List.of(), null); // 无变更，无需合并
        }
        GitRun merge = run(mainRoot, List.of("git", "merge", "--no-ff", branch,
                "-m", "harness: 合并子任务 " + subTaskId + " 的产出", "--no-verify"));
        if (merge.ok()) {
            return new MergeOutcome(true, List.of(), null);
        }
        // 冲突：以子任务版本（--theirs）覆盖，完成提交并标注冲突清单
        List<String> conflicts = new ArrayList<>();
        for (String line : (merge.stdout() + "\n" + merge.stderr()).split("\n")) {
            if (line.startsWith("CONFLICT")) {
                String f = line.substring(line.indexOf(':') + 1).strip();
                if (!f.isEmpty()) conflicts.add(f);
            }
        }
        GitRun theirs = run(mainRoot, List.of("git", "checkout", "--theirs", "."));
        if (!theirs.ok()) {
            return new MergeOutcome(false, conflicts, "冲突解决失败: " + theirs.firstLine());
        }
        GitRun settle = run(mainRoot, List.of("git", "add", "-A"));
        if (!settle.ok()) {
            return new MergeOutcome(false, conflicts, "冲突标记失败: " + settle.firstLine());
        }
        GitRun settleCommit = run(mainRoot, List.of("git", "commit", "-m",
                "harness: 子任务 " + subTaskId + " 合并冲突以子任务版本解决", "--no-verify"));
        if (!settleCommit.ok() && !settleCommit.stdout().contains("nothing to commit")) {
            return new MergeOutcome(false, conflicts, "冲突提交失败: " + settleCommit.firstLine());
        }
        log.warn("子任务 {} 合并存在冲突（以子任务版本为准）: {}", subTaskId, conflicts);
        return new MergeOutcome(true, conflicts, null);
    }

    /** 清理：删除 worktree 与临时分支（尽力而为，忽略错误）。 */
    public synchronized void cleanup(Path mainRoot, String subTaskId) {
        String safe = subTaskId.replaceAll("[^a-zA-Z0-9_-]", "_");
        try {
            Path base = tempBase(mainRoot);
            run(mainRoot, List.of("git", "worktree", "remove", "--force",
                    base.resolve(safe).toString()));
        } catch (Exception ignore) {
            // 尽力清理
        }
        run(mainRoot, List.of("git", "branch", "-D", branchOf(subTaskId)));
    }

    private static String branchOf(String subTaskId) {
        return "harness/" + subTaskId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /** worktree 存放根：<系统临时目录>/harness-worktrees/<repo 指纹>/ ——不进项目目录，零 git status 污染。 */
    private Path tempBase(Path mainRoot) {
        String repo = mainRoot.toAbsolutePath().normalize().toString().toLowerCase();
        byte[] h;
        try {
            h = MessageDigest.getInstance("SHA-256").digest(repo.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            h = repo.getBytes(StandardCharsets.UTF_8);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, h.length); i++) sb.append(String.format("%02x", h[i]));
        return Path.of(System.getProperty("java.io.tmpdir"), "harness-worktrees", sb.toString());
    }

    private record GitRun(boolean ok, String stdout, String stderr) {

        String firstLine() {
            String s = (stdout + " " + stderr).strip();
            int nl = s.indexOf('\n');
            return nl > 0 ? s.substring(0, nl) : s;
        }
    }

    /** git 命令执行：stdout/stderr 并行排空（虚拟线程）防管道写满死锁，分离收集。 */
    private GitRun run(Path cwd, List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            var outFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()));
            var errFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> readAll(p.getErrorStream()));
            boolean done = p.waitFor(60, TimeUnit.SECONDS);
            String out = outFuture.join();
            String err = errFuture.join();
            if (!done) {
                p.destroyForcibly();
                return new GitRun(false, out, "git 命令超时");
            }
            return new GitRun(p.exitValue() == 0, out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitRun(false, "", "被中断");
        } catch (IOException e) {
            return new GitRun(false, "", e.getMessage());
        }
    }

    private static String readAll(java.io.InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private void deletePathSilently(Path p) {
        if (!Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (Exception ignore) {
                    // 尽力删除
                }
            });
        } catch (Exception ignore) {
            // 尽力删除
        }
    }
}
