package com.example.harness.mcp;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 连接管理器（M5-F7）：维护每台服务器的客户端连接与工具注册。
 * - 启动时后台线程逐台连接 enabled 服务器（失败不阻塞启动，标记 error）；
 * - 调用失败时 lazy 重连一次再试，仍失败返回明确错误文本（回灌模型走 reflect）；
 * - 禁用/删除服务器即注销其全部工具并关闭连接（stdio 传输终止子进程）；
 * - 传输按 transport 分流：http → McpHttpClient，stdio → McpStdioClient。
 */
@Component
public class McpConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);

    /** 单台服务器的运行态连接。registeredTools 记录已注册的本地工具名（注销用）。 */
    private static final class Conn {
        volatile McpClient client;
        final Map<String, String> toolDescriptions = new LinkedHashMap<>(); // 本地名 -> 描述
        volatile String status = "connecting";
        volatile String lastError;
    }

    private final McpServerStore store;
    private final ToolRegistry toolRegistry;
    private final HarnessProperties props;
    private final ObjectMapper om;
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();

    public McpConnectionManager(McpServerStore store, ToolRegistry toolRegistry,
                                HarnessProperties props, ObjectMapper om) {
        this.store = store;
        this.toolRegistry = toolRegistry;
        this.props = props;
        this.om = om;
    }

    /** 启动后台连接：逐台握手（每台自带超时），绝不阻塞 Spring 启动。 */
    @PostConstruct
    public void init() {
        List<McpServerStore.McpServer> enabled = store.listAll().stream()
                .filter(McpServerStore.McpServer::enabled).toList();
        if (enabled.isEmpty()) return;
        Thread t = new Thread(() -> {
            for (McpServerStore.McpServer s : enabled) {
                try {
                    connect(s.id());
                } catch (Exception e) {
                    log.warn("MCP 服务器 [{}] 启动连接失败: {}", s.name(), e.getMessage());
                }
            }
            log.info("MCP 服务器启动连接完成：{} 台", enabled.size());
        }, "mcp-startup-connect");
        t.setDaemon(true);
        t.start();
    }

    /** 连接（或重连）一台服务器：握手 → tools/list → 注册 McpTool。同步执行，自带超时。 */
    public McpServerStore.McpServer connect(String serverId) {
        McpServerStore.McpServer server = store.get(serverId).orElse(null);
        if (server == null) throw new IllegalArgumentException("MCP 服务器不存在: " + serverId);
        if (!server.enabled()) {
            disconnect(serverId);
            store.updateStatus(serverId, "disabled", null, 0);
            return store.get(serverId).orElse(server);
        }
        unregisterTools(serverId);
        Conn conn = conns.computeIfAbsent(serverId, k -> new Conn());
        conn.status = "connecting";
        conn.lastError = null;
        store.updateStatus(serverId, "connecting", null, 0);
        // 换连接前必须关闭旧客户端（stdio 持有子进程，置空丢弃会泄漏进程）
        McpClient old = conn.client;
        conn.client = null;
        if (old != null) {
            try {
                old.close();
            } catch (Exception e) {
                log.warn("MCP 服务器 [{}] 旧连接关闭失败: {}", server.name(), e.getMessage());
            }
        }
        McpClient candidate = null;
        try {
            candidate = buildClient(server);
            candidate.initialize();
            List<Map<String, Object>> tools = candidate.listTools();
            conn.client = candidate;
            for (Map<String, Object> tool : tools) {
                String toolName = String.valueOf(tool.get("name"));
                if (toolName.isBlank() || "null".equals(toolName)) continue;
                McpTool t = new McpTool(serverId, server.name(), toolName,
                        String.valueOf(tool.getOrDefault("description", "")),
                        schemaJson(tool.get("inputSchema")), this);
                toolRegistry.register(t);
                conn.toolDescriptions.put(t.name(), t.description());
            }
            conn.status = "connected";
            store.updateStatus(serverId, "connected", null, conn.toolDescriptions.size());
            log.info("MCP 服务器 [{}] 已连接（{}），注册 {} 个工具",
                    server.name(), server.transport(), conn.toolDescriptions.size());
        } catch (Exception e) {
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (Exception ignored) {
                    // 尽力而为
                }
            }
            conn.status = "error";
            conn.lastError = e.getMessage();
            store.updateStatus(serverId, "error", abbreviate(e.getMessage()), 0);
            log.warn("MCP 服务器 [{}] 连接失败: {}", server.name(), e.getMessage());
        }
        return store.get(serverId).orElse(server);
    }

    /** 按传输方式构建客户端：http → Streamable HTTP，stdio → 本地子进程。 */
    private McpClient buildClient(McpServerStore.McpServer server) {
        if (server.isStdio()) {
            return new McpStdioClient(server.name(), server.command(), server.args(), server.env(),
                    props.getMcp().getStdioInitTimeoutMs(), props.getMcp().getCallTimeoutMs());
        }
        return new McpHttpClient(server.url(), server.headers(),
                props.getMcp().getConnectTimeoutMs(), props.getMcp().getCallTimeoutMs());
    }

    /** 注销该服务器注册的全部工具并关闭连接。 */
    public void disconnect(String serverId) {
        unregisterTools(serverId);
        Conn conn = conns.remove(serverId);
        if (conn != null && conn.client != null) {
            conn.client.close();
        }
    }

    /** 手动刷新：重连 + 重新 tools/list。 */
    public McpServerStore.McpServer refresh(String serverId) {
        return connect(serverId);
    }

    /** McpTool 执行入口：失败时 lazy 重连一次再试。 */
    public ToolRegistryHolder invoke(String serverId, String toolName, Map<String, Object> args) {
        Conn conn = conns.get(serverId);
        if (conn == null || conn.client == null || !"connected".equals(conn.status)) {
            // 未连接（如服务器中途重启后首次调用）：尝试重连一次
            try {
                connect(serverId);
                conn = conns.get(serverId);
            } catch (Exception e) {
                return ToolRegistryHolder.failure("MCP 服务器连接失败: " + e.getMessage());
            }
            if (conn == null || conn.client == null) {
                return ToolRegistryHolder.failure("MCP 服务器未连接"
                        + (conn != null && conn.lastError != null ? "（" + conn.lastError + "）" : ""));
            }
        }
        try {
            McpClient.CallResult result = conn.client.callTool(toolName, args);
            if (result.isError()) {
                return ToolRegistryHolder.failure(textOf(result.content()).isBlank()
                        ? "MCP 工具返回错误" : textOf(result.content()));
            }
            String text = textOf(result.content());
            if (text.length() > 16_000) {
                text = text.substring(0, 16_000) + "…（已截断）";
            }
            return ToolRegistryHolder.success(text.isBlank() ? "（工具无文本输出）" : text);
        } catch (Exception first) {
            // 断线恢复：重连一次后重试
            log.info("MCP 工具调用失败，尝试重连后重试: {}", first.getMessage());
            try {
                connect(serverId);
                Conn reconnected = conns.get(serverId);
                if (reconnected != null && reconnected.client != null) {
                    McpClient.CallResult result = reconnected.client.callTool(toolName, args);
                    if (result.isError()) {
                        return ToolRegistryHolder.failure(textOf(result.content()).isBlank()
                                ? "MCP 工具返回错误" : textOf(result.content()));
                    }
                    String text = textOf(result.content());
                    return ToolRegistryHolder.success(text.isBlank() ? "（工具无文本输出）" : text);
                }
            } catch (Exception retry) {
                return ToolRegistryHolder.failure("MCP 工具调用失败（已重试）: " + retry.getMessage());
            }
            return ToolRegistryHolder.failure("MCP 工具调用失败: " + first.getMessage());
        }
    }

    /** invoke 的返回结构（避免与 tools.ToolResult 循环依赖的简单载体）。 */
    public record ToolRegistryHolder(boolean ok, String output) {
        public static ToolRegistryHolder success(String output) {
            return new ToolRegistryHolder(true, output);
        }

        public static ToolRegistryHolder failure(String message) {
            return new ToolRegistryHolder(false, message);
        }
    }

    /** 该服务器已注册工具的本地名与描述（管理 UI 展示）。 */
    public List<Map<String, Object>> registeredTools(String serverId) {
        Conn conn = conns.get(serverId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (conn == null) return out;
        for (Map.Entry<String, String> e : conn.toolDescriptions.entrySet()) {
            out.add(Map.of("name", e.getKey(), "description", e.getValue()));
        }
        return out;
    }

    public String statusOf(String serverId) {
        Conn conn = conns.get(serverId);
        return conn == null ? "disabled" : conn.status;
    }

    /** 新增服务器（供 Controller）：生成 id 落库并按需后台连接。 */
    public McpServerStore.McpServer create(String name, String url, Map<String, String> headers,
                                           String transport, String command, List<String> args,
                                           Map<String, String> env, boolean enabled) {
        String id = UUID.randomUUID().toString();
        String now = LocalDateTime.now().toString();
        McpServerStore.McpServer server = new McpServerStore.McpServer(id, name, url,
                headers, transport, command, args, env,
                enabled, enabled ? "connecting" : "disabled", null, 0, now, now);
        store.save(server);
        if (enabled) {
            Thread t = new Thread(() -> {
                try {
                    connect(id);
                } catch (Exception e) {
                    log.warn("MCP 服务器 [{}] 初始连接失败: {}", name, e.getMessage());
                }
            }, "mcp-connect-" + id);
            t.setDaemon(true);
            t.start();
        }
        return server;
    }

    /** 应用关闭时终止全部连接（stdio 子进程随之销毁）。 */
    @PreDestroy
    public void shutdown() {
        for (String serverId : conns.keySet()) {
            try {
                disconnect(serverId);
            } catch (Exception e) {
                log.warn("MCP 服务器 [{}] 关闭失败: {}", serverId, e.getMessage());
            }
        }
    }

    private void unregisterTools(String serverId) {
        Conn conn = conns.get(serverId);
        if (conn == null) return;
        for (String localName : conn.toolDescriptions.keySet()) {
            toolRegistry.unregister(localName);
        }
        conn.toolDescriptions.clear();
    }

    private String schemaJson(Object schema) {
        try {
            return schema == null ? "{\"type\":\"object\",\"properties\":{}}"
                    : om.writeValueAsString(schema);
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    private String textOf(List<Map<String, Object>> content) {
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            for (Map<String, Object> c : content) {
                if ("text".equals(c.get("type")) && c.get("text") != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(c.get("text"));
                } else if (c.get("type") != null && !"text".equals(c.get("type"))) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append("（").append(c.get("type")).append(" 类型内容已忽略）");
                }
            }
        }
        return sb.toString();
    }

    private String abbreviate(String s) {
        if (s == null) return null;
        s = s.replace('\n', ' ');
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
