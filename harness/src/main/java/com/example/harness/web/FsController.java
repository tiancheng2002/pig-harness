package com.example.harness.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地文件夹浏览 API：供前端「选择文件夹」弹窗逐级导航本地目录。
 * 仅列出子目录（不暴露文件内容）；path 为空时返回盘符/根列表。
 * 本应用定位为本机单用户工具，目录浏览仅用于绑定项目，不做更细的权限控制。
 */
@RestController
public class FsController {

    /** 单目录条目：显示名 + 绝对路径。 */
    public record DirEntry(String name, String path) {
    }

    /**
     * 目录列表。path=null 表示根层级（此电脑/盘符列表）。
     * home 为用户主目录，供前端快捷跳转。
     */
    public record DirListing(String path, String name, String parent, String home,
                             List<DirEntry> dirs, boolean truncated) {
    }

    private static final int MAX_DIRS = 500;

    @GetMapping("/api/fs/list")
    public ResponseEntity<DirListing> list(@RequestParam(required = false) String path) {
        String home = System.getProperty("user.home");
        if (path == null || path.isBlank()) {
            List<DirEntry> roots = new ArrayList<>();
            for (File root : File.listRoots()) {
                roots.add(new DirEntry(root.toString(), root.toString()));
            }
            return ResponseEntity.ok(new DirListing(null, "此电脑", null, home, roots, false));
        }

        Path dir;
        try {
            dir = Paths.get(path).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路径不合法: " + path);
        }
        if (!Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件夹不存在: " + dir);
        }
        Path parent = dir.getParent();
        List<DirEntry> dirs = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> stream = Files.list(dir)) {
            var sorted = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
            for (Path p : sorted) {
                if (dirs.size() >= MAX_DIRS) {
                    truncated = true;
                    break;
                }
                String name = p.getFileName() != null ? p.getFileName().toString() : p.toString();
                dirs.add(new DirEntry(name, p.toString()));
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取该目录（权限不足或已被移除）: " + dir);
        }
        String name = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
        return ResponseEntity.ok(new DirListing(dir.toString(), name,
                parent != null ? parent.toString() : null, home, dirs, truncated));
    }
}
