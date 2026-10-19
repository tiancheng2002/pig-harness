package com.example.harness.web;

import com.example.harness.core.ModelRegistry;
import com.example.harness.core.memory.ModelStore;
import com.example.harness.model.ModelProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模型档案 API：列表（不暴露 apiKey）/ 用户新增 / 修改 / 删除。
 * 内置档案（yml 预配）与用户档案（SQLite）合并返回，前端对话时可选择模型与深度思考。
 */
@RestController
public class ModelController {

    private static final Logger log = LoggerFactory.getLogger(ModelController.class);

    public record ModelRequest(String name, String baseUrl, String apiKey,
                               String model, String thinkingModel, Integer contextLength) {
    }

    private final ModelRegistry registry;
    private final ModelStore store;

    public ModelController(ModelRegistry registry, ModelStore store) {
        this.registry = registry;
        this.store = store;
    }

    @GetMapping("/api/models")
    public List<ModelProfile.View> list() {
        return registry.profiles().stream().map(ModelController::toView).toList();
    }

    @PostMapping("/api/models")
    public ResponseEntity<?> create(@RequestBody ModelRequest req) {
        String err = validate(req);
        if (err != null) {
            return ResponseEntity.badRequest().body(Map.of("error", err));
        }
        String id = "user-" + UUID.randomUUID().toString().substring(0, 8);
        ModelProfile p = store.upsert(new ModelProfile(id, req.name().trim(), req.baseUrl().trim(),
                req.apiKey() == null ? "" : req.apiKey().trim(), req.model().trim(),
                blankToNull(req.thinkingModel()), normalizeContextLength(req.contextLength()), false));
        log.info("新增模型档案: {} ({})", p.name(), p.id());
        return ResponseEntity.created(URI.create("/api/models/" + id)).body(toView(p));
    }

    @PatchMapping("/api/models/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ModelRequest req) {
        ModelProfile cur = registry.find(id).orElse(null);
        if (cur == null) {
            return ResponseEntity.notFound().build();
        }
        if (cur.builtin()) {
            return ResponseEntity.badRequest().body(Map.of("error", "内置模型不可修改"));
        }
        String name = req.name() != null && !req.name().isBlank() ? req.name().trim() : cur.name();
        String baseUrl = req.baseUrl() != null && !req.baseUrl().isBlank() ? req.baseUrl().trim() : cur.baseUrl();
        String apiKey = req.apiKey() != null && !req.apiKey().isBlank() ? req.apiKey().trim() : cur.apiKey();
        String model = req.model() != null && !req.model().isBlank() ? req.model().trim() : cur.model();
        String thinking = req.thinkingModel() != null ? blankToNull(req.thinkingModel()) : cur.thinkingModel();
        Integer ctxLen = req.contextLength() != null
                ? normalizeContextLength(req.contextLength()) : cur.contextLength();
        ModelProfile p = store.upsert(new ModelProfile(id, name, baseUrl, apiKey, model,
                thinking, ctxLen, false));
        return ResponseEntity.ok(toView(p));
    }

    @DeleteMapping("/api/models/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        ModelProfile cur = registry.find(id).orElse(null);
        if (cur == null) {
            return ResponseEntity.notFound().build();
        }
        if (cur.builtin()) {
            return ResponseEntity.badRequest().body(Map.of("error", "内置模型不可删除"));
        }
        store.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static ModelProfile.View toView(ModelProfile p) {
        boolean hasKey = p.apiKey() != null && !p.apiKey().isBlank();
        boolean thinking = p.thinkingModel() != null && !p.thinkingModel().isBlank();
        return new ModelProfile.View(p.id(), p.name(), p.baseUrl(), p.model(), p.thinkingModel(),
                hasKey, p.builtin(), thinking, p.contextLength());
    }

    /** 上下文长度入参收敛：null/非正数 → null（按全局默认兜底）。 */
    private static Integer normalizeContextLength(Integer v) {
        return v == null || v <= 0 ? null : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String validate(ModelRequest req) {
        if (req.name() == null || req.name().isBlank()) return "名称不能为空";
        if (req.baseUrl() == null || req.baseUrl().isBlank()) return "Base URL 不能为空";
        if (req.model() == null || req.model().isBlank()) return "模型名不能为空";
        return null;
    }
}
