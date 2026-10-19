package com.example.harness.core.memory;

import com.example.harness.model.Knowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 已确认的长期知识才可进入提示词；自动提取始终先进入审核队列。 */
@Component
public class KnowledgeStore {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeStore.class);
    private static final String COLUMNS = "id, category, key, content, source, pinned, status, project_id, evidence, source_conversation_id, created_at, updated_at";
    private static final Pattern TERMS = Pattern.compile("[a-z0-9_.-]{3,}|[\\p{IsHan}]{2,}");
    private static final Set<String> GENERIC = Set.of("帮我", "请你", "一下", "这个", "那个", "继续", "任务", "项目", "需要", "进行", "完成");

    private final Db db;

    public KnowledgeStore(Db db) { this.db = db; }

    public List<Knowledge> list(String category) { return list(category, null); }

    public List<Knowledge> list(String category, String status) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM knowledge WHERE 1=1");
        boolean filterCategory = category != null && !category.isBlank() && !"全部".equals(category);
        boolean filterStatus = status != null && !status.isBlank() && !"all".equals(status);
        if (filterCategory) sql.append(" AND category=?");
        if (filterStatus) sql.append(" AND status=?");
        sql.append(" ORDER BY pinned DESC, updated_at DESC");
        List<Knowledge> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(sql.toString())) {
            int i = 1;
            if (filterCategory) ps.setString(i++, category);
            if (filterStatus) ps.setString(i, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        } catch (Exception e) {
            log.warn("读取知识失败: {}", e.getMessage());
        }
        return out;
    }

    /** 已启用、同项目、相关的少量记忆；置顶最多两条，避免提示词随知识库增长。 */
    public List<Knowledge> forPrompt(String prompt, String projectId, int limit) {
        if (limit <= 0) return List.of();
        List<Knowledge> candidates = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT " + COLUMNS + " FROM knowledge "
                + "WHERE status='active' AND ((category IN ('用户偏好','工作习惯') AND (project_id IS NULL OR project_id=?)) "
                + "OR (category='项目事实' AND project_id=?))")) {
            ps.setString(1, projectId);
            ps.setString(2, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) candidates.add(read(rs));
            }
        } catch (Exception e) {
            log.warn("读取提示词知识失败: {}", e.getMessage());
            return List.of();
        }
        String query = prompt == null ? "" : prompt.trim().toLowerCase();
        candidates.removeIf(k -> !query.isEmpty() && !k.pinned() && relevance(query, k) == 0);
        candidates.sort(Comparator.comparing(Knowledge::pinned).reversed()
                .thenComparing(Comparator.comparingInt((Knowledge k) -> relevance(query, k)).reversed())
                .thenComparing(Knowledge::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Knowledge> selected = new ArrayList<>();
        int pinned = 0, chars = 0;
        for (Knowledge k : candidates) {
            if (k.pinned() && pinned >= 2) continue;
            int size = k.key().length() + Math.min(120, k.content().length());
            if (selected.size() >= Math.min(limit, 5)) break;
            if (chars + size > 600) continue;
            selected.add(k);
            chars += size;
            if (k.pinned()) pinned++;
        }
        return selected;
    }

    private static int relevance(String query, Knowledge k) {
        if (query.isBlank()) return 0;
        String memory = (k.key() + " " + k.content()).toLowerCase();
        Set<String> terms = new HashSet<>();
        Matcher m = TERMS.matcher(query);
        while (m.find()) {
            String term = m.group();
            if (term.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                for (int i = 0; i < term.length() - 1; i++) terms.add(term.substring(i, i + 2));
            } else terms.add(term);
        }
        int score = 0;
        for (String term : terms) if (!GENERIC.contains(term) && memory.contains(term)) score++;
        return score;
    }

    public Optional<Knowledge> get(long id) {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT " + COLUMNS + " FROM knowledge WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(read(rs));
            }
        } catch (Exception e) {
            log.warn("读取知识失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public Knowledge insert(Knowledge k) {
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT INTO knowledge (category, key, content, source, pinned, status, project_id,
                                       evidence, source_conversation_id, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            String now = LocalDateTime.now().toString();
            ps.setString(1, k.category());
            ps.setString(2, k.key());
            ps.setString(3, k.content());
            ps.setString(4, k.source() == null ? "manual" : k.source());
            ps.setInt(5, k.pinned() ? 1 : 0);
            ps.setString(6, k.status() == null ? "active" : k.status());
            ps.setString(7, k.projectId());
            ps.setString(8, k.evidence());
            ps.setString(9, k.sourceConversationId());
            ps.setString(10, now);
            ps.setString(11, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return new Knowledge(keys.getLong(1), k.category(), k.key(), k.content(),
                        k.source(), k.pinned(), k.status(), k.projectId(), k.evidence(), k.sourceConversationId(), now, now);
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存知识失败: " + e.getMessage(), e);
        }
        return k;
    }

    /** 候选与已确认/已忽略知识冲突时不覆盖；避免同一段事实被反复提出。 */
    public boolean propose(String category, String key, String content, String projectId,
                           String evidence, String conversationId) {
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT OR IGNORE INTO knowledge (category, key, content, source, pinned, status, project_id,
                                                 evidence, source_conversation_id, created_at, updated_at)
                SELECT ?,?,?,'auto',0,'pending',?,?,?, ?,?
                WHERE NOT EXISTS (SELECT 1 FROM knowledge WHERE category=? AND content=?
                                  AND COALESCE(project_id,'')=COALESCE(? ,''))
                """)) {
            String now = LocalDateTime.now().toString();
            ps.setString(1, category);
            ps.setString(2, key);
            ps.setString(3, content);
            ps.setString(4, projectId);
            ps.setString(5, evidence);
            ps.setString(6, conversationId);
            ps.setString(7, now);
            ps.setString(8, now);
            ps.setString(9, category);
            ps.setString(10, content);
            ps.setString(11, projectId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.warn("写入知识候选失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean update(long id, String category, String key, String content, Boolean pinned,
                          String status, String projectId) {
        Optional<Knowledge> cur = get(id);
        if (cur.isEmpty()) return false;
        Knowledge k = cur.get();
        String nextCategory = category == null ? k.category() : category;
        String nextProject = projectId != null ? (projectId.isBlank() ? null : projectId) : k.projectId();
        if (!"项目事实".equals(nextCategory) && category != null && projectId == null) nextProject = null;
        String nextStatus = status == null ? k.status() : status;
        if ("active".equals(nextStatus) && "项目事实".equals(nextCategory) && nextProject == null) {
            throw new IllegalArgumentException("项目事实需先绑定项目才能启用");
        }
        try (PreparedStatement ps = db.connection().prepareStatement("""
                UPDATE knowledge SET category=?, key=?, content=?, pinned=?, status=?, project_id=?, updated_at=? WHERE id=?
                """)) {
            ps.setString(1, nextCategory);
            ps.setString(2, key == null ? k.key() : key);
            ps.setString(3, content == null ? k.content() : content);
            ps.setInt(4, pinned == null ? (k.pinned() ? 1 : 0) : (pinned ? 1 : 0));
            ps.setString(5, nextStatus);
            ps.setString(6, nextProject);
            ps.setString(7, LocalDateTime.now().toString());
            ps.setLong(8, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.warn("更新知识失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean delete(long id) {
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM knowledge WHERE id=?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.warn("删除知识失败: {}", e.getMessage());
            return false;
        }
    }

    private static Knowledge read(ResultSet rs) throws java.sql.SQLException {
        return new Knowledge(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6) == 1, rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12));
    }
}
