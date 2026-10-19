package com.example.harness.core.evolution;

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
 * P0 自进化：成功任务向量存储（task_vectors 表）。
 * 每个未命中技能的成功任务记一行（向量或关键词），按簇聚合；簇内计数达到阈值触发技能合成。
 */
@Component
public class TaskVectorStore {

    private static final Logger log = LoggerFactory.getLogger(TaskVectorStore.class);

    private final Connection conn;

    public TaskVectorStore(Db db) {
        this.conn = db.connection();
    }

    public record TaskVector(long id, String conversationId, long traceId, int clusterId,
                             String vector, String keywords, int stepsCount, String createdAt) {
    }

    public synchronized boolean insert(String conversationId, long traceId, int clusterId,
                                       String vectorJson, String keywords, int stepsCount) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO task_vectors (conversation_id, trace_id, cluster_id, vector, keywords, steps_count, created_at) "
                        + "VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, conversationId);
            ps.setLong(2, traceId);
            ps.setInt(3, clusterId);
            ps.setString(4, vectorJson);
            ps.setString(5, keywords);
            ps.setInt(6, stepsCount);
            ps.setString(7, LocalDateTime.now().toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("写入任务向量失败: {}", e.getMessage());
            return false;
        }
    }

    /** 最近的 N 条（聚类比较窗口）。 */
    public synchronized List<TaskVector> listRecent(int limit) {
        List<TaskVector> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, conversation_id, trace_id, cluster_id, vector, keywords, steps_count, created_at "
                        + "FROM task_vectors ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            log.error("读取任务向量失败: {}", e.getMessage());
        }
        return out;
    }

    public synchronized int countByCluster(int clusterId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM task_vectors WHERE cluster_id = ?")) {
            ps.setInt(1, clusterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("统计簇失败: {}", e.getMessage());
            return 0;
        }
    }

    public synchronized int countAll() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM task_vectors")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 现有最大簇 id（新簇 = max+1）。 */
    public synchronized int maxClusterId() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(cluster_id), 0) FROM task_vectors")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 非技能任务的平均步数（节省估算基线）；样本不足返回 -1。 */
    public synchronized double avgSteps(int minSamples) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*), COALESCE(AVG(steps_count), 0) FROM task_vectors")) {
            if (rs.next() && rs.getInt(1) >= minSamples) {
                return rs.getDouble(2);
            }
        } catch (SQLException e) {
            log.warn("统计平均步数失败: {}", e.getMessage());
        }
        return -1;
    }

    private TaskVector map(ResultSet rs) throws SQLException {
        return new TaskVector(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8));
    }
}
