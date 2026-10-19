package com.example.harness.core.memory;

import com.example.harness.model.Conversation;
import com.example.harness.model.ConversationSummary;
import com.example.harness.model.StoredMessage;
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
import java.util.UUID;

/**
 * 会话存储（F12/F14）：conversations / messages 表 CRUD。
 * - 新建会话自动生成 UUID
 * - 首条用户消息自动生成标题（未被用户重命名时）
 * - 每条消息落库并刷新会话 updated_at
 */
@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    private final Connection conn;

    public ConversationStore(Db db) {
        this.conn = db.connection();
    }

    public Conversation create(String projectId) {
        String id = UUID.randomUUID().toString();
        String now = LocalDateTime.now().toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO conversations (id, title, created_at, updated_at, project_id) VALUES (?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, null);
            ps.setString(3, now);
            ps.setString(4, now);
            ps.setString(5, projectId);
            ps.executeUpdate();
            return new Conversation(id, null, now, now, projectId);
        } catch (SQLException e) {
            throw new IllegalStateException("创建会话失败: " + e.getMessage(), e);
        }
    }

    public List<ConversationSummary> list() {
        List<ConversationSummary> out = new ArrayList<>();
        String sql = """
                SELECT c.id, c.title, c.updated_at,
                       (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS cnt,
                       c.project_id, p.name
                FROM conversations c
                LEFT JOIN projects p ON p.id = c.project_id
                ORDER BY c.updated_at DESC
                LIMIT 200
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ConversationSummary(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6)));
            }
        } catch (SQLException e) {
            log.error("会话列表查询失败: {}", e.getMessage());
        }
        return out;
    }

    public Optional<Conversation> get(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, title, created_at, updated_at, project_id FROM conversations WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Conversation(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            log.error("会话查询失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public boolean exists(String id) {
        return get(id).isPresent();
    }

    public List<StoredMessage> messages(String conversationId) {
        List<StoredMessage> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, conversation_id, role, content, events, created_at, trace_id, task_run_id "
                        + "FROM messages WHERE conversation_id = ? ORDER BY id ASC")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long traceIdCol = rs.getLong(7);
                    out.add(new StoredMessage(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5),
                            rs.wasNull() ? null : traceIdCol, rs.getString(8), rs.getString(6)));
                }
            }
        } catch (SQLException e) {
            log.error("消息查询失败: {}", e.getMessage());
        }
        return out;
    }

    /** 对话级压缩摘要：摘要文本 + 已摘要到的消息水位线（messages.id，之前的消息进摘要）。 */
    public record ContextSummary(String summary, long untilId) {
    }

    public Optional<ContextSummary> contextSummary(String conversationId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ctx_summary, ctx_summary_until_id FROM conversations WHERE id = ?")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String s = rs.getString(1);
                    long until = rs.getLong(2);
                    if (s != null && !s.isBlank()) {
                        return Optional.of(new ContextSummary(s, rs.wasNull() ? 0L : until));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("读取对话摘要失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** 保存压缩摘要与新水位线（覆盖旧值）。 */
    public void saveContextSummary(String conversationId, String summary, long untilId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE conversations SET ctx_summary = ?, ctx_summary_until_id = ? WHERE id = ?")) {
            ps.setString(1, summary);
            ps.setLong(2, untilId);
            ps.setString(3, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("保存对话摘要失败: {}", e.getMessage());
        }
    }

    /** 记录最后一轮 LLM 请求的真实 prompt 用量（不刷新 updated_at，避免影响侧栏排序）。 */
    public void updateLastPromptTokens(String conversationId, int tokens) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE conversations SET last_prompt_tokens = ? WHERE id = ?")) {
            ps.setInt(1, Math.max(0, tokens));
            ps.setString(2, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("更新会话 prompt 用量失败: {}", e.getMessage());
        }
    }

    /** 最后一轮 LLM 请求的真实 prompt 用量（从未调用过返回 0）。 */
    public int lastPromptTokens(String conversationId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT last_prompt_tokens FROM conversations WHERE id = ?")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int v = rs.getInt(1);
                    return rs.wasNull() ? 0 : Math.max(0, v);
                }
            }
        } catch (SQLException e) {
            log.warn("读取会话 prompt 用量失败: {}", e.getMessage());
        }
        return 0;
    }

    /** 根据轨迹 id 反查所属会话（技能合成时用于取对话上下文）。 */
    public Optional<String> findConversationIdByTrace(long traceId) {        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT conversation_id FROM messages WHERE trace_id = ? ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString(1));
            }
        } catch (SQLException e) {
            log.error("按轨迹查会话失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** 追加消息并刷新会话活跃时间。 */
    public long addMessage(String conversationId, String role, String content,
                           String eventsJson, Long traceId) {
        return addMessage(conversationId, role, content, eventsJson, traceId, null);
    }

    /** 追加消息并返回数据库 ID；taskRunId 为空时兼容旧调用。 */
    public long addMessage(String conversationId, String role, String content,
                           String eventsJson, Long traceId, String taskRunId) {
        synchronized (conn) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO messages (conversation_id, role, content, events, trace_id, task_run_id, created_at) VALUES (?,?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, conversationId);
            ps.setString(2, role);
            ps.setString(3, content);
            ps.setString(4, eventsJson);
            if (traceId == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setLong(5, traceId);
            }
            ps.setString(6, taskRunId);
            ps.setString(7, LocalDateTime.now().toString());
            ps.executeUpdate();
            touch(conversationId);
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException e) {
            log.error("消息写入失败: {}", e.getMessage());
            return -1L;
        }
        }
    }

    /** 首条用户消息自动生成标题（仅在未手动命名时）。 */
    public void autoTitle(String conversationId, String prompt) {
        if (prompt == null || prompt.isBlank()) return;
        String title = prompt.replaceAll("\\s+", " ").trim();
        if (title.length() > 24) title = title.substring(0, 24) + "…";
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE conversations SET title = ? WHERE id = ? "
                        + "AND (title IS NULL OR title = '' OR title = '新对话')")) {
            ps.setString(1, title);
            ps.setString(2, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("自动命名失败: {}", e.getMessage());
        }
    }

    public boolean rename(String conversationId, String title) {
        if (title == null || title.isBlank()) return false;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE conversations SET title = ? WHERE id = ?")) {
            ps.setString(1, title.trim());
            ps.setString(2, conversationId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("会话重命名失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 删除某条消息及其之后的全部消息（消息编辑重发：撤回最后一轮）。
     * 只会用于删除最后一条用户消息起的尾部消息——恒在对话摘要水位线（ctx_summary_until_id，
     * 只指向更早的消息）之后，不影响已生成的上下文摘要。
     *
     * @return 实际删除的条数（消息不属于该会话时为 0，幂等）
     */
    public int deleteFrom(String conversationId, long fromMessageId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM messages WHERE conversation_id = ? AND id >= ?")) {
            ps.setString(1, conversationId);
            ps.setLong(2, fromMessageId);
            int n = ps.executeUpdate();
            if (n > 0) {
                touch(conversationId);
            }
            return n;
        } catch (SQLException e) {
            log.error("消息截断失败: {}", e.getMessage());
            return -1;
        }
    }

    /** 删除会话及其全部消息（F14：删除后列表不再显示）。 */
    public boolean delete(String conversationId) {
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM messages WHERE conversation_id = ?")) {
                ps.setString(1, conversationId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM conversations WHERE id = ?")) {
                ps.setString(1, conversationId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.error("会话删除失败: {}", e.getMessage());
            return false;
        }
    }

    private void touch(String conversationId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE conversations SET updated_at = ? WHERE id = ?")) {
            ps.setString(1, LocalDateTime.now().toString());
            ps.setString(2, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("会话活跃时间更新失败: {}", e.getMessage());
        }
    }
}
