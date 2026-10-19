package com.example.harness.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 共享 SQLite 数据源：traces / embeddings / conversations / messages
 * 同一数据库文件单连接，供 EpisodicStore 与 ConversationStore 复用。
 */
@Component
public class Db implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Db.class);

    private final Connection conn;

    public Db(@Value("${harness.db-path:db/harness.db}") String dbPath) {
        try {
            Path p = Paths.get(dbPath).toAbsolutePath().normalize();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + p);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
            }
            initSchema();
            log.info("SQLite 数据库就绪: {}", p);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 SQLite 失败: " + e.getMessage(), e);
        }
    }

    public Connection connection() {
        return conn;
    }

    @Override
    @PreDestroy
    public void close() {
        try {
            if (!conn.isClosed()) conn.close();
        } catch (SQLException e) {
            log.warn("关闭 SQLite 连接失败: {}", e.getMessage());
        }
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            // ---- M1 主体：轨迹（F8）----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS traces (
                      id         INTEGER PRIMARY KEY AUTOINCREMENT,
                      task       TEXT,
                      created_at TEXT,
                      steps      TEXT,
                      outcome    TEXT
                    )
                    """);
            // ---- M2：技能库与向量检索 ----
            // 旧版占位 embeddings(trace_id, vector BLOB) 直接重建（M1 未写入有效数据）
            st.executeUpdate("DROP TABLE IF EXISTS old_embeddings_placeholder");
            try (var rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='embeddings'")) {
                if (rs.next()) {
                    // 如果存在旧表，检查列名；是旧占位则删除重建
                    try (var cols = st.getConnection().getMetaData()
                            .getColumns(null, null, "embeddings", "trace_id")) {
                        if (cols.next()) {
                            st.executeUpdate("DROP TABLE embeddings");
                        }
                    }
                }
            }
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS skills (
                      id          TEXT PRIMARY KEY,
                      name        TEXT,
                      description TEXT,
                      triggers    TEXT,
                      when_to_use TEXT,
                      file_path   TEXT UNIQUE,
                      enabled     INTEGER DEFAULT 1,
                      version     INTEGER DEFAULT 1,
                      created_at  TEXT,
                      updated_at  TEXT
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS embeddings (
                      id         INTEGER PRIMARY KEY AUTOINCREMENT,
                      skill_id   TEXT UNIQUE,
                      model      TEXT,
                      vector     TEXT,
                      updated_at TEXT,
                      FOREIGN KEY(skill_id) REFERENCES skills(id)
                    )
                    """);
            // ---- M1 补充：多会话（F12）----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS conversations (
                      id         TEXT PRIMARY KEY,
                      title      TEXT,
                      created_at TEXT,
                      updated_at TEXT
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS messages (
                      id              INTEGER PRIMARY KEY AUTOINCREMENT,
                      conversation_id TEXT,
                      role            TEXT,
                      content         TEXT,
                      events          TEXT,
                      created_at      TEXT,
                      FOREIGN KEY(conversation_id) REFERENCES conversations(id)
                    )
                    """);
            // 兼容已存在的旧库：messages 表补 events 列（F12 会话内事件流持久化）
            addColumnIfMissing(st, "messages", "events", "TEXT");
            // M2：assistant 消息关联轨迹
            addColumnIfMissing(st, "messages", "trace_id", "INTEGER");
            // ---- 多模型：用户自配模型档案 ----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS model_profiles (
                      id             TEXT PRIMARY KEY,
                      name           TEXT,
                      base_url       TEXT,
                      api_key        TEXT,
                      model          TEXT,
                      thinking_model TEXT,
                      created_at     TEXT,
                      updated_at     TEXT
                    )
                    """);
            // ---- 自进化记忆：知识（用户偏好/项目事实/工作习惯）----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS knowledge (
                      id         INTEGER PRIMARY KEY AUTOINCREMENT,
                      category   TEXT,
                      key        TEXT,
                      content    TEXT,
                      source     TEXT DEFAULT 'manual',
                      pinned     INTEGER DEFAULT 0,
                      created_at TEXT,
                      updated_at TEXT,
                      UNIQUE(category, key)
                    )
                    """);
            // ---- 项目模式：本地文件夹项目（会话可绑定，沙箱根切换到项目目录）----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS projects (
                      id         TEXT PRIMARY KEY,
                      name       TEXT,
                      root_path  TEXT,
                      created_at TEXT
                    )
                    """);
            addColumnIfMissing(st, "conversations", "project_id", "TEXT");
            // 上下文压缩：对话级摘要 + 已摘要消息水位线（messages.id）
            addColumnIfMissing(st, "conversations", "ctx_summary", "TEXT");
            addColumnIfMissing(st, "conversations", "ctx_summary_until_id", "INTEGER");
            // 最后一轮 LLM 请求的真实 prompt 用量（API usage 回传；容量徽标取 max(真实, 估算)）
            addColumnIfMissing(st, "conversations", "last_prompt_tokens", "INTEGER");
            // 模型上下文窗口长度（token），空 = 全局默认
            addColumnIfMissing(st, "model_profiles", "context_length", "INTEGER");
            // 技能来源：system（系统内置）/ user（用户创建或从轨迹合成）
            addColumnIfMissing(st, "skills", "origin", "TEXT");
            // 技能形态（M5）：steps（步骤脚本）/ prompt（散文指南，注入 AgentLoop 执行）
            addColumnIfMissing(st, "skills", "mode", "TEXT");
            // ---- M5 外部能力：MCP 服务器（Streamable HTTP + stdio 本地进程）----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mcp_servers (
                      id           TEXT PRIMARY KEY,
                      name         TEXT,
                      url          TEXT,
                      headers_json TEXT,
                      transport    TEXT DEFAULT 'http',
                      command      TEXT,
                      args_json    TEXT,
                      env_json     TEXT,
                      enabled      INTEGER DEFAULT 1,
                      status       TEXT,
                      last_error   TEXT,
                      tool_count   INTEGER DEFAULT 0,
                      created_at   TEXT,
                      updated_at   TEXT
                    )
                    """);
            // stdio 传输扩展：旧库补列（transport 默认 http 与既有数据语义一致）
            addColumnIfMissing(st, "mcp_servers", "transport", "TEXT DEFAULT 'http'");
            addColumnIfMissing(st, "mcp_servers", "command", "TEXT");
            addColumnIfMissing(st, "mcp_servers", "args_json", "TEXT");
            addColumnIfMissing(st, "mcp_servers", "env_json", "TEXT");
            // ---- P0 自进化：成功任务向量（聚类计数，触发技能合成闸门）----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS task_vectors (
                      id              INTEGER PRIMARY KEY AUTOINCREMENT,
                      conversation_id TEXT,
                      trace_id        INTEGER,
                      cluster_id      INTEGER,
                      vector          TEXT,
                      keywords        TEXT,
                      steps_count     INTEGER DEFAULT 0,
                      created_at      TEXT
                    )
                    """);
            // ---- P0 自进化：技能合成草稿（pending → validating → validated/rejected → committed）----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS skill_drafts (
                      id                INTEGER PRIMARY KEY AUTOINCREMENT,
                      name              TEXT,
                      content           TEXT,
                      sample_test_input TEXT,
                      status            TEXT DEFAULT 'pending',
                      report_json       TEXT,
                      trace_id          INTEGER,
                      cluster_id        INTEGER,
                      created_at        TEXT,
                      updated_at        TEXT
                    )
                    """);
            // ---- P0 自进化：技能使用统计（复用次数 / 失败次数 / 最近使用 / 是否合成产物）----
            addColumnIfMissing(st, "skills", "hit_count", "INTEGER DEFAULT 0");
            addColumnIfMissing(st, "skills", "fail_count", "INTEGER DEFAULT 0");
            addColumnIfMissing(st, "skills", "last_used_at", "TEXT");
            addColumnIfMissing(st, "skills", "synth", "INTEGER DEFAULT 0");
            // ---- P1：代码语义索引分块（scope = 工作根指纹）----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS code_chunks (
                      id           INTEGER PRIMARY KEY AUTOINCREMENT,
                      scope        TEXT,
                      path         TEXT,
                      start_line   INTEGER,
                      end_line     INTEGER,
                      content      TEXT,
                      vector       TEXT,
                      content_hash TEXT,
                      updated_at   TEXT
                    )
                    """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chunks_scope ON code_chunks(scope, path)");
            // ---- P1：会话用量流水（每轮主泳道 LLM 调用一行，成本观测数据源）----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS usage_log (
                      id                INTEGER PRIMARY KEY AUTOINCREMENT,
                      conversation_id   TEXT,
                      model             TEXT,
                      prompt_tokens     INTEGER,
                      completion_tokens INTEGER,
                      estimated         INTEGER DEFAULT 0,
                      created_at        TEXT
                    )
                    """);
            // 旧自动知识先进入审核队列；NULL 仅出现在迁移前的数据，已审核条目不会被重置。
            addColumnIfMissing(st, "knowledge", "status", "TEXT");
            addColumnIfMissing(st, "knowledge", "project_id", "TEXT");
            addColumnIfMissing(st, "knowledge", "evidence", "TEXT");
            addColumnIfMissing(st, "knowledge", "source_conversation_id", "TEXT");
            st.executeUpdate("UPDATE knowledge SET status='pending' WHERE status IS NULL AND source='auto'");
            st.executeUpdate("UPDATE knowledge SET status='active' WHERE status IS NULL");
            // ---- P4 Task Runtime v2：任务运行、模型调用与工具调用明细 ----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS task_runs (
                      id                    TEXT PRIMARY KEY,
                      conversation_id       TEXT,
                      user_message_id       INTEGER,
                      assistant_message_id  INTEGER,
                      trace_id              INTEGER,
                      project_id             TEXT,
                      root_fingerprint       TEXT,
                      mode                   TEXT,
                      status                 TEXT,
                      model_requested        TEXT,
                      started_at             TEXT,
                      finished_at            TEXT,
                      wall_duration_ms       INTEGER DEFAULT 0,
                      first_token_ms         INTEGER,
                      approval_wait_ms       INTEGER DEFAULT 0,
                      llm_calls               INTEGER DEFAULT 0,
                      tool_calls              INTEGER DEFAULT 0,
                      prompt_tokens          INTEGER DEFAULT 0,
                      completion_tokens      INTEGER DEFAULT 0,
                      estimated_calls        INTEGER DEFAULT 0,
                      cost_usd               REAL,
                      outcome_json           TEXT,
                      error_code             TEXT,
                      error_summary          TEXT,
                      created_at              TEXT,
                      updated_at              TEXT
                    )
                    """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_task_runs_conversation "
                    + "ON task_runs(conversation_id, created_at)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_task_runs_message "
                    + "ON task_runs(user_message_id)");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS llm_calls (
                      id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                      task_run_id        TEXT,
                      phase              TEXT,
                      agent_id           TEXT,
                      model              TEXT,
                      prompt_tokens      INTEGER,
                      completion_tokens  INTEGER,
                      cached_tokens      INTEGER,
                      estimated          INTEGER DEFAULT 0,
                      started_at         TEXT,
                      duration_ms        INTEGER,
                      first_token_ms     INTEGER,
                      cost_usd           REAL,
                      status             TEXT,
                      error_summary      TEXT,
                      FOREIGN KEY(task_run_id) REFERENCES task_runs(id)
                    )
                    """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_llm_calls_run ON llm_calls(task_run_id, id)");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS tool_calls (
                      id             INTEGER PRIMARY KEY AUTOINCREMENT,
                      task_run_id    TEXT,
                      call_id        TEXT,
                      agent_id       TEXT,
                      name           TEXT,
                      target         TEXT,
                      mutating       INTEGER DEFAULT 0,
                      started_at     TEXT,
                      duration_ms    INTEGER,
                      status         TEXT,
                      error_summary  TEXT,
                      FOREIGN KEY(task_run_id) REFERENCES task_runs(id)
                    )
                    """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tool_calls_run ON tool_calls(task_run_id, id)");
            addColumnIfMissing(st, "messages", "task_run_id", "TEXT");
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_assistant_run "
                    + "ON messages(task_run_id) WHERE role='assistant' AND task_run_id IS NOT NULL");
            // ---- 子智能体体系：主 Agent 可 spawnAgent 派出的可复用角色（内置 + 用户自建）----
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS agent_profiles (
                      id         TEXT PRIMARY KEY,
                      name       TEXT UNIQUE,
                      description TEXT,
                      prompt     TEXT,
                      tools_json TEXT,
                      max_steps  INTEGER,
                      enabled    INTEGER DEFAULT 1,
                      origin     TEXT DEFAULT 'user',
                      created_at TEXT,
                      updated_at TEXT
                    )
                    """);
        }
    }

    private void addColumnIfMissing(Statement st, String table, String column, String type) {
        try (var rs = st.getConnection().getMetaData()
                .getColumns(null, null, table, column)) {
            if (!rs.next()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            }
        } catch (SQLException e) {
            log.warn("补列 {}:{} 失败: {}", table, column, e.getMessage());
        }
    }
}
