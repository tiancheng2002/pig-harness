package com.example.harness.core.memory;

import com.example.harness.model.Knowledge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeStoreTest {
    @TempDir Path temp;

    @Test void legacyAutoKnowledgeNeedsReviewAndApprovalSurvivesRestart() throws Exception {
        Path path = temp.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE knowledge (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT, key TEXT, content TEXT, source TEXT, pinned INTEGER DEFAULT 0, created_at TEXT, updated_at TEXT, UNIQUE(category,key))");
            statement.executeUpdate("INSERT INTO knowledge(category,key,content,source,created_at,updated_at) VALUES('用户偏好','语言','偏好中文回答','auto','old','old')");
            statement.executeUpdate("INSERT INTO knowledge(category,key,content,source,created_at,updated_at) VALUES('用户偏好','格式','喜欢简洁结论','manual','old','old')");
        }
        long autoId;
        try (Db db = new Db(path.toString())) {
            KnowledgeStore store = new KnowledgeStore(db);
            assertEquals(1, store.list(null, "pending").size());
            assertEquals(1, store.list(null, "active").size());
            assertTrue(store.forPrompt("请用中文回答", null, 5).stream().noneMatch(k -> "语言".equals(k.key())));
            autoId = store.list(null, "pending").getFirst().id();
            assertTrue(store.update(autoId, null, null, null, null, "active", null));
        }
        try (Db db = new Db(path.toString())) {
            KnowledgeStore store = new KnowledgeStore(db);
            assertEquals("active", store.get(autoId).orElseThrow().status());
        }
    }

    @Test void pendingRejectedAndOtherProjectFactsAreNeverInjected() {
        try (Db db = new Db(temp.resolve("scope.db").toString())) {
            KnowledgeStore store = new KnowledgeStore(db);
            assertTrue(store.propose("项目事实", "统一响应", "统一响应使用 ResponseSchema", "project-a", "统一响应", "conv"));
            assertTrue(store.forPrompt("统一响应", "project-a", 5).isEmpty());
            Knowledge candidate = store.list(null, "pending").getFirst();
            assertTrue(store.update(candidate.id(), null, null, null, null, "active", null));
            assertTrue(store.forPrompt("统一响应", "project-b", 5).isEmpty());
            assertEquals(1, store.forPrompt("统一响应", "project-a", 5).size());

            Knowledge manual = store.insert(Knowledge.manual("用户偏好", "中文交流", "偏好中文回答"));
            assertTrue(store.update(manual.id(), null, null, null, true, null, null));
            assertEquals(1, store.forPrompt("无关请求", "project-b", 5).size());

            assertTrue(store.propose("工作习惯", "审查代码", "先运行测试", null, "先运行测试", "conv"));
            Knowledge pending = store.list(null, "pending").getFirst();
            assertTrue(store.update(pending.id(), null, null, null, null, "rejected", null));
            assertFalse(store.propose("工作习惯", "审查代码", "先运行测试", null, "先运行测试", "conv"));
        }
    }
}
