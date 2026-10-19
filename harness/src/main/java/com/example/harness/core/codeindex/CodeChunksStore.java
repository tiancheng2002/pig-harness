package com.example.harness.core.codeindex;

import com.example.harness.core.memory.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * P1 代码语义索引存储（code_chunks 表）。
 * scope = 工作根指纹（绝对路径 SHA-256 前 16 位），同一库支撑多项目/工作区索引互不干扰。
 */
@Component
public class CodeChunksStore {

    private static final Logger log = LoggerFactory.getLogger(CodeChunksStore.class);

    private final Connection conn;

    public CodeChunksStore(Db db) {
        this.conn = db.connection();
    }

    public record Chunk(long id, String scope, String path, int startLine, int endLine,
                        String content, float[] vector, String contentHash) {
    }

    public synchronized int countByScope(String scope) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM code_chunks WHERE scope = ?")) {
            ps.setString(1, scope);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public synchronized List<Chunk> listByScope(String scope) {
        List<Chunk> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, scope, path, start_line, end_line, content, vector, content_hash "
                        + "FROM code_chunks WHERE scope = ? ORDER BY path, start_line")) {
            ps.setString(1, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            log.error("读取代码分块失败: {}", e.getMessage());
        }
        return out;
    }

    public synchronized void deleteByPath(String scope, String path) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM code_chunks WHERE scope = ? AND path = ?")) {
            ps.setString(1, scope);
            ps.setString(2, path);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("删除代码分块失败: {}", e.getMessage());
        }
    }

    public synchronized void deleteByScope(String scope) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM code_chunks WHERE scope = ?")) {
            ps.setString(1, scope);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("清空代码索引失败: {}", e.getMessage());
        }
    }

    /** 单文件整体替换写入（先删后插，事务由 SQLite 单连接顺序保证）。 */
    public synchronized void replacePath(String scope, String path, List<Chunk> chunks) {
        deleteByPath(scope, path);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO code_chunks (scope, path, start_line, end_line, content, vector, content_hash, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)")) {
            for (Chunk c : chunks) {
                ps.setString(1, scope);
                ps.setString(2, c.path());
                ps.setInt(3, c.startLine());
                ps.setInt(4, c.endLine());
                ps.setString(5, c.content());
                ps.setString(6, c.vector() == null ? null : toJson(c.vector()));
                ps.setString(7, c.contentHash());
                ps.setString(8, LocalDateTime.now().toString());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.error("写入代码分块失败: {}", e.getMessage());
        }
    }

    /** 已索引文件的 content_hash（增量去重用）：path → hash。 */
    public synchronized java.util.Map<String, String> hashesByScope(String scope) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT path, MAX(content_hash) FROM code_chunks WHERE scope = ? GROUP BY path")) {
            ps.setString(1, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            log.warn("读取索引哈希失败: {}", e.getMessage());
        }
        return out;
    }

    private Chunk map(ResultSet rs) throws SQLException {
        String vec = rs.getString(7);
        return new Chunk(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getInt(5), rs.getString(6),
                vec == null ? new float[0] : fromJson(vec), rs.getString(8));
    }

    private String toJson(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private float[] fromJson(String json) {
        try {
            String body = json.substring(1, json.length() - 1);
            if (body.isBlank()) return new float[0];
            String[] parts = body.split(",");
            float[] out = new float[parts.length];
            for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i].trim());
            return out;
        } catch (Exception e) {
            return new float[0];
        }
    }
}
