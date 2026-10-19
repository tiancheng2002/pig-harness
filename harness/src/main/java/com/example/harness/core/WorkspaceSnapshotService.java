package com.example.harness.core;

import com.example.harness.sandbox.Sandbox;
import com.example.harness.tools.ShellEnvironmentResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Small deterministic repository inventory used to ground planning before the first model call. */
@Component
public class WorkspaceSnapshotService {
    private static final int MAX_ENTRIES = 40;
    private static final Set<String> SKIP = Set.of(
            ".git", "target", "node_modules", "build", "dist", ".idea", ".vscode", "__pycache__");
    private static final Set<String> MANIFESTS = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "package.json", "go.mod", "cargo.toml", "pyproject.toml", "requirements.txt");

    private final ShellEnvironmentResolver shellResolver;

    public WorkspaceSnapshotService(ShellEnvironmentResolver shellResolver) {
        this.shellResolver = shellResolver;
    }

    public Snapshot inspect(Sandbox sandbox) {
        Path root = sandbox.root().toAbsolutePath().normalize();
        List<String> entries = new ArrayList<>();
        List<String> manifests = new ArrayList<>();
        Map<String, Integer> languages = new LinkedHashMap<>();
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 2,
                    new SimpleFileVisitor<>() {
                        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(root) && SKIP.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (!dir.equals(root)) add(dir, true);
                            return entries.size() >= MAX_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }

                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            add(file, false);
                            return entries.size() >= MAX_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }

                        private void add(Path path, boolean directory) {
                            String rel = root.relativize(path).toString().replace('\\', '/');
                            entries.add(rel + (directory ? "/" : ""));
                            if (!directory) {
                                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                                if (MANIFESTS.contains(name)) manifests.add(rel);
                                String language = languageOf(name);
                                if (language != null) languages.merge(language, 1, Integer::sum);
                            }
                        }
                    });
            entries.sort(String.CASE_INSENSITIVE_ORDER);
            manifests.sort(String.CASE_INSENSITIVE_ORDER);
        } catch (Exception e) {
            entries.add("<inventory unavailable: " + e.getMessage() + ">");
        }
        if (manifests.stream().anyMatch(path -> path.endsWith("pom.xml")
                || path.endsWith("build.gradle") || path.endsWith("build.gradle.kts"))) {
            languages.putIfAbsent("Java", 1);
        }
        if (manifests.stream().anyMatch(path -> path.endsWith("package.json"))) {
            languages.putIfAbsent("JS/TS", 1);
        }
        boolean git = Files.exists(root.resolve(".git"));
        String gitStatus = git ? gitStatus(root) : "not-a-repository";
        return new Snapshot(System.getProperty("os.name", "unknown"), shellResolver.promptSummary(),
                root.toString(), git, gitStatus, List.copyOf(entries), List.copyOf(manifests), Map.copyOf(languages));
    }

    private String gitStatus(Path root) {
        Process process = null;
        try {
            process = new ProcessBuilder("git", "-C", root.toString(), "status", "--short", "--branch",
                    "--untracked-files=no").redirectErrorStream(true).start();
            boolean done = process.waitFor(3, TimeUnit.SECONDS);
            if (!done) return "unavailable: timeout";
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) return "unavailable: exit " + process.exitValue();
            String[] lines = output.split("\\R");
            // A long dirty-tree listing is what invites the agent to go archaeology hunting.
            int keep = Math.min(lines.length, 12);
            return String.join(" | ", java.util.Arrays.copyOf(lines, keep));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "unavailable: " + e.getClass().getSimpleName();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private String languageOf(String name) {
        if (name.endsWith(".java")) return "Java";
        if (name.endsWith(".js") || name.endsWith(".jsx") || name.endsWith(".ts") || name.endsWith(".tsx")) return "JS/TS";
        if (name.endsWith(".py")) return "Python";
        if (name.endsWith(".go")) return "Go";
        if (name.endsWith(".rs")) return "Rust";
        if (name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".vue")) return "Frontend";
        return null;
    }

    public record Snapshot(String os, String shell, String root, boolean gitRepository, String gitStatus,
                           List<String> entries, List<String> manifests, Map<String, Integer> languages) {
        public String toPrompt() {
            return "[WORKSPACE_SNAPSHOT]\n"
                    + "os=" + os + "\n"
                    + "shell=" + shell + "\n"
                    + "root=" + root + "\n"
                    + "git=" + gitRepository + "\n"
                    + "gitStatus=" + gitStatus + "\n"
                    + "manifests=" + manifests + "\n"
                    + "languages=" + languages + "\n"
                    + "tree(depth<=2,max=" + MAX_ENTRIES + "):\n- " + String.join("\n- ", entries)
                    + "\n[/WORKSPACE_SNAPSHOT]";
        }
    }
}
