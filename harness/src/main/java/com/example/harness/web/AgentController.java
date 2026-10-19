package com.example.harness.web;

import com.example.harness.core.memory.AgentProfileStore;
import com.example.harness.model.AgentProfile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 子智能体管理 API（体验升级七）：清单 / 新建 / 修改（含启停）/ 删除。
 * 内置档案（origin=builtin，如「代码探索」）可禁用不可删除；删除后重启自动播种恢复。
 * 主 Agent 经 spawnAgent(agent=名称, task=…) 按需派出。
 */
@RestController
public class AgentController {

    private final AgentProfileStore store;

    public AgentController(AgentProfileStore store) {
        this.store = store;
    }

    public record CreateRequest(String name, String description, String prompt,
                                List<String> tools, Integer maxSteps, Boolean enabled) {
    }

    public record PatchRequest(String name, String description, String prompt,
                               List<String> tools, Integer maxSteps, Boolean enabled) {
    }

    @GetMapping("/api/agents")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentProfile p : store.list()) {
            out.add(toMap(p));
        }
        return out;
    }

    @PostMapping("/api/agents")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 不能为空");
        }
        if (req.prompt() == null || req.prompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt（职责说明）不能为空");
        }
        String name = req.name().trim();
        if (store.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同名子智能体已存在: " + name);
        }
        AgentProfile created = store.upsert(new AgentProfile(
                UUID.randomUUID().toString().substring(0, 8),
                name,
                req.description() == null ? "" : req.description().trim(),
                req.prompt().trim(),
                normalizeTools(req.tools()),
                req.maxSteps(),
                req.enabled() == null || req.enabled(),
                AgentProfile.ORIGIN_USER,
                null, null));
        return ResponseEntity.status(HttpStatus.CREATED).body(toMap(created));
    }

    @PatchMapping("/api/agents/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable String id,
                                                     @RequestBody PatchRequest req) {
        AgentProfile existing = store.get(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        String name = req.name() != null && !req.name().isBlank() ? req.name().trim() : existing.name();
        if (!name.equalsIgnoreCase(existing.name()) && store.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同名子智能体已存在: " + name);
        }
        AgentProfile updated = store.upsert(new AgentProfile(
                existing.id(),
                name,
                req.description() != null ? req.description().trim() : existing.description(),
                req.prompt() != null && !req.prompt().isBlank() ? req.prompt().trim() : existing.prompt(),
                req.tools() != null ? normalizeTools(req.tools()) : existing.allowedTools(),
                req.maxSteps() != null ? req.maxSteps() : existing.maxSteps(),
                req.enabled() != null ? req.enabled() : existing.enabled(),
                existing.origin(),
                existing.createdAt(), null));
        return ResponseEntity.ok(toMap(updated));
    }

    @DeleteMapping("/api/agents/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        AgentProfile existing = store.get(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (AgentProfile.ORIGIN_BUILTIN.equals(existing.origin())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "内置子智能体不可删除（可禁用）: " + existing.name());
        }
        store.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 工具白名单去重去空；空 = 不限制（spawnAgent 对子智能体始终关闭）。 */
    private static List<String> normalizeTools(List<String> tools) {
        if (tools == null) return List.of();
        return tools.stream()
                .filter(t -> t != null && !t.isBlank() && !"spawnAgent".equals(t.trim()))
                .map(String::trim)
                .distinct()
                .toList();
    }

    private Map<String, Object> toMap(AgentProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("name", p.name());
        m.put("description", p.description());
        m.put("prompt", p.prompt());
        m.put("tools", p.allowedTools());
        m.put("maxSteps", p.maxSteps());
        m.put("enabled", p.enabled());
        m.put("origin", p.origin());
        m.put("createdAt", p.createdAt());
        m.put("updatedAt", p.updatedAt());
        return m;
    }
}
