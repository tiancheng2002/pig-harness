package com.example.harness.embedding;

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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 技能向量存储：每技能一行，支持增量/全量维护。
 */
@Component
public class EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStore.class);

    private final Connection conn;
    private final ObjectMapper om;

    public EmbeddingStore(Db db, ObjectMapper om) {
        this.conn = db.connection();
        this.om = om;
    }

    public synchronized void save(String skillId, String model, float[] vector) {
        String json;
        try {
            json = om.writeValueAsString(vector);
        } catch (Exception e) {
            log.error("向量序列化失败: {}", e.getMessage());
            return;
        }
        String now = LocalDateTime.now().toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO embeddings (skill_id, model, vector, updated_at) VALUES (?,?,?,?)"
                        + " ON CONFLICT(skill_id) DO UPDATE SET model=excluded.model, vector=excluded.vector, updated_at=excluded.updated_at")) {
            ps.setString(1, skillId);
            ps.setString(2, model);
            ps.setString(3, json);
            ps.setString(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("保存 embedding 失败: {}", e.getMessage());
        }
    }

    public synchronized void deleteBySkillId(String skillId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM embeddings WHERE skill_id = ?")) {
            ps.setString(1, skillId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("删除 embedding 失败: {}", e.getMessage());
        }
    }

    public synchronized List<EmbeddingRow> findAll() {
        List<EmbeddingRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT skill_id, model, vector FROM embeddings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String skillId = rs.getString(1);
                String model = rs.getString(2);
                String json = rs.getString(3);
                float[] vector = parseVector(json);
                if (vector != null) {
                    out.add(new EmbeddingRow(skillId, model, vector));
                }
            }
        } catch (SQLException e) {
            log.error("查询 embeddings 失败: {}", e.getMessage());
        }
        return out;
    }

    public synchronized Optional<EmbeddingRow> findBySkillId(String skillId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT skill_id, model, vector FROM embeddings WHERE skill_id = ?")) {
            ps.setString(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    float[] vector = parseVector(rs.getString(3));
                    if (vector != null) {
                        return Optional.of(new EmbeddingRow(rs.getString(1), rs.getString(2), vector));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("查询 embedding 失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private float[] parseVector(String json) {
        try {
            List<Float> list = om.readValue(json, new TypeReference<List<Float>>() {
            });
            float[] out = new float[list.size()];
            for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
            return out;
        } catch (Exception e) {
            log.warn("向量解析失败: {}", e.getMessage());
            return null;
        }
    }

    public record EmbeddingRow(String skillId, String model, float[] vector) {
    }
}
