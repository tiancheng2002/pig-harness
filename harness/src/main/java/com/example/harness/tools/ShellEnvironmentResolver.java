package com.example.harness.tools;

import com.example.harness.config.HarnessProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Detects the real local shell once and exposes its dialect to prompts and command execution. */
@Component
public class ShellEnvironmentResolver {

    private static final Logger log = LoggerFactory.getLogger(ShellEnvironmentResolver.class);

    public enum Dialect { BASH, POWERSHELL, CMD, POSIX_SH }

    public record Environment(Dialect dialect, String executable) {
        public String label() {
            return switch (dialect) {
                case BASH -> "bash";
                case POWERSHELL -> "PowerShell";
                case CMD -> "cmd";
                case POSIX_SH -> "POSIX sh";
            };
        }

        public List<String> commandLine(String command) {
            return switch (dialect) {
                case BASH -> List.of(executable, "--noprofile", "--norc", "-o", "pipefail", "-c", command);
                case POSIX_SH -> List.of(executable, "-c", command);
                case POWERSHELL -> List.of(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                        "$ErrorActionPreference='Stop'; & { " + command
                                + " }; $harnessOk=$?; $harnessExit=$LASTEXITCODE;"
                                + " if ($null -ne $harnessExit) { exit $harnessExit }"
                                + " elseif (-not $harnessOk) { exit 1 } else { exit 0 }");
                case CMD -> List.of(executable, "/d", "/s", "/c", command);
            };
        }
    }

    private final HarnessProperties props;
    private volatile Environment cached;

    public ShellEnvironmentResolver(HarnessProperties props) {
        this.props = props;
    }

    public Environment resolve() {
        Environment value = cached;
        if (value != null) return value;
        synchronized (this) {
            if (cached == null) {
                cached = detect();
                log.info("命令工具使用 {}: {}", cached.label(), cached.executable());
            }
            return cached;
        }
    }

    public String promptSummary() {
        Environment env = resolve();
        String syntax = switch (env.dialect()) {
            case BASH, POSIX_SH -> "使用 POSIX 命令语法；管道已启用 pipefail";
            case POWERSHELL -> "使用 PowerShell 语法，不要使用 ls/cat/true 等 Bash 专用组合";
            case CMD -> "使用 Windows cmd 语法，不要使用 ls/cat/true 等 Bash 命令";
        };
        return env.label() + "（" + syntax + "）";
    }

    private Environment detect() {
        if (!windows()) {
            String bash = firstWorkingBash(List.of("bash", "/bin/bash", "/usr/bin/bash"));
            return bash != null ? new Environment(Dialect.BASH, bash)
                    : new Environment(Dialect.POSIX_SH, "/bin/sh");
        }

        String preferred = normalizedPreferred();
        if ("bash".equals(preferred) || "auto".equals(preferred)) {
            String bash = firstWorkingBash(windowsBashCandidates());
            if (bash != null) return new Environment(Dialect.BASH, bash);
        }
        if ("powershell".equals(preferred) || "auto".equals(preferred)) {
            String ps = firstWorkingPowerShell();
            if (ps != null) return new Environment(Dialect.POWERSHELL, ps);
        }
        if ("cmd".equals(preferred) || "auto".equals(preferred)) {
            return new Environment(Dialect.CMD, "cmd.exe");
        }
        log.warn("未知 harness.shell.preferred={}，回退自动探测", preferred);
        String bash = firstWorkingBash(windowsBashCandidates());
        if (bash != null) return new Environment(Dialect.BASH, bash);
        String ps = firstWorkingPowerShell();
        return ps != null ? new Environment(Dialect.POWERSHELL, ps)
                : new Environment(Dialect.CMD, "cmd.exe");
    }

    private String normalizedPreferred() {
        String value = props.getShell().getPreferred();
        return value == null || value.isBlank() ? "auto" : value.trim().toLowerCase(Locale.ROOT);
    }

    protected List<String> windowsBashCandidates() {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add("bash");
        for (Path git : executablesOnPath("git.exe")) {
            for (Path bash : deriveGitBashCandidates(git)) candidates.add(bash.toString());
        }
        candidates.add("C:\\Program Files\\Git\\bin\\bash.exe");
        candidates.add("C:\\Program Files\\Git\\usr\\bin\\bash.exe");
        candidates.add("C:\\Program Files (x86)\\Git\\bin\\bash.exe");
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            candidates.add(Path.of(local, "Programs", "Git", "bin", "bash.exe").toString());
        }
        return List.copyOf(candidates);
    }

    /** git.exe is usually <git-root>/cmd/git.exe; Bash lives in sibling bin/ or usr/bin/. */
    public static List<Path> deriveGitBashCandidates(Path gitExecutable) {
        if (gitExecutable == null) return List.of();
        Path parent = gitExecutable.toAbsolutePath().normalize().getParent();
        if (parent == null) return List.of();
        Path root = "cmd".equalsIgnoreCase(String.valueOf(parent.getFileName()))
                ? parent.getParent() : parent;
        if (root == null) return List.of();
        return List.of(root.resolve("bin").resolve("bash.exe"),
                root.resolve("usr").resolve("bin").resolve("bash.exe"));
    }

    private List<Path> executablesOnPath(String executable) {
        List<Path> out = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("where.exe", executable).redirectErrorStream(true).start();
            boolean done = process.waitFor(3, TimeUnit.SECONDS);
            if (!done) process.destroyForcibly();
            if (done && process.exitValue() == 0) {
                String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    if (!line.isBlank()) out.add(Path.of(line.trim()));
                }
            }
        } catch (Exception ignore) {
            // PATH discovery is best-effort; standard candidates are tried next.
        }
        return out;
    }

    protected String firstWorkingBash(List<String> candidates) {
        for (String candidate : candidates) {
            if (candidate.contains("\\") && !Files.isRegularFile(Path.of(candidate))) continue;
            if (probe(List.of(candidate, "--noprofile", "--norc", "-c", "printf __harness_ok__"),
                    "__harness_ok__")) return candidate;
        }
        return null;
    }

    protected String firstWorkingPowerShell() {
        List<String> candidates = new ArrayList<>(List.of("pwsh.exe", "powershell.exe"));
        String root = System.getenv("SystemRoot");
        if (root != null && !root.isBlank()) {
            candidates.add(Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString());
        }
        for (String candidate : candidates) {
            if (candidate.contains("\\") && !Files.isRegularFile(Path.of(candidate))) continue;
            if (probe(List.of(candidate, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::Write('__harness_ok__')"), "__harness_ok__")) return candidate;
        }
        return null;
    }

    private boolean probe(List<String> command, String expected) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean done = process.waitFor(3, TimeUnit.SECONDS);
            if (!done) return false;
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 && output.contains(expected);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    protected boolean windows() { return isWindows(); }
}
