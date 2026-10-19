package com.example.harness.web;

import com.example.harness.core.knowledge.KnowledgeExtractor;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.memory.KnowledgeStore;
import com.example.harness.core.memory.ProjectStore;
import com.example.harness.model.Knowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识（长期记忆）API：自进化记忆的 CRUD 与手动提取。
 */
@RestController
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    public record KnowledgeRequest(String category, String key, String content, Boolean pinned,
                                   String status, String projectId) {
    }
    private static final Set<String> STATUSES = Set.of("pending", "active", "archived", "rejected");

    private final KnowledgeStore store;
    private final KnowledgeExtractor extractor;
    private final ConversationStore conversationStore;
    private final ProjectStore projects;

    public KnowledgeController(KnowledgeStore store, KnowledgeExtractor extractor,
                               ConversationStore conversationStore, ProjectStore projects) {
        this.store = store;
        this.extractor = extractor;
        this.conversationStore = conversationStore;
        this.projects = projects;
    }

    @GetMapping("/api/knowledge")
    public Map<String, Object> list(@RequestParam(required = false) String category,
                                    @RequestParam(required = false) String status) {
        List<Knowledge> items = store.list(category, status);
        Map<String, Long> byCategory = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Knowledge k : store.list(null)) {
            byCategory.merge(k.category(), 1L, Long::sum);
            byStatus.merge(k.status(), 1L, Long::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", items.size());
        out.put("byCategory", byCategory);
        out.put("byStatus", byStatus);
        out.put("conversationCount", conversationStore.list().size());
        return out;
    }

    @PostMapping("/api/knowledge")
    public ResponseEntity<?> create(@RequestBody KnowledgeRequest req) {
        String category = normalizeCategory(req.category());
        if (req.key() == null || req.key().isBlank() || req.content() == null || req.content().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "标题与内容不能为空"));
        }
        String projectId;
        try { projectId = validProject(req.projectId()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        if ("项目事实".equals(category) && projectId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "项目事实需选择所属项目"));
        }
        Knowledge manual = Knowledge.manual(category, req.key().trim(), req.content().trim());
        try {
            return ResponseEntity.ok(store.insert(new Knowledge(null, manual.category(), manual.key(), manual.content(),
                    manual.source(), false, "active", projectId, null, null, null, null)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "同分类下已存在同名知识"));
        }
    }

    @PatchMapping("/api/knowledge/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody KnowledgeRequest req) {
        if (req.status() != null && !STATUSES.contains(req.status())) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效知识状态"));
        }
        // 兼容旧客户端的“忽略”请求：不再保留 rejected 记录。
        if ("rejected".equals(req.status())) return delete(id);
        String projectId;
        try { projectId = validProject(req.projectId()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        boolean ok;
        try {
            ok = store.update(id,
                    req.category() == null ? null : normalizeCategory(req.category()),
                    req.key(), req.content(), req.pinned(), req.status(), projectId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ok ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/api/knowledge/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        return store.delete(id)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.notFound().build();
    }

    /** 手动触发：从指定会话（或最近的会话）提取记忆。 */
    @PostMapping("/api/knowledge/extract")
    public ResponseEntity<?> extract(@RequestBody(required = false) Map<String, String> body) {
        String conversationId = body == null ? null : body.get("conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            var convs = conversationStore.list();
            if (convs.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "暂无会话可提取"));
            }
            conversationId = convs.get(0).id();
        }
        int added = extractor.extractNow(conversationId);
        log.info("[knowledge] 手动提取: 会话 {} -> {} 条待审核候选", conversationId, added);
        return ResponseEntity.ok(Map.of("added", added));
    }

    private static String normalizeCategory(String c) {
        List<String> allowed = new ArrayList<>(List.of("用户偏好", "项目事实", "工作习惯", "其他"));
        return c != null && allowed.contains(c) ? c : "其他";
    }

    private String validProject(String id) {
        if (id == null || id.isBlank()) return null;
        if (projects.get(id).isEmpty()) throw new IllegalArgumentException("项目不存在");
        return id;
    }
}
