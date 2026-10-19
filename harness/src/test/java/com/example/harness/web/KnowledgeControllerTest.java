package com.example.harness.web;

import com.example.harness.core.memory.Db;
import com.example.harness.core.memory.KnowledgeStore;
import com.example.harness.core.memory.ProjectStore;
import com.example.harness.model.Knowledge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeControllerTest {
    @TempDir Path temp;

    @Test void projectFactsNeedScopeAndInvalidReviewStatesAreRejected() {
        try (Db db = new Db(temp.resolve("api.db").toString())) {
            KnowledgeController controller = new KnowledgeController(new KnowledgeStore(db), null, null, new ProjectStore(db));
            var missingProject = controller.create(new KnowledgeController.KnowledgeRequest(
                    "项目事实", "接口", "统一响应", null, null, null));
            assertEquals(400, missingProject.getStatusCode().value());

            var created = controller.create(new KnowledgeController.KnowledgeRequest(
                    "用户偏好", "语言", "中文回答", null, null, null));
            assertEquals(200, created.getStatusCode().value());
            Knowledge knowledge = (Knowledge) created.getBody();
            var invalidStatus = controller.update(knowledge.id(), new KnowledgeController.KnowledgeRequest(
                    null, null, null, null, "unreviewed", null));
            assertEquals(400, invalidStatus.getStatusCode().value());

            var ignored = controller.update(knowledge.id(), new KnowledgeController.KnowledgeRequest(
                    null, null, null, null, "rejected", null));
            assertEquals(200, ignored.getStatusCode().value());
            assertTrue(new KnowledgeStore(db).get(knowledge.id()).isEmpty());
        }
    }
}
