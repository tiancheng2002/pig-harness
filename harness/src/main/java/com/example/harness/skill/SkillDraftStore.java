package com.example.harness.skill;

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
import java.util.Map;
import java.util.Optional;

/**
 * P0 自进化：技能合成草稿存储（skill_drafts 表）。
 * 状态机：pending → validating → validated / rejected → committed（reject/commit 为终态）。
 */
@Component
public class SkillDraftStore {

    private static final Logger log = LoggerFactory.getLogger(SkillDraftStore.class);

    public static final String PENDING = "pending";
    public static final String VALIDATING = "validating";
    public static final String VALIDATED = "validated";
    public static final String REJECTED = "rejected";
    public static final String COMMITTED = "committed";

    private final Connection conn;

    public SkillDraftStore(Db db) {
        this.conn = db.connection();
    }

    public record SkillDraft(long id, String name, String content, String sampleTestInput,
                             String status, String reportJson, Long traceId, Integer clusterId,
                             String createdAt, String updatedAt) {
    }

    private static final String COLUMNS =
            "id, name, content, sample_test_input, status, report_json, trace_id, cluster_id, created_at, updated_at";

    public synchronized long insert(SkillDraft draft) {
        String now = LocalDateTime.now().toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO skill_drafts (name, content, sample_test_input, status, report_json, trace_id, cluster_id, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, draft.name());
            ps.setString(2, draft.content());
            ps.setString(3, draft.sampleTestInput());
            ps.setString(4, draft.status() == null ? PENDING : draft.status());
            ps.setString(5, draft.reportJson());
            if (draft.traceId() == null) ps.setNull(6, java.sql.Types.INTEGER);
            else ps.setLong(6, draft.traceId());
            if (draft.clusterId() == null) ps.setNull(7, java.sql.Types.INTEGER);
            else ps.setInt(7, draft.clusterId());
            ps.setString(8, now);
            ps.setString(9, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        } catch (SQLException e) {
            log.error("保存技能草稿失败: {}", e.getMessage());
        }
        return -1;
    }

    public synchronized Optional<SkillDraft> get(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM skill_drafts WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            log.error("查询技能草稿失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** status=null 时返回全部草稿，按更新时间倒序。 */
    public synchronized List<SkillDraft> list(String status) {
        List<SkillDraft> out = new ArrayList<>();
        String sql = "SELECT " + COLUMNS + " FROM skill_drafts"
                + (status == null || status.isBlank() ? "" : " WHERE status = ?")
                + " ORDER BY updated_at DESC, id DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (status != null && !status.isBlank()) ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            log.error("列出技能草稿失败: {}", e.getMessage());
        }
        return out;
    }

    /** 按状态计数（进化仪表盘）。 */
    public synchronized Map<String, Integer> countByStatus() {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String s : List.of(PENDING, VALIDATING, VALIDATED, REJECTED, COMMITTED)) out.put(s, 0);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status, COUNT(*) FROM skill_drafts GROUP BY status");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        } catch (SQLException e) {
            log.error("统计技能草稿状态失败: {}", e.getMessage());
        }
        return out;
    }

    /** 该聚类是否已有未终态草稿（防止同一簇重复合成）。 */
    public synchronized boolean existsActiveForCluster(int clusterId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM skill_drafts WHERE cluster_id = ? AND status IN (?, ?, ?)")) {
            ps.setInt(1, clusterId);
            ps.setString(2, PENDING);
            ps.setString(3, VALIDATING);
            ps.setString(4, VALIDATED);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("查询聚类草稿失败: {}", e.getMessage());
            return true; // 查询异常时保守处理：视为已存在，避免重复合成
        }
    }

    public synchronized boolean updateStatus(long id, String status) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE skill_drafts SET status = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, LocalDateTime.now().toString());
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("更新草稿状态失败: {}", e.getMessage());
            return false;
        }
    }

    /** 重新合成后更新内容（重试时报告回灌），状态回到 pending。 */
    public synchronized boolean updateContent(long id, String content, String name, String reportJson) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE skill_drafts SET content = ?, name = ?, report_json = ?, status = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, content);
            ps.setString(2, name);
            ps.setString(3, reportJson);
            ps.setString(4, PENDING);
            ps.setString(5, LocalDateTime.now().toString());
            ps.setLong(6, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("更新草稿内容失败: {}", e.getMessage());
            return false;
        }
    }

    public synchronized boolean updateReport(long id, String reportJson) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE skill_drafts SET report_json = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, reportJson);
            ps.setString(2, LocalDateTime.now().toString());
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("更新草稿报告失败: {}", e.getMessage());
            return false;
        }
    }

    public synchronized boolean delete(long id) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM skill_drafts WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("删除技能草稿失败: {}", e.getMessage());
            return false;
        }
    }

    private SkillDraft map(ResultSet rs) throws SQLException {
        long traceId = rs.getLong(7);
        boolean traceNull = rs.wasNull();
        int clusterId = rs.getInt(8);
        boolean clusterNull = rs.wasNull();
        return new SkillDraft(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), traceNull ? null : traceId,
                clusterNull ? null : clusterId, rs.getString(9), rs.getString(10));
    }
}
