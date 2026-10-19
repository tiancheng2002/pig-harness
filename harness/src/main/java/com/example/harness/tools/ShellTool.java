package com.example.harness.tools;

import com.example.harness.config.HarnessProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * bash 命令执行工具：在工作根目录运行 shell 命令（编译构建、运行测试、git、
 * 查看进程/端口等调试操作），返回合并 stdout/stderr 与退出码。
 * Shell 选择：优先本机 bash（PATH、Git 安装位置推导或常见路径，首次探测后缓存）；
 * 无 bash 的 Windows 依次回退 PowerShell、cmd。输出超长截断（保留头尾），超时强制终止并回传已捕获输出。
 */
@Component
public class ShellTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ShellTool.class);
    /** Shell output is fed back to the model, so successful build logs must stay compact. */
    private static final int SUCCESS_OUTPUT_CHARS = 5_000;
    private static final int FAILURE_OUTPUT_CHARS = 8_000;

    /** P1 内置危险命令黑名单（正则，大小写不敏感）；用户可经 harness.shell.deny-patterns 追加。 */
    private static final List<java.util.regex.Pattern> BUILTIN_DENY = List.of(
            compileCase("\\brm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\\s+[\"']?[/~]"),
            compileCase("\\bmkfs(\\.|\\s)"),
            compileCase("\\bdd\\s+if="),
            compileCase("\\b(shutdown|reboot|halt|poweroff)\\b"),
            compileCase(":\\(\\)\\s*\\{.*\\};\\s*:"),              // fork bomb
            compileCase("\\bformat\\s+[a-zA-Z]:"),
            compileCase("\\b(del\\s+/[sq]|rd\\s+/s|erase\\s+[a-zA-Z]:)"),
            compileCase("\\bchmod\\s+-R\\s+777\\s+/(\\s|$)"),
            compileCase(">\\s*/dev/(sd|nvme|hd)"),
            compileCase("\\bmkfs\\.[a-z]+\\b"),
            compileCase("\\b(diskpart|diskutil\\s+erase)\\b"));

    private static java.util.regex.Pattern compileCase(String regex) {
        return java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
    }

    /** docker 可用性探测缓存（P1 隔离模式用；null=未探测）。 */
    private static volatile Boolean dockerAvailable;

    private final HarnessProperties props;
    private final ShellEnvironmentResolver shellResolver;

    public ShellTool(HarnessProperties props, ShellEnvironmentResolver shellResolver) {
        this.props = props;
        this.shellResolver = shellResolver;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "在工作根目录执行命令（当前方言：" + shellResolver.promptSummary() + "），"
                + "用于编译、测试、运行程序和 git；浏览/读取文件优先使用 listFiles/grep/fileRead。"
                + "建议用 working_dir 指定模块目录，不要用 cd 串联；返回合并输出与真实退出码。"
                + "测试/构建的用例数、失败原因与断言差异已自动摘录并置顶，"
                + "不要把构建日志重定向到文件再 grep/sed 回读。";
    }

    @Override
    public String parametersJson() {
        return """
                {"type":"object","properties":{
                  "command":{"type":"string","description":"要执行的命令，必须使用工具描述中声明的当前 Shell 方言"},
                  "timeout_seconds":{"type":"integer","description":"超时秒数，默认 60，上限 300"},
                  "working_dir":{"type":"string","description":"工作目录（相对工作根的相对路径，默认工作根）"}
                },"required":["command"]}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        if (!props.getShell().isEnabled()) {
            return ToolResult.error("bash 工具已被禁用（harness.shell.enabled=false）");
        }
        Object cmd = args.get("command");
        if (!(cmd instanceof String command) || command.isBlank()) {
            return ToolResult.error("缺少必填参数 command");
        }
        // P1 命令黑名单：内置危险清单 + 用户追加正则，命中即拒绝（不执行、错误回灌）
        String denyHit = denyHit(command);
        if (denyHit != null) {
            String brief = command.length() > 120 ? command.substring(0, 120) + "…" : command;
            log.warn("bash 命令被安全策略拒绝: {}", brief);
            return ToolResult.error("命令被安全策略拒绝（deny-patterns 命中: " + denyHit + "）。"
                    + "该命令匹配危险操作模式，请改用安全的替代方案；如确属误报，请管理员调整 harness.shell.deny-patterns 配置。");
        }
        String backgroundProblem = unmanagedBackground(command);
        if (backgroundProblem != null) {
            return ToolResult.error(backgroundProblem);
        }
        Path cwd;
        try {
            Object wd = args.get("working_dir");
            cwd = ctx.resolve(wd instanceof String s && !s.isBlank() ? s : ".");
            if (!Files.isDirectory(cwd)) {
                return ToolResult.error("工作目录不存在或不是目录: " + wd);
            }
        } catch (Exception e) {
            return ToolResult.error("工作目录非法: " + e.getMessage());
        }
        int timeout = clampTimeout(args.get("timeout_seconds"));

        String syntaxError = bashSyntaxError(command);
        if (syntaxError != null) {
            return ToolResult.error("Bash 静态语法检查失败（命令尚未执行）。这不是系统环境故障；"
                    + "请改写成一条引号配对、结构完整的命令后重试，不要为此创建临时脚本文件。\n"
                    + compactOutput(syntaxError, false));
        }

        List<String> cmdLine = buildCommand(command.trim(), cwd);
        ProcessBuilder pb = new ProcessBuilder(cmdLine);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true); // stderr 并入 stdout（git 的 CRLF 警告等一并回传）
        try {
            Process p = pb.start();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            Thread drainer = Thread.ofVirtual().start(() -> drain(p.getInputStream(), raw));
            boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                terminateProcessTree(p);
                p.waitFor(2, TimeUnit.SECONDS);
                drainer.join(1000);
                String partial = decode(raw);
                return ToolResult.error("命令执行超时（" + timeout + " 秒），已强制终止。"
                        + (partial.isBlank() ? "" : "\n已捕获输出：\n" + compactOutput(partial, false)));
            }
            drainer.join(2000);
            String output = decode(raw);
            int code = p.exitValue();
            // Build/test verdicts sit in the middle of the log, which is exactly what truncation
            // drops. Pin them to the front so the agent never has to grep a log file back.
            String summary = BuildOutputSummarizer.applies(command)
                    ? BuildOutputSummarizer.summarize(output) : "";
            String body = summary
                    + (output.isBlank() ? "（无输出）" : compactOutput(output, code == 0))
                    + "\n（退出码 " + code + "）";
            if (code == 0) {
                return ToolResult.success(body);
            }
            return ToolResult.error(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("命令执行被中断");
        } catch (Exception e) {
            log.warn("bash 执行失败: {}", e.getMessage());
            return ToolResult.error("命令启动失败: " + e.getMessage());
        }
    }

    /** 命令黑名单检查：内置清单 + 用户追加；命中返回命中的模式描述，未命中返回 null。 */
    private String denyHit(String command) {
        for (java.util.regex.Pattern p : BUILTIN_DENY) {
            if (p.matcher(command).find()) return "内置危险清单 " + p.pattern();
        }
        for (String extra : props.getShell().getDenyPatterns()) {
            try {
                if (compileCase(extra).matcher(command).find()) return "用户配置 " + extra;
            } catch (Exception ignore) {
                // 非法正则跳过（配置错误不该炸掉工具）
            }
        }
        return null;
    }

    /** Detached processes cannot be reliably attributed and cleaned up by a task. */
    private static String unmanagedBackground(String command) {
        String c = command.toLowerCase(java.util.Locale.ROOT);
        boolean bashDetached = c.matches("(?s).*\\bnohup\\b.*")
                || c.matches("(?s).*(?<!&)&\\s*$");
        boolean powershellDetached = c.contains("start-process") && !c.contains("-wait");
        boolean cmdDetached = c.matches("(?s).*\\bstart\\s+(?:\"[^\"]*\"\\s+)?/b\\b.*")
                && !c.contains("/wait");
        boolean explicitCleanup = c.contains("trap ") && (c.contains("kill ") || c.contains("wait "));
        if ((bashDetached && !explicitCleanup) || powershellDetached || cmdDetached) {
            return "拒绝启动无托管后台进程：任务结束后它可能继续占用端口。"
                    + "优先使用单元/集成测试；若用户明确要求真实启动，请在一个前台脚本中使用随机端口，"
                    + "并用 trap + kill/wait 保证退出时清理本任务启动的进程。";
        }
        return null;
    }

    /** Bash parses the command before it is allowed to start builds, servers or scripts. */
    private String bashSyntaxError(String command) {
        if ("docker".equalsIgnoreCase(props.getShell().getSandbox())) return null;
        ShellEnvironmentResolver.Environment env = shellResolver.resolve();
        if (env.dialect() != ShellEnvironmentResolver.Dialect.BASH) return null;
        Process process = null;
        try {
            process = new ProcessBuilder(env.executable(), "--noprofile", "--norc",
                    "-n", "-c", command).redirectErrorStream(true).start();
            boolean done = process.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                terminateProcessTree(process);
                return "语法检查超时";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? null : output.stripTrailing();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // A failed preflight must not make an otherwise executable shell unavailable.
            return null;
        } finally {
            if (process != null && process.isAlive()) terminateProcessTree(process);
        }
    }

    private static void terminateProcessTree(Process process) {
        try {
            List<ProcessHandle> descendants = process.descendants().toList();
            for (int i = descendants.size() - 1; i >= 0; i--) {
                descendants.get(i).destroyForcibly();
            }
        } catch (Exception ignore) {
            // Best effort on platforms that restrict process inspection.
        }
        process.destroyForcibly();
    }

    /**
     * 命令行组装：harness.shell.sandbox=docker 时容器内执行（挂载工作根，资源受限，
     * Docker 不可用明确报错不降级）；否则使用 ShellEnvironmentResolver 已探测的真实方言。
     */
    private List<String> buildCommand(String command, java.nio.file.Path cwd) {
        if ("docker".equalsIgnoreCase(props.getShell().getSandbox())) {
            return dockerCommand(command, cwd);
        }
        return shellResolver.resolve().commandLine(command);
    }

    /** docker 隔离执行：`docker run --rm -v <cwd>:/work -w /work [--network X] [--memory] [--cpus] image sh -c cmd`。 */
    private List<String> dockerCommand(String command, java.nio.file.Path cwd) {
        Boolean available = dockerAvailable;
        if (available == null) {
            available = probeDocker();
            dockerAvailable = available;
            log.info("docker 隔离模式可用性: {}", available ? "OK" : "不可用");
        }
        if (!available) {
            throw new IllegalStateException(
                    "harness.shell.sandbox=docker 但 Docker 不可用（CLI 缺失或守护进程未启动）。"
                            + "为安全起见不会回退为直接在本机执行；请启动 Docker Desktop 或将 sandbox 改回 off。");
        }
        var shell = props.getShell();
        List<String> cmd = new java.util.ArrayList<>(List.of(
                "docker", "run", "--rm",
                "-v", cwd.toAbsolutePath().normalize() + ":/work",
                "-w", "/work"));
        if (shell.getDocker().getNetwork() != null && !shell.getDocker().getNetwork().isBlank()) {
            cmd.addAll(List.of("--network", shell.getDocker().getNetwork()));
        }
        if (shell.getDocker().getMemory() != null && !shell.getDocker().getMemory().isBlank()) {
            cmd.addAll(List.of("--memory", shell.getDocker().getMemory()));
        }
        if (shell.getDocker().getCpus() != null && !shell.getDocker().getCpus().isBlank()) {
            cmd.addAll(List.of("--cpus", shell.getDocker().getCpus()));
        }
        cmd.add(shell.getDocker().getImage());
        cmd.addAll(List.of("sh", "-c", command));
        return cmd;
    }

    /** docker CLI 探测：`docker info` 在 10 秒内成功即视为可用。 */
    private static boolean probeDocker() {
        try {
            Process p = new ProcessBuilder("docker", "info").start();
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            p.destroyForcibly();
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public String environmentSummary() {
        return shellResolver.promptSummary();
    }

    private int clampTimeout(Object requested) {
        int def = Math.max(1, props.getShell().getDefaultTimeoutSeconds());
        int max = Math.max(def, props.getShell().getMaxTimeoutSeconds());
        if (!(requested instanceof Number n) || n.intValue() <= 0) {
            return def;
        }
        return Math.min(n.intValue(), max);
    }

    /** 虚线程排空输出流（防管道写满死锁），保留原始字节由调用方解码。 */
    private static void drain(InputStream in, ByteArrayOutputStream sink) {
        try (in) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                sink.write(buf, 0, n);
                if (sink.size() > 2 * 1024 * 1024) {
                    break; // 超大输出不再收集（进程会因管道关闭收到 SIGPIPE/IOException 结束）
                }
            }
        } catch (Exception ignore) {
            // 进程被强制终止时读取会抛异常，属预期
        }
    }

    /** 输出解码：UTF-8 优先；出现替换符（Windows cmd 的 GBK 输出）时回退 GBK。 */
    private static String decode(ByteArrayOutputStream raw) {
        byte[] bytes = raw.toByteArray();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0) {
            text = new String(bytes, Charset.forName("GBK"));
        }
        return text.stripTrailing();
    }

    /** Successful commands retain a small head/tail; failures retain more diagnostic context. */
    private static String compactOutput(String s, boolean success) {
        int limit = success ? SUCCESS_OUTPUT_CHARS : FAILURE_OUTPUT_CHARS;
        if (s.length() <= limit) {
            return s;
        }
        int head = success ? 1_000 : 5_000;
        int tail = success ? 3_500 : 2_500;
        return s.substring(0, head)
                + "\n…（输出过长，中间 "
                + (s.length() - head - tail)
                + " 字符已截断）…\n"
                + s.substring(s.length() - tail);
    }
}
