package com.example.harness.core.memory;

import com.example.harness.model.ModelProfile;
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
 * 用户自配模型档案的 SQLite 持久化（model_profiles 表）。
 */
@Component
public class ModelStore {

    private static final Logger log = LoggerFactory.getLogger(ModelStore.class);

    private final Db db;

    public ModelStore(Db db) {
        this.db = db;
    }

    public List<ModelProfile> list() {
        List<ModelProfile> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT id, name, base_url, api_key, model, thinking_model, context_length "
                        + "FROM model_profiles ORDER BY created_at")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ModelProfile(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), readInt(rs, 7), false));
                }
            }
        } catch (Exception e) {
            log.warn("读取模型档案失败: {}", e.getMessage());
        }
        return out;
    }

    public Optional<ModelProfile> get(String id) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT id, name, base_url, api_key, model, thinking_model, context_length "
                        + "FROM model_profiles WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ModelProfile(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), readInt(rs, 7), false));
                }
            }
        } catch (Exception e) {
            log.warn("读取模型档案失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public ModelProfile upsert(ModelProfile p) {
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT INTO model_profiles (id, name, base_url, api_key, model, thinking_model, context_length, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                  name=excluded.name, base_url=excluded.base_url, api_key=excluded.api_key,
                  model=excluded.model, thinking_model=excluded.thinking_model,
                  context_length=excluded.context_length, updated_at=excluded.updated_at
                """)) {
            String now = LocalDateTime.now().toString();
            ps.setString(1, p.id());
            ps.setString(2, p.name());
            ps.setString(3, p.baseUrl());
            ps.setString(4, p.apiKey());
            ps.setString(5, p.model());
            ps.setString(6, p.thinkingModel());
            if (p.contextLength() == null || p.contextLength() <= 0) {
                ps.setNull(7, java.sql.Types.INTEGER);
            } else {
                ps.setInt(7, p.contextLength());
            }
            ps.setString(8, now);
            ps.setString(9, now);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("保存模型档案失败: " + e.getMessage(), e);
        }
        return p;
    }

    /** 可空整数列读取（列可能刚迁移出来，历史行为 NULL）。 */
    private static Integer readInt(ResultSet rs, int col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public boolean delete(String id) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM model_profiles WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.warn("删除模型档案失败: {}", e.getMessage());
            return false;
        }
    }
}
