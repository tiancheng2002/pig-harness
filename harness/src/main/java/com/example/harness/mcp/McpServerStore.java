package com.example.harness.mcp;

import com.example.harness.core.memory.Db;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * mcp_servers 表 CRUD（M5-F6）：服务器清单存 DB，与模型档案管理方式一致。
 * transport 支持两种（stdio 扩展）：http（Streamable HTTP，url/headers）与
 * stdio（本地子进程，command/args/env）。
 */
@Component
public class McpServerStore {

    private static final Logger log = LoggerFactory.getLogger(McpServerStore.class);

    /** 一条 MCP 服务器配置。headers 为 HTTP 附加请求头；args 为启动参数列表；env 为子进程附加环境变量。 */
    public record McpServer(String id, String name, String url, Map<String, String> headers,
                            String transport, String command, List<String> args, Map<String, String> env,
                            boolean enabled, String status, String lastError, int toolCount,
                            String createdAt, String updatedAt) {

        public boolean isStdio() {
            return "stdio".equals(transport);
        }
    }

    private static final String COLUMNS =
            "id, name, url, headers_json, transport, command, args_json, env_json, "
                    + "enabled, status, last_error, tool_count, created_at, updated_at";

    private final Connection conn;
    private final ObjectMapper om;

    public McpServerStore(Db db, ObjectMapper om) {
        this.conn = db.connection();
        this.om = om;
    }

    public synchronized List<McpServer> listAll() {
        List<McpServer> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM mcp_servers ORDER BY created_at ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) {
            log.error("列出 mcp_servers 失败: {}", e.getMessage());
        }
        return out;
    }

    public synchronized Optional<McpServer> get(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM mcp_servers WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            log.error("查询 mcp_server 失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** 插入或整体更新（除状态字段外；状态由连接管理器单独更新）。 */
    public synchronized void save(McpServer s) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mcp_servers (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT(id) DO UPDATE SET name=excluded.name, url=excluded.url, "
                        + "headers_json=excluded.headers_json, transport=excluded.transport, "
                        + "command=excluded.command, args_json=excluded.args_json, env_json=excluded.env_json, "
                        + "enabled=excluded.enabled, updated_at=excluded.updated_at")) {
            ps.setString(1, s.id());
            ps.setString(2, s.name());
            ps.setString(3, s.url());
            ps.setString(4, toJson(s.headers()));
            ps.setString(5, s.transport() == null || s.transport().isBlank() ? "http" : s.transport());
            ps.setString(6, s.command());
            ps.setString(7, toJson(s.args() == null ? List.of() : s.args()));
            ps.setString(8, toJson(s.env()));
            ps.setInt(9, s.enabled() ? 1 : 0);
            ps.setString(10, s.status());
            ps.setString(11, s.lastError());
            ps.setInt(12, s.toolCount());
            ps.setString(13, s.createdAt());
            ps.setString(14, s.updatedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("保存 mcp_server 失败: {}", e.getMessage());
        }
    }

    /** 连接管理器回写运行状态。 */
    public synchronized void updateStatus(String id, String status, String lastError, int toolCount) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mcp_servers SET status=?, last_error=?, tool_count=?, updated_at=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setString(2, lastError);
            ps.setInt(3, toolCount);
            ps.setString(4, java.time.LocalDateTime.now().toString());
            ps.setString(5, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("更新 mcp_server 状态失败: {}", e.getMessage());
        }
    }

    public synchronized boolean delete(String id) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM mcp_servers WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("删除 mcp_server 失败: {}", e.getMessage());
            return false;
        }
    }

    private McpServer map(ResultSet rs) throws SQLException {
        return new McpServer(
                rs.getString("id"), rs.getString("name"), rs.getString("url"),
                parseMap(rs.getString("headers_json")),
                rs.getString("transport"), rs.getString("command"),
                parseList(rs.getString("args_json")), parseMap(rs.getString("env_json")),
                rs.getInt("enabled") == 1, rs.getString("status"), rs.getString("last_error"),
                rs.getInt("tool_count"), rs.getString("created_at"), rs.getString("updated_at"));
    }

    private String toJson(Object value) {
        try {
            return om.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, String> parseMap(String json) {
        try {
            return om.readValue(json == null || json.isBlank() ? "{}" : json,
                    new TypeReference<Map<String, String>>() {
                    });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> parseList(String json) {
        try {
            return om.readValue(json == null || json.isBlank() ? "[]" : json,
                    new TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }
}
