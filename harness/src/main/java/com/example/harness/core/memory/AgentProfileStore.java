package com.example.harness.core.memory;

import com.example.harness.model.AgentProfile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 子智能体档案的 SQLite 持久化（agent_profiles 表）。
 */
@Component
public class AgentProfileStore {

    private static final Logger log = LoggerFactory.getLogger(AgentProfileStore.class);

    private final Db db;
    private final ObjectMapper om = new ObjectMapper();

    public AgentProfileStore(Db db) {
        this.db = db;
    }

    public List<AgentProfile> list() {
        List<AgentProfile> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT id, name, description, prompt, tools_json, max_steps, enabled, origin, created_at, updated_at "
                        + "FROM agent_profiles ORDER BY origin DESC, created_at")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (Exception e) {
            log.warn("读取子智能体档案失败: {}", e.getMessage());
        }
        return out;
    }

    public List<AgentProfile> listEnabled() {
        return list().stream().filter(AgentProfile::enabled).toList();
    }

    public Optional<AgentProfile> get(String id) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT id, name, description, prompt, tools_json, max_steps, enabled, origin, created_at, updated_at "
                        + "FROM agent_profiles WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(read(rs));
                }
            }
        } catch (Exception e) {
            log.warn("读取子智能体档案失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** 按名称查找（spawnAgent 的 agent 参数用名称引用，大小写不敏感）。 */
    public Optional<AgentProfile> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT id, name, description, prompt, tools_json, max_steps, enabled, origin, created_at, updated_at "
                        + "FROM agent_profiles WHERE lower(name) = lower(?)")) {
            ps.setString(1, name.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(read(rs));
                }
            }
        } catch (Exception e) {
            log.warn("按名称读取子智能体档案失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public AgentProfile upsert(AgentProfile p) {
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT INTO agent_profiles (id, name, description, prompt, tools_json, max_steps, enabled, origin, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                  name=excluded.name, description=excluded.description, prompt=excluded.prompt,
                  tools_json=excluded.tools_json, max_steps=excluded.max_steps, enabled=excluded.enabled,
                  updated_at=excluded.updated_at
                """)) {
            String now = LocalDateTime.now().toString();
            ps.setString(1, p.id());
            ps.setString(2, p.name());
            ps.setString(3, p.description());
            ps.setString(4, p.prompt());
            ps.setString(5, writeTools(p.allowedTools()));
            if (p.maxSteps() == null || p.maxSteps() <= 0) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, p.maxSteps());
            }
            ps.setInt(7, p.enabled() ? 1 : 0);
            ps.setString(8, p.origin() == null ? AgentProfile.ORIGIN_USER : p.origin());
            ps.setString(9, p.createdAt() == null ? now : p.createdAt());
            ps.setString(10, now);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("保存子智能体档案失败: " + e.getMessage(), e);
        }
        return p;
    }

    public boolean delete(String id) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM agent_profiles WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.warn("删除子智能体档案失败: {}", e.getMessage());
            return false;
        }
    }

    private AgentProfile read(ResultSet rs) throws SQLException {
        return new AgentProfile(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                readTools(rs.getString(5)), readInt(rs, 6), rs.getInt(7) != 0,
                rs.getString(8), rs.getString(9), rs.getString(10));
    }

    private List<String> readTools(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> arr = om.readValue(json, new TypeReference<List<String>>() {
            });
            return arr == null ? List.of() : arr;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) return "[]";
        try {
            return om.writeValueAsString(tools);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static Integer readInt(ResultSet rs, int col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
