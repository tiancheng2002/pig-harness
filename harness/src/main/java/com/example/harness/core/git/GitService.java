package com.example.harness.core.git;

import com.example.harness.model.GitStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 项目 Git 集成（只依赖本机 git 命令行，无需 JGit 依赖）：
 * - status：分支 / ahead-behind / 变更文件清单（含每文件增删行数）
 * - diff：单文件 diff（未跟踪文件输出合成的新增 diff）
 * - commit：git add -A + commit（未配置身份时自动以 harness 本地身份兜底）
 * - push：git push（无 upstream 时自动 -u origin <branch>）
 */
@Component
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);
    private static final int TIMEOUT_SECONDS = 30;
    /** diff 单文件最多返回的行数（防超大 diff 撑爆前端） */
    private static final int DIFF_MAX_LINES = 600;

    /** 在项目根执行 git 命令。stdout/stderr 分离收集（git 的 LF/CRLF 警告走 stderr，混流会污染解析）。 */
    private Result run(Path root, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("-c");
            cmd.add("core.quotepath=false");
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            Process p = pb.start();
            Thread errReader = Thread.ofVirtual().start(() -> {
                try (var es = p.getErrorStream()) {
                    es.readAllBytes();
                } catch (IOException ignored) {
                }
            });
            String out;
            try (var is = p.getInputStream()) {
                out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            try {
                errReader.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new Result(-1, "git 命令超时（" + TIMEOUT_SECONDS + "s）");
            }
            // 注意：不要整体 trim——porcelain 首行的前导空格是未暂存状态位，裁掉会错位解析
            return new Result(p.exitValue(), out);
        } catch (IOException e) {
            return new Result(-1, "无法执行 git 命令（请确认本机已安装 git 并在 PATH 中）: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "git 命令被中断");
        }
    }

    /** 全量状态：分支 + ahead/behind + 文件级变更与增删行数统计。 */
    public GitStatus status(Path root) {
        Result inside = run(root, "rev-parse", "--is-inside-work-tree");
        if (inside.exit != 0 || !"true".equals(inside.out.strip())) {
            return GitStatus.notRepo("该文件夹不是 Git 仓库（可在终端执行 git init 后使用提交/推送）");
        }
        // 必须是独立的仓库根：位于上级仓库的子目录不算（否则 add/commit 会误操作父仓库）
        Result top = run(root, "rev-parse", "--show-toplevel");
        if (top.exit != 0 || !sameDir(top.out, root)) {
            return GitStatus.notRepo("该文件夹不是独立的 Git 仓库根（它属于上级仓库 "
                    + (top.exit == 0 ? top.out.strip() : "?") + "）");
        }

        Result branchRes = run(root, "rev-parse", "--abbrev-ref", "HEAD");
        String branch = branchRes.exit == 0 ? branchRes.out.strip() : "";
        int[] ab = aheadBehind(root);
        List<GitStatus.Change> changes = new ArrayList<>();

        // 已跟踪文件：相对 HEAD 的增删行数（暂存+未暂存合并视图）
        Map<String, int[]> numstat = new HashMap<>();
        Result ns = run(root, "diff", "HEAD", "--numstat");
        if (ns.exit == 0) {
            for (String line : ns.out.split("\n")) {
                String[] parts = line.strip().split("\t");
                if (parts.length >= 3) {
                    int add = "-".equals(parts[0]) ? 0 : parseIntSafe(parts[0]);
                    int del = "-".equals(parts[1]) ? 0 : parseIntSafe(parts[1]);
                    numstat.put(parts[2], new int[]{add, del});
                }
            }
        }

        // porcelain -uall：逐个列出未跟踪文件（含中文路径原样输出）
        Result st = run(root, "status", "--porcelain=v1", "-uall");
        int totalAdd = 0, totalDel = 0;
        if (st.exit == 0 && !st.out.isBlank()) {
            for (String line : st.out.split("\n")) {
                // 格式：XY<space><path>；不匹配的行（如空行/杂音）跳过
                if (line.length() < 4 || line.charAt(2) != ' ') continue;
                String code = line.substring(0, 2).strip();
                String path = normalize(line.substring(3).strip());
                if (path.contains(" -> ")) {
                    String[] seg = path.split(" -> ", 2);
                    path = seg[1];
                }
                boolean untracked = "??".equals(code) || "?".equals(code);
                int add;
                int del;
                if (untracked) {
                    int[] cnt = countUntrackedLines(root.resolve(path));
                    add = cnt[0];
                    del = cnt[1];
                } else {
                    int[] n = numstat.getOrDefault(path, new int[]{0, 0});
                    add = n[0];
                    del = n[1];
                }
                totalAdd += add;
                totalDel += del;
                changes.add(new GitStatus.Change(path, code, labelOf(code), add, del));
            }
        }
        return new GitStatus(true, branch, ab[0], ab[1], changes, totalAdd, totalDel, null);
    }

    /** 单文件 diff（未跟踪文件合成新增 diff）。 */
    public String diff(Path root, String path) {
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root)) return "（非法路径）";
        Result st = run(root, "status", "--porcelain=v1", "--", path);
        boolean untracked = st.exit == 0 && st.out.startsWith("??");
        if (untracked) {
            int[] cnt = countUntrackedLines(file);
            if (cnt[0] < 0) return "（二进制文件，不显示 diff）";
            StringBuilder sb = new StringBuilder("--- /dev/null\n+++ b/").append(path).append('\n');
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int shown = 0;
                for (String l : lines) {
                    if (shown++ >= DIFF_MAX_LINES) {
                        sb.append("…（其余 ").append(lines.size() - DIFF_MAX_LINES).append(" 行省略）\n");
                        break;
                    }
                    sb.append('+').append(l).append('\n');
                }
            } catch (IOException e) {
                return "（读取文件失败: " + e.getMessage() + "）";
            }
            return sb.toString();
        }
        Result d = run(root, "diff", "HEAD", "--", path);
        if (d.exit != 0) return d.out.isBlank() ? "（无变更或获取失败）" : d.out.strip();
        String[] lines = d.out.strip().split("\n", -1);
        if (lines.length <= DIFF_MAX_LINES) return d.out;
        return String.join("\n", java.util.Arrays.copyOfRange(lines, 0, DIFF_MAX_LINES))
                + "\n…（其余 " + (lines.length - DIFF_MAX_LINES) + " 行省略）";
    }

    /** 暂存全部变更并提交。返回 [成功?("true"/"false"), 展示信息]。 */
    public String[] commit(Path root, String message) {
        Result add = run(root, "add", "-A");
        if (add.exit != 0) return new String[]{"false", "git add 失败: " + add.out};
        Result c = run(root, "commit", "-m", message);
        if (c.exit != 0) {
            String err = c.out.toLowerCase();
            if (err.contains("please tell me who you are") || err.contains("user.name")) {
                // 本机未配置 git 身份：用本地兜底身份重试（仅本次命令生效，不改全局配置）
                c = run(root, "-c", "user.name=harness", "-c", "user.email=harness@local",
                        "commit", "-m", message);
            }
            if (c.exit != 0) return new String[]{"false", "提交失败: " + tail(c.out, 400)};
        }
        Result hash = run(root, "rev-parse", "--short", "HEAD");
        String shortHash = hash.exit == 0 ? hash.out.strip() : "";
        return new String[]{"true", "已提交 " + shortHash + "：" + message};
    }

    /** 推送当前分支；无 upstream 时自动关联 origin。 */
    public String[] push(Path root) {
        Result p = run(root, "push");
        if (p.exit == 0) return new String[]{"true", "推送成功\n" + tail(p.out, 500)};
        if (p.out.contains("no upstream") || p.out.contains("set the remote as upstream")
                || p.out.contains("has no upstream branch")) {
            Result br = run(root, "rev-parse", "--abbrev-ref", "HEAD");
            String branch = br.exit == 0 ? br.out.strip() : "main";
            Result up = run(root, "push", "-u", "origin", branch);
            if (up.exit == 0) return new String[]{"true", "推送成功（已关联 origin/" + branch + "）\n" + tail(up.out, 500)};
            return new String[]{"false", "推送失败: " + tail(up.out, 500)};
        }
        return new String[]{"false", "推送失败: " + tail(p.out, 500)};
    }

    // ---------- 内部工具 ----------

    private int[] aheadBehind(Path root) {
        // git status -sb 第一行：## main...origin/main [ahead 1, behind 2]
        Result r = run(root, "status", "-sb");
        if (r.exit != 0) return new int[]{0, 0};
        String first = r.out.split("\n", 2)[0];
        int ahead = 0, behind = 0;
        var m = java.util.regex.Pattern.compile("ahead (\\d+)").matcher(first);
        if (m.find()) ahead = Integer.parseInt(m.group(1));
        m = java.util.regex.Pattern.compile("behind (\\d+)").matcher(first);
        if (m.find()) behind = Integer.parseInt(m.group(1));
        return new int[]{ahead, behind};
    }

    /** 未跟踪文件的行数统计：[新增行, 删除行=0]；二进制返回 [-1,-1]。 */
    private int[] countUntrackedLines(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
                if (bytes[i] == 0) return new int[]{-1, -1};
            }
            // 与 wc -l 口径一致：末尾换行符不另起一行
            int lines = 0;
            for (byte b : bytes) if (b == '\n') lines++;
            if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') lines++;
            return new int[]{lines, 0};
        } catch (IOException e) {
            return new int[]{0, 0};
        }
    }

    private static String labelOf(String code) {
        return switch (code) {
            case "M", "MM", "AM" -> "修改";
            case "A" -> "新增";
            case "D" -> "删除";
            case "R" -> "重命名";
            case "??" -> "未跟踪";
            default -> code.isEmpty() ? "变更" : "变更(" + code + ")";
        };
    }

    private static String normalize(String path) {
        // git 可能仍对特殊字符加引号
        if (path.startsWith("\"") && path.endsWith("\"")) {
            return path.substring(1, path.length() - 1);
        }
        return path;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 路径等价比较（Windows 大小写不敏感、/ 与 \ 互认）。 */
    private static boolean sameDir(String a, Path b) {
        if (a == null || a.isBlank()) return false;
        try {
            Path pa = Path.of(a.trim()).toAbsolutePath().normalize();
            return pa.compareTo(b.toAbsolutePath().normalize()) == 0
                    || pa.toString().equalsIgnoreCase(b.toAbsolutePath().normalize().toString());
        } catch (Exception e) {
            return false;
        }
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    private record Result(int exit, String out) {
    }
}
