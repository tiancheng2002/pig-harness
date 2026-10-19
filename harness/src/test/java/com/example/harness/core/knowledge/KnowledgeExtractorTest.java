package com.example.harness.core.knowledge;

import com.example.harness.core.memory.Db;
import com.example.harness.core.memory.KnowledgeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeExtractorTest {
    @TempDir Path temp;

    @Test void onlyLatestUserEvidenceCreatesPendingCandidate() {
        try (Db db = new Db(temp.resolve("evidence.db").toString())) {
            KnowledgeStore store = new KnowledgeStore(db);
            KnowledgeExtractor extractor = new KnowledgeExtractor(null, null, store, null, null);
            String candidate = """
                    [{"category":"用户偏好","key":"回答语言","content":"用户偏好中文回答", "evidence":"后续回答用中文"}]
                    """;
            assertEquals(0, extractor.parseAndSave(candidate, "帮我写一个页面", null, "conv"));
            assertEquals(1, extractor.parseAndSave(candidate, "请记住，后续回答用中文。", null, "conv"));
            assertEquals(0, extractor.parseAndSave(candidate, "请记住，后续回答用中文。", null, "conv"));
            assertEquals("pending", store.list(null, "pending").getFirst().status());
            assertEquals("后续回答用中文", store.list(null, "pending").getFirst().evidence());

            String projectFact = """
                    [{"category":"项目事实","key":"响应规范","content":"项目统一使用 ResponseSchema", "evidence":"项目统一使用 ResponseSchema"}]
                    """;
            assertEquals(0, extractor.parseAndSave(projectFact, "项目统一使用 ResponseSchema", null, "conv"));
        }
    }
}
