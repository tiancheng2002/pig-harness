package com.example.harness.core;

import com.example.harness.llm.LlmToolSpec;
import com.example.harness.tools.Tool;
import com.example.harness.tools.ToolContext;
import com.example.harness.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册/分发中心（F5 + M5 动态扩展）：
 * - Spring 自动收集所有 Tool Bean（fileWrite/fileEdit/.../httpFetch）；
 * - 支持运行期 register/unregister（MCP 服务器工具 mcp__server__tool 动态接入/注销）；
 * - 未知工具名返回错误（进入 reflect），不抛异常。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 需要用户审批的写操作工具（权限模式=请求审批时生效；AgentLoop 与 SkillExecutor 共用）。
     * bash 可变更任意文件/环境，同样按写操作走审批闸门。 */
    public static final Set<String> MUTATING_TOOLS =
            Set.of("fileWrite", "fileEdit", "fileDelete", "docxWrite", "bash");

    /** 基础工具（system prompt 固定说明；其余工具作为「扩展工具」动态追加展示）。 */
    private static final Set<String> BASE_TOOLS =
            Set.of("fileWrite", "fileEdit", "fileRead", "listFiles", "fileDelete", "docxWrite", "bash", "grep");

    /** 子智能体永不开放的工具（防递归派出：spawnAgent 仅主 Agent 可用）。 */
    private static final String SPAWN_AGENT = com.example.harness.core.agents.SpawnAgentTool.NAME;

    /** 并发容器：MCP 工具在运行期动态注册/注销，与 LLM 调用线程并发访问。 */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final com.example.harness.core.task.ToolCallTracker callTracker;

    public ToolRegistry(List<Tool> toolBeans, com.example.harness.core.task.ToolCallTracker callTracker) {
        this.callTracker = callTracker;
        for (Tool t : toolBeans) {
            tools.put(t.name(), t);
        }
    }

    /** 运行期注册（MCP 工具接入）；同名覆盖。 */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        log.info("注册扩展工具: {}", tool.name());
    }

    /** 运行期注销（MCP 服务器禁用/删除）。 */
    public void unregister(String name) {
        if (tools.remove(name) != null) {
            log.info("注销扩展工具: {}", name);
        }
    }

    public boolean isRegistered(String name) {
        return name != null && tools.containsKey(name);
    }

    public Set<String> names() {
        return new TreeSet<>(tools.keySet());
    }

    public List<LlmToolSpec> specs() {
        List<LlmToolSpec> specs = new ArrayList<>();
        for (Tool t : tools.values()) {
            specs.add(new LlmToolSpec(t.name(), t.description(), t.parametersJson()));
        }
        specs.sort(Comparator.comparing(LlmToolSpec::name));
        return specs;
    }

    /**
     * 子智能体视角的工具清单：白名单过滤（空 = 不限制），且永不包含 spawnAgent（防递归派出）。
     */
    public List<LlmToolSpec> specsFor(java.util.Collection<String> allowed) {
        java.util.Set<String> allow = allowed == null || allowed.isEmpty()
                ? null : new java.util.HashSet<>(allowed);
        List<LlmToolSpec> specs = new ArrayList<>();
        for (Tool t : tools.values()) {
            if (SPAWN_AGENT.equals(t.name())) continue;
            if (allow != null && !allow.contains(t.name())) continue;
            specs.add(new LlmToolSpec(t.name(), t.description(), t.parametersJson()));
        }
        specs.sort(Comparator.comparing(LlmToolSpec::name));
        return specs;
    }

    /** 基础文件工具之外的扩展工具（httpFetch、mcp__*），system prompt 动态追加说明。 */
    public List<Tool> extras() {
        return tools.values().stream()
                .filter(t -> !BASE_TOOLS.contains(t.name()))
                .sorted(Comparator.comparing(Tool::name))
                .toList();
    }

    /** 工具定义按请求体的 JSON 形状序列化（TASK 请求每次都携带；用于上下文基线估算）。 */
    public String specsJson() {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (LlmToolSpec t : specs()) {
            arr.add(Map.of("type", "function",
                    "function", Map.of("name", t.name(), "description", t.description(),
                            "parameters", t.parametersJson())));
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arr);
        } catch (Exception e) {
            return "[]";
        }
    }

    public ToolResult execute(String name, Map<String, Object> args, ToolContext ctx) {
        return execute(null, name, args, ctx);
    }

    public ToolResult execute(String callId, String name, Map<String, Object> args, ToolContext ctx) {
        Map<String,Object> safeArgs = args == null ? Map.of() : args;
        return callTracker.track(ctx == null ? null : ctx.runContext(), callId,
                ctx == null ? null : ctx.agentId(), name, safeArgs,
                () -> executeRaw(name, safeArgs, ctx));
    }

    public void recordRejected(String callId, String name, Map<String, Object> args,
                               ToolContext ctx, String reason) {
        callTracker.rejected(ctx == null ? null : ctx.runContext(), callId,
                ctx == null ? null : ctx.agentId(), name, args == null ? Map.of() : args, reason);
    }

    public void recordSynthetic(String callId, String name, Map<String, Object> args,
                                ToolContext ctx, boolean ok, String error,
                                java.time.Instant startedAt, long durationMs) {
        callTracker.synthetic(ctx == null ? null : ctx.runContext(), callId,
                ctx == null ? null : ctx.agentId(), name, args == null ? Map.of() : args,
                ok, error, startedAt, durationMs);
    }

    private ToolResult executeRaw(String name, Map<String, Object> args, ToolContext ctx) {
        Tool tool = name == null ? null : tools.get(name);
        if (tool == null) {
            return ToolResult.error("未知工具: " + name + "（可用工具: " + String.join(", ", names()) + "）");
        }
        try {
            return tool.execute(args, ctx);
        } catch (SecurityException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("工具执行异常: " + e.getMessage());
        }
    }

    /** 工具的一句话动词说明（审批卡片展示用）。 */
    public String describe(String name, Map<String, Object> args) {
        Tool tool = name == null ? null : tools.get(name);
        return tool == null ? String.valueOf(name) : tool.description();
    }
}
