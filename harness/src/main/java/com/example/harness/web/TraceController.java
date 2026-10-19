package com.example.harness.web;

import com.example.harness.core.memory.EpisodicStore;
import com.example.harness.model.Trace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 轨迹查询接口（F8 验收：可读回轨迹）。 */
@RestController
public class TraceController {

    private final EpisodicStore store;
    private final ObjectMapper om;

    public TraceController(EpisodicStore store, ObjectMapper om) {
        this.store = store;
        this.om = om;
    }

    @GetMapping("/api/traces")
    public List<Trace> list(@RequestParam(defaultValue = "50") int limit) {
        return store.list(limit);
    }

    @GetMapping("/api/traces/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
        return store.get(id)
                .map(t -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("id", t.id());
                    body.put("task", t.task());
                    body.put("createdAt", t.createdAt());
                    body.put("outcome", t.outcome());
                    try {
                        body.put("steps", om.readTree(t.steps() == null ? "[]" : t.steps()));
                    } catch (Exception e) {
                        body.put("steps", List.of());
                    }
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
