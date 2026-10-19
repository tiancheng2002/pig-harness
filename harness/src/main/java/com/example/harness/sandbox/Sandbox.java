package com.example.harness.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地文件沙箱（F10）：所有文件操作必须落在 root 内。
 * 越界路径（绝对路径、../ 逃逸）一律抛 SecurityException。
 */
public class Sandbox {

    private final Path root;

    public Sandbox(String rootDir) {
        Path p = (rootDir == null || rootDir.isBlank())
                ? Paths.get(System.getProperty("user.dir"), "workspace")
                : Paths.get(rootDir);
        this.root = p.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建沙箱根目录: " + this.root, e);
        }
    }

    public Path root() {
        return root;
    }

    /** 在沙箱内解析相对/绝对路径，越界即拒绝。 */
    public Path resolve(String path) {
        if (path == null || path.isBlank()) {
            throw new SecurityException("非法路径: <空>");
        }
        if (path.contains("\0")) {
            throw new SecurityException("非法路径: " + path);
        }
        Path raw = Paths.get(path);
        Path target = (raw.isAbsolute() ? raw : root.resolve(raw)).toAbsolutePath().normalize();
        if (!insideRoot(target)) {
            throw new SecurityException("越界路径被拒绝: " + path);
        }
        return target;
    }

    /** root 内的相对路径展示形式（供 UI/日志使用）。 */
    public String relativize(Path target) {
        String t = target.toAbsolutePath().normalize().toString();
        String r = root.toString();
        if (t.equalsIgnoreCase(r)) return ".";
        if (t.toLowerCase().startsWith(r.toLowerCase() + java.io.File.separator)) {
            return t.substring(r.length() + 1);
        }
        return t;
    }

    private boolean insideRoot(Path target) {
        if (target.startsWith(root)) return true;
        // Windows 下兜底做大小写不敏感比较
        String t = target.toString().toLowerCase();
        String r = root.toString().toLowerCase();
        return t.equals(r) || t.startsWith(r + java.io.File.separator);
    }
}
