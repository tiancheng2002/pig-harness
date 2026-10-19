package com.example.harness.tools;

import com.example.harness.core.memory.WorkingMemory;
import com.example.harness.sandbox.Sandbox;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工具执行上下文：每任务一份（沙箱根 + 工作记忆 + 权限模式）。
 * allowOutside=true（完全访问）时允许解析沙箱外的绝对路径；否则一律经沙箱校验。
 */
public record ToolContext(Sandbox sandbox, WorkingMemory memory, boolean allowOutside,
                          com.example.harness.core.task.TaskRunContext runContext,
                          String agentId) {

    public ToolContext(Sandbox sandbox, WorkingMemory memory, boolean allowOutside) {
        this(sandbox, memory, allowOutside, null, null);
    }

    public ToolContext(Sandbox sandbox, WorkingMemory memory) {
        this(sandbox, memory, false, null, null);
    }

    /** 解析工具传入的路径：默认走沙箱校验；完全访问时绝对路径直接放行（相对路径仍相对工作根）。 */
    public Path resolve(String path) {
        if (!allowOutside) {
            return sandbox.resolve(path);
        }
        if (path == null || path.isBlank()) {
            throw new SecurityException("非法路径: <空>");
        }
        if (path.contains("\0")) {
            throw new SecurityException("非法路径: " + path);
        }
        Path raw = Paths.get(path);
        return (raw.isAbsolute() ? raw : sandbox.root().resolve(raw)).toAbsolutePath().normalize();
    }
}
