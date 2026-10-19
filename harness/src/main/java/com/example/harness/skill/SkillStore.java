package com.example.harness.skill;

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
import java.util.Optional;

/**
 * SQLite skills 表 CRUD。
 */
@Component
public class SkillStore {

    private static final Logger log = LoggerFactory.getLogger(SkillStore.class);

    private final Connection conn;
    private final ObjectMapper om;

    public SkillStore(Db db, ObjectMapper om) {
        this.conn = db.connection();
        this.om = om;
    }

    public synchronized void save(Skill skill) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO skills (id, name, description, triggers, when_to_use, file_path, enabled, version, created_at, updated_at, origin, mode) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT(id) DO UPDATE SET name=excluded.name, description=excluded.description, "
                        + "triggers=excluded.triggers, when_to_use=excluded.when_to_use, file_path=excluded.file_path, "
                        + "enabled=excluded.enabled, version=excluded.version, updated_at=excluded.updated_at, "
                        + "origin=excluded.origin, mode=excluded.mode")) {
            ps.setString(1, skill.id());
            ps.setString(2, skill.name());
            ps.setString(3, skill.description());
            ps.setString(4, toJson(skill.triggers()));
            ps.setString(5, skill.whenToUse());
            ps.setString(6, skill.filePath());
            ps.setInt(7, skill.enabled() ? 1 : 0);
            ps.setInt(8, skill.version());
            ps.setString(9, skill.createdAt());
            ps.setString(10, skill.updatedAt());
            ps.setString(11, skill.origin());
            ps.setString(12, skill.mode());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("保存 skill 失败: {}", e.getMessage());
        }
    }

    private static final String SKILL_COLUMNS =
            "id, name, description, triggers, when_to_use, file_path, enabled, version, created_at, updated_at, origin, mode";

    /** P0 技能使用统计（不在 Skill record 中，按 id 侧车查询，避免动全部构造点）。 */
    public record SkillStats(int hitCount, int failCount, String lastUsedAt, int synth) {
    }

    /** 全部技能的统计侧车表（单查询，供列表页合并渲染）。 */
    public synchronized java.util.Map<String, SkillStats> allStats() {
        java.util.Map<String, SkillStats> out = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, COALESCE(hit_count,0), COALESCE(fail_count,0), last_used_at, COALESCE(synth,0) FROM skills");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), new SkillStats(rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getInt(5)));
            }
        } catch (SQLException e) {
            log.error("查询技能统计失败: {}", e.getMessage());
        }
        return out;
    }

    /** 技能被执行（命中）一次：hit_count+1 并刷新 last_used_at。 */
    public synchronized void incrementHit(String id) {
        bump(id, "hit_count");
    }

    /** 技能执行失败一次：fail_count+1。 */
    public synchronized void incrementFail(String id) {
        bump(id, "fail_count");
    }

    /** 标记技能为自动合成产物（synth=1）。 */
    public synchronized void markSynthesized(String id) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE skills SET synth = 1 WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("标记合成技能失败: {}", e.getMessage());
        }
    }

    private void bump(String id, String column) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE skills SET " + column + " = COALESCE(" + column + ",0) + 1, "
                        + "last_used_at = ? WHERE id = ?")) {
            ps.setString(1, java.time.LocalDateTime.now().toString());
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("更新技能统计 {} 失败: {}", column, e.getMessage());
        }
    }

    public synchronized Optional<Skill> get(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + SKILL_COLUMNS + " FROM skills WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            log.error("查询 skill 失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized Optional<Skill> getByFilePath(String filePath) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + SKILL_COLUMNS + " FROM skills WHERE file_path = ?")) {
            ps.setString(1, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            log.error("按路径查询 skill 失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized List<Skill> listAll() {
        List<Skill> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + SKILL_COLUMNS + " FROM skills ORDER BY updated_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) {
            log.error("列出 skills 失败: {}", e.getMessage());
        }
        return out;
    }

    public synchronized List<Skill> listEnabled() {
        return listAll().stream().filter(Skill::enabled).toList();
    }

    public synchronized boolean delete(String id) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM skills WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("删除 skill 失败: {}", e.getMessage());
            return false;
        }
    }

    private Skill map(ResultSet rs) throws SQLException {
        String id = rs.getString(1);
        String name = rs.getString(2);
        String description = rs.getString(3);
        List<String> triggers = parseTriggers(rs.getString(4));
        String whenToUse = rs.getString(5);
        String filePath = rs.getString(6);
        boolean enabled = rs.getInt(7) == 1;
        int version = rs.getInt(8);
        String createdAt = rs.getString(9);
        String updatedAt = rs.getString(10);
        String origin = rs.getString(11);
        String mode = rs.getString(12);
        return new Skill(id, name, description, triggers, whenToUse, null,
                origin == null || origin.isBlank() ? "user" : origin,
                mode == null || mode.isBlank() ? "steps" : mode, filePath,
                enabled, version, createdAt, updatedAt, List.of(), java.util.Set.of());
    }

    private String toJson(List<String> list) {
        try {
            return om.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> parseTriggers(String json) {
        try {
            return om.readValue(json == null ? "[]" : json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
