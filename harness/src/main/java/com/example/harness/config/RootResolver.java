package com.example.harness.config;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 解析沙箱默认项目根（F10）：
 * - harness.root 已配置 → 绝对路径（相对路径解析为绝对）
 * - 未配置 → <启动目录>/workspace
 */
@Component
public class RootResolver {

    private final Path defaultRoot;

    public RootResolver(HarnessProperties props) {
        String root = props.getRoot();
        this.defaultRoot = (root == null || root.isBlank())
                ? Paths.get(System.getProperty("user.dir"), "workspace").toAbsolutePath().normalize()
                : Paths.get(root).toAbsolutePath().normalize();
    }

    public Path defaultRoot() {
        return defaultRoot;
    }
}
