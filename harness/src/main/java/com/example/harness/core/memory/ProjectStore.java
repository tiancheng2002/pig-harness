package com.example.harness.core.memory;

import com.example.harness.model.Project;
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
import java.util.UUID;

/**
 * 项目存储（projects 表）：本地文件夹项目的 CRUD。
 * - 同一根路径重复添加时返回已有项目（避免重复绑定）
 * - 删除项目仅解除会话绑定（project_id 置空），不删除会话与文件
 */
@Component
public class ProjectStore {

    private static final Logger log = LoggerFactory.getLogger(ProjectStore.class);

    private final Connection conn;

    public ProjectStore(Db db) {
        this.conn = db.connection();
    }

    /** 创建项目；同 rootPath 已存在时直接返回已有项目。 */
    public Project create(String name, String rootPath) {
        Optional<Project> existed = findByRootPath(rootPath);
        if (existed.isPresent()) {
            return existed.get();
        }
        String id = "p-" + UUID.randomUUID().toString().substring(0, 8);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO projects (id, name, root_path, created_at) VALUES (?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, rootPath);
            ps.setString(4, java.time.LocalDateTime.now().toString());
            ps.executeUpdate();
            return new Project(id, name, rootPath, java.time.LocalDateTime.now().toString());
        } catch (SQLException e) {
            throw new IllegalStateException("创建项目失败: " + e.getMessage(), e);
        }
    }

    /** 项目列表（含会话数，最近活跃在前）。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = """
                SELECT p.id, p.name, p.root_path, p.created_at,
                       (SELECT COUNT(*) FROM conversations c WHERE c.project_id = p.id) AS convs
                FROM projects p
                ORDER BY p.created_at DESC
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("id", rs.getString(1));
                item.put("name", rs.getString(2));
                item.put("rootPath", rs.getString(3));
                item.put("createdAt", rs.getString(4));
                item.put("conversationCount", rs.getInt(5));
                out.add(item);
            }
        } catch (SQLException e) {
            log.error("项目列表查询失败: {}", e.getMessage());
        }
        return out;
    }

    public Optional<Project> get(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, root_path, created_at FROM projects WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Project(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            log.error("项目查询失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<Project> findByRootPath(String rootPath) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, root_path, created_at FROM projects WHERE root_path = ?")) {
            ps.setString(1, rootPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Project(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            log.error("项目按路径查询失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** 删除项目并解除其会话的绑定（会话保留，回到普通对话模式）。 */
    public boolean delete(String id) {
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE conversations SET project_id = NULL WHERE project_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM projects WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.error("项目删除失败: {}", e.getMessage());
            return false;
        }
    }
}
