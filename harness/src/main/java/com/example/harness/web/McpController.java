package com.example.harness.web;

import com.example.harness.mcp.McpConnectionManager;
import com.example.harness.mcp.McpServerStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器管理 API（M5-F6）：清单/增删改/刷新/工具列表。
 * 服务器由用户显式配置，其 URL 不受 httpFetch 的私网限制（本地 MCP server 普遍跑在 127.0.0.1）。
 * stdio 扩展：transport=stdio 时以 command+args+env 描述本地子进程（url 留空）；
 * args 接受字符串（按空白/双引号分词）或字符串数组，落库统一为数组。
 */
@RestController
public class McpController {

    private final McpServerStore store;
    private final McpConnectionManager manager;

    public McpController(McpServerStore store, McpConnectionManager manager) {
        this.store = store;
        this.manager = manager;
    }

    public record CreateRequest(String name, String url, Map<String, String> headers,
                                String transport, String command, Object args, Map<String, String> env,
                                Boolean enabled) {
    }

    public record PatchRequest(String name, String url, Map<String, String> headers,
                               String transport, String command, Object args, Map<String, String> env,
                               Boolean enabled) {
    }

    @GetMapping("/api/mcp/servers")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpServerStore.McpServer s : store.listAll()) {
            out.add(toMap(s));
        }
        return out;
    }

    @PostMapping("/api/mcp/servers")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest req) {
        String transport = normalizeTransport(req.transport(), req.command(), req.url());
        String url = req.url() == null ? "" : req.url().trim();
        String command = req.command() == null ? "" : req.command().trim();
        if ("stdio".equals(transport)) {
            if (command.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stdio 传输需要提供启动命令");
            }
        } else {
            if (url.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url 不能为空");
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url 仅支持 http/https");
            }
        }
        String name = req.name() == null || req.name().isBlank() ? "mcp-server" : req.name().trim();
        boolean enabled = req.enabled() == null || req.enabled();
        McpServerStore.McpServer created = manager.create(name, url, req.headers(), transport,
                command, normalizeArgs(req.args()), req.env(), enabled);
        return ResponseEntity.status(HttpStatus.CREATED).body(toMap(created));
    }

    @PatchMapping("/api/mcp/servers/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable String id,
                                                     @RequestBody PatchRequest req) {
        McpServerStore.McpServer existing = store.get(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        String name = req.name() != null ? req.name().trim() : existing.name();
        String transport = normalizeTransport(req.transport(), req.command(), existing.url());
        if (req.transport() == null || req.transport().isBlank()) transport = existing.transport();
        String url = req.url() != null ? req.url().trim() : existing.url();
        String command = req.command() != null ? req.command().trim() : existing.command();
        if ("stdio".equals(transport)) {
            if (command.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stdio 传输需要提供启动命令");
            }
        } else {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url 仅支持 http/https");
            }
        }
        Map<String, String> headers = req.headers() != null ? req.headers() : existing.headers();
        List<String> args = req.args() != null ? normalizeArgs(req.args()) : existing.args();
        Map<String, String> env = req.env() != null ? req.env() : existing.env();
        boolean enabled = req.enabled() != null ? req.enabled() : existing.enabled();
        boolean targetChanged = existing.isStdio() != "stdio".equals(transport)
                || !url.equals(existing.url()) || !headers.equals(existing.headers())
                || !command.equals(existing.command()) || !args.equals(existing.args())
                || !env.equals(existing.env());
        boolean enabledChanged = enabled != existing.enabled();
        McpServerStore.McpServer updated = new McpServerStore.McpServer(id, name, url, headers,
                transport, command, args, env,
                enabled, existing.status(), existing.lastError(), existing.toolCount(),
                existing.createdAt(), LocalDateTime.now().toString());
        store.save(updated);
        if (!enabled) {
            manager.disconnect(id);
            store.updateStatus(id, "disabled", null, 0);
        } else if (enabledChanged || targetChanged) {
            Thread t = new Thread(() -> manager.connect(id), "mcp-patch-" + id);
            t.setDaemon(true);
            t.start();
        }
        return store.get(id).map(s -> ResponseEntity.ok(toMap(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/mcp/servers/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        if (store.get(id).isEmpty()) return ResponseEntity.notFound().build();
        manager.disconnect(id);
        store.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/mcp/servers/{id}/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@PathVariable String id) {
        if (store.get(id).isEmpty()) return ResponseEntity.notFound().build();
        McpServerStore.McpServer s = manager.refresh(id);
        return ResponseEntity.ok(toMap(s));
    }

    @GetMapping("/api/mcp/servers/{id}/tools")
    public ResponseEntity<List<Map<String, Object>>> tools(@PathVariable String id) {
        if (store.get(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(manager.registeredTools(id));
    }

    /** transport 归一：显式声明 > 按字段推断（给了 command 即 stdio）> 默认 http。 */
    private String normalizeTransport(String transport, String command, String url) {
        if (transport != null && !transport.isBlank()) {
            String t = transport.trim().toLowerCase();
            if (!"http".equals(t) && !"stdio".equals(t)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transport 仅支持 http/stdio");
            }
            return t;
        }
        if (command != null && !command.isBlank()) return "stdio";
        return "http";
    }

    /** args 归一：字符串按空白/双引号分词（shell 式），数组按元素取值；统一为 List<String>。 */
    private static List<String> normalizeArgs(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o));
            }
            return out;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        boolean hasContent = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == '"') inQuote = false;
                else cur.append(c);
            } else if (c == '"') {
                inQuote = true;
                hasContent = true;
            } else if (c == ' ' || c == '\t') {
                if (cur.length() > 0 || hasContent) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    hasContent = false;
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0 || hasContent) out.add(cur.toString());
        return out;
    }

    private Map<String, Object> toMap(McpServerStore.McpServer s) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("transport", s.transport());
        m.put("url", s.url());
        m.put("headers", s.headers());
        m.put("command", s.command());
        m.put("args", s.args());
        m.put("env", s.env());
        m.put("enabled", s.enabled());
        // 运行态优先取连接管理器内存值（DB 为持久化快照）
        String status = s.enabled() ? manager.statusOf(s.id()) : "disabled";
        m.put("status", status);
        m.put("lastError", s.lastError());
        m.put("toolCount", s.toolCount());
        m.put("createdAt", s.createdAt());
        m.put("updatedAt", s.updatedAt());
        return m;
    }
}
