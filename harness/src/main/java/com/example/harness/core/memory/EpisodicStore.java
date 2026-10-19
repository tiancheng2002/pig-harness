package com.example.harness.core.memory;

import com.example.harness.model.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 情景记忆（F8）：执行轨迹持久化到 SQLite（共享 Db 数据源）。
 * 表结构见 M1 开发文档 §4.2（embeddings 表为 M2 预留）。
 */
@Component
public class EpisodicStore {

    private static final Logger log = LoggerFactory.getLogger(EpisodicStore.class);

    private final Connection conn;

    public EpisodicStore(Db db) {
        this.conn = db.connection();
    }

    public synchronized long save(Trace trace) {
        try {
            String stepsJson = trace.steps() == null ? "[]" : trace.steps();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO traces (task, created_at, steps, outcome) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, trace.task());
                ps.setString(2, trace.createdAt());
                ps.setString(3, stepsJson);
                ps.setString(4, trace.outcome());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
            return -1;
        } catch (SQLException e) {
            log.error("轨迹写入失败: {}", e.getMessage());
            return -1;
        }
    }

    public synchronized List<Trace> list(int limit) {
        List<Trace> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, task, created_at, steps, outcome FROM traces ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Trace(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            log.error("轨迹查询失败: {}", e.getMessage());
        }
        return out;
    }

    public synchronized Optional<Trace> get(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, task, created_at, steps, outcome FROM traces WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Trace(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            log.error("轨迹查询失败: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
