package com.example.harness.web;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.evolution.EvolutionPipeline;
import com.example.harness.core.evolution.TaskVectorStore;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.memory.EpisodicStore;
import com.example.harness.core.memory.KnowledgeStore;
import com.example.harness.model.Knowledge;
import com.example.harness.model.StoredMessage;
import com.example.harness.model.Trace;
import com.example.harness.skill.Skill;
import com.example.harness.skill.SkillDraftStore;
import com.example.harness.skill.SkillLibrary;
import com.example.harness.skill.SkillStore;
import com.example.harness.skill.SkillSynthesizer;
import com.example.harness.skill.SkillValidator;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 技能库管理 API（M2）：列表 / 创建 / 详情 / 更新 / 删除 / 重建索引 / 存为技能。
 */
@RestController
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillLibrary skillLibrary;
    private final EpisodicStore episodicStore;
    private final ConversationStore conversationStore;
    private final SkillSynthesizer synthesizer;
    private final SkillStore skillStore;
    private final SkillDraftStore draftStore;
    private final EvolutionPipeline evolutionPipeline;
    private final KnowledgeStore knowledgeStore;
    private final TaskVectorStore taskVectorStore;
    private final HarnessProperties props;
    private final com.fasterxml.jackson.databind.ObjectMapper om;

    public SkillController(SkillLibrary skillLibrary, EpisodicStore episodicStore,
                           ConversationStore conversationStore, SkillSynthesizer synthesizer,
                           SkillStore skillStore, SkillDraftStore draftStore,
                           EvolutionPipeline evolutionPipeline, KnowledgeStore knowledgeStore,
                           TaskVectorStore taskVectorStore, HarnessProperties props,
                           com.fasterxml.jackson.databind.ObjectMapper om) {
        this.skillLibrary = skillLibrary;
        this.episodicStore = episodicStore;
        this.conversationStore = conversationStore;
        this.synthesizer = synthesizer;
        this.skillStore = skillStore;
        this.draftStore = draftStore;
        this.evolutionPipeline = evolutionPipeline;
        this.knowledgeStore = knowledgeStore;
        this.taskVectorStore = taskVectorStore;
        this.props = props;
        this.om = om;
    }

    public record CreateRequest(String name, String content) {
    }

    public record PatchRequest(Boolean enabled, String name, String description) {
    }

    public record FromTraceRequest(long traceId, String name) {
    }

    /** P0：手动创建草稿（导入 / E2E 构造用例）。 */
    public record DraftRequest(String name, String content, String sampleTestInput) {
    }

    @GetMapping("/api/skills")
    public List<Map<String, Object>> list() {
        // 系统技能在前、用户技能在后，各按更新时间倒序，保证前端分区展示稳定
        return toSummaries(skillLibrary.list().stream()
                .sorted(java.util.Comparator
                        .comparing((Skill s) -> "system".equals(s.origin()) ? 0 : 1)
                        .thenComparing(s -> s.updatedAt() == null ? "" : s.updatedAt(),
                                java.util.Comparator.reverseOrder()))
                .toList());
    }

    @PostMapping("/api/skills")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest req) {
        if (req.content() == null || req.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 不能为空");
        }
        Skill skill = skillLibrary.create(req.name(), req.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(skill));
    }

    @GetMapping("/api/skills/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return skillLibrary.get(id)
                .map(s -> ResponseEntity.ok(toDetail(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/api/skills/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable String id,
                                                     @RequestBody PatchRequest req) {
        if (!skillLibrary.update(id, req.name(), req.description(), req.enabled())) {
            return ResponseEntity.notFound().build();
        }
        return skillLibrary.get(id)
                .map(s -> ResponseEntity.ok(toDetail(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/skills/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        if (!skillLibrary.delete(id)) {
            return ResponseEntity.notFound().build();
        }
        log.info("技能已删除: {}", id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/skills/{id}/reindex")
    public ResponseEntity<Map<String, Object>> reindex(@PathVariable String id) {
        if (skillLibrary.get(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        skillLibrary.reindex(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/skills/from-trace")
    public ResponseEntity<Map<String, Object>> fromTrace(@RequestBody FromTraceRequest req) {
        Optional<Trace> traceOpt = episodicStore.get(req.traceId());
        if (traceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Trace trace = traceOpt.get();

        // 结合本会话最近几轮对话上下文，让 LLM 生成有意义的名称/描述/触发词（Mock 或失败时确定性兜底）
        SkillSynthesizer.DraftMeta meta = synthesizer.synthesize(trace, recentContext(trace.id()));
        String name = req.name() == null || req.name().isBlank()
                ? meta.name() : req.name().trim();
        String content = synthesizer.buildSkillMarkdown(name, meta, trace);
        Skill skill = skillLibrary.create(name, content);
        skillLibrary.update(skill.id(), null, null, false); // 草稿默认禁用
        log.info("轨迹 #{} 已合成为草稿技能: {}（{}）", trace.id(), skill.name(), skill.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(skill));
    }

    /** 取轨迹所属会话的最近 8 条消息（供合成器理解任务背景）。 */
    private List<StoredMessage> recentContext(long traceId) {
        try {
            Optional<String> convId = conversationStore.findConversationIdByTrace(traceId);
            if (convId.isEmpty()) return List.of();
            List<StoredMessage> all = conversationStore.messages(convId.get());
            return all.size() > 8 ? all.subList(all.size() - 8, all.size()) : all;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> toSummary(Skill s) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.id());
        m.put("name", s.name() == null ? "" : s.name());
        m.put("description", s.description() == null ? "" : s.description());
        m.put("origin", s.origin() == null ? "user" : s.origin());
        m.put("mode", s.mode() == null ? "steps" : s.mode());
        m.put("enabled", s.enabled());
        m.put("version", s.version());
        m.put("triggers", s.triggers());
        m.put("whenToUse", s.whenToUse() == null ? "" : s.whenToUse());
        m.put("filePath", s.filePath() == null ? "" : s.filePath());
        m.put("updatedAt", s.updatedAt() == null ? "" : s.updatedAt());
        return m;
    }

    /** 列表视图：合并 P0 使用统计侧车（hit_count/fail_count/last_used_at/synth）。 */
    private List<Map<String, Object>> toSummaries(List<Skill> skills) {
        Map<String, SkillStore.SkillStats> stats = skillStore.allStats();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Skill s : skills) {
            Map<String, Object> m = toSummary(s);
            SkillStore.SkillStats st = stats.get(s.id());
            m.put("hitCount", st == null ? 0 : st.hitCount());
            m.put("failCount", st == null ? 0 : st.failCount());
            m.put("lastUsedAt", st == null || st.lastUsedAt() == null ? "" : st.lastUsedAt());
            m.put("synth", st != null && st.synth() == 1);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> toDetail(Skill s) {
        String content = "";
        if (s.source() != null) {
            content = s.source();
        } else if (s.filePath() != null) {
            try {
                content = java.nio.file.Files.readString(java.nio.file.Paths.get(s.filePath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                content = "(读取失败: " + e.getMessage() + ")";
            }
        }
        Map<String, Object> map = new java.util.HashMap<>(toSummary(s));
        map.put("content", content);
        map.put("steps", s.steps().stream().map(step -> Map.of(
                "index", step.index(),
                "tool", step.tool(),
                "args", step.args(),
                "rawLine", step.rawLine())).toList());
        map.put("params", s.params());
        return map;
    }

    // ==================== P0 自进化：技能草稿管理 + 进化统计 ====================

    @GetMapping("/api/skills/drafts")
    public List<Map<String, Object>> drafts(@org.springframework.web.bind.annotation.RequestParam(required = false) String status) {
        return draftStore.list(status).stream().map(this::toDraftView).toList();
    }

    @PostMapping("/api/skills/drafts")
    public ResponseEntity<Map<String, Object>> createDraft(@RequestBody DraftRequest req) {
        if (req.content() == null || req.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 不能为空");
        }
        long id = draftStore.insert(new SkillDraftStore.SkillDraft(-1,
                req.name() == null || req.name().isBlank() ? "manual-draft" : req.name().trim(),
                req.content(), req.sampleTestInput(), SkillDraftStore.PENDING, null, null, null, null, null));
        if (id < 0) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "草稿写入失败");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(draftStore.get(id).map(this::toDraftView).orElse(Map.of()));
    }

    @GetMapping("/api/skills/drafts/{id}")
    public ResponseEntity<Map<String, Object>> draftDetail(@PathVariable long id) {
        return draftStore.get(id)
                .map(d -> ResponseEntity.ok(toDraftView(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** 重新校验（同步返回最新状态；校验含 LLM 调用，可能耗时数十秒）。 */
    @PostMapping("/api/skills/drafts/{id}/validate")
    public ResponseEntity<Map<String, Object>> validateDraft(@PathVariable long id) {
        if (draftStore.get(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        SkillDraftStore.SkillDraft draft = evolutionPipeline.validateDraft(id, props.getEvolution());
        return ResponseEntity.ok(draft == null ? Map.of("ok", false) : toDraftView(draft));
    }

    /** 人工确认入库：落盘 SKILL.md + 启用 + 标记 synth=1。 */
    @PostMapping("/api/skills/drafts/{id}/commit")
    public ResponseEntity<Map<String, Object>> commitDraft(@PathVariable long id) {
        if (draftStore.get(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(evolutionPipeline.commitDraft(id));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/api/skills/drafts/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectDraft(@PathVariable long id) {
        if (!evolutionPipeline.rejectDraft(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/api/skills/drafts/{id}")
    public ResponseEntity<Map<String, Object>> deleteDraft(@PathVariable long id) {
        if (!draftStore.delete(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 进化统计：草稿状态分布 / 技能复用 / 知识周增长 / 节省估算（宁缺毋滥）。 */
    @GetMapping("/api/skills/evolution")
    public Map<String, Object> evolution() {
        Map<String, Integer> drafts = draftStore.countByStatus();

        // 技能复用统计
        Map<String, SkillStore.SkillStats> stats = skillStore.allStats();
        int totalHits = 0;
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map.Entry<String, SkillStore.SkillStats> e : stats.entrySet()) {
            totalHits += e.getValue().hitCount();
            Map<String, Object> row = new HashMap<>();
            row.put("skillId", e.getKey());
            row.put("hitCount", e.getValue().hitCount());
            skillLibrary.get(e.getKey()).ifPresent(s -> row.put("name", s.name()));
            top.add(row);
        }
        top.sort((a, b) -> Integer.compare((int) b.get("hitCount"), (int) a.get("hitCount")));
        int totalTasks = taskVectorStore.countAll();
        int hitTotal = totalHits + totalTasks;

        // 知识近 8 周增长
        List<Knowledge> allKnowledge = knowledgeStore.list(null);
        java.time.LocalDate today = java.time.LocalDate.now();
        List<Map<String, Object>> weekly = new ArrayList<>();
        for (int i = 7; i >= 0; i--) {
            java.time.LocalDate start = today.minusDays(i * 7L + 6);
            java.time.LocalDate end = today.minusDays(i * 7L);
            int count = 0;
            for (Knowledge k : allKnowledge) {
                java.time.LocalDate d = parseDate(k.createdAt());
                if (d != null && !d.isBefore(start) && !d.isAfter(end)) count++;
            }
            Map<String, Object> bucket = new HashMap<>();
            bucket.put("start", start.toString());
            bucket.put("end", end.toString());
            bucket.put("count", count);
            weekly.add(bucket);
        }

        // 节省估算：基线 = 非技能任务平均工具步数（样本 ≥3 才出数，宁缺毋滥）
        Map<String, Object> saved = new HashMap<>();
        double baseline = taskVectorStore.avgSteps(3);
        saved.put("available", baseline >= 0 && totalHits > 0);
        if (baseline >= 0) {
            saved.put("baselineSteps", Math.round(baseline * 10) / 10.0);
            saved.put("estimateSteps", Math.round(baseline * totalHits));
            saved.put("sampleCount", totalTasks);
            saved.put("caliber", "对比同工作区未命中任务的平均工具步数 × 技能复用次数（估算值）");
        }

        Map<String, Object> out = new HashMap<>();
        out.put("drafts", drafts);
        out.put("reuse", Map.of(
                "totalHits", totalHits,
                "totalTasks", totalTasks,
                "hitRate", hitTotal == 0 ? 0.0 : Math.round(1000.0 * totalHits / hitTotal) / 1000.0,
                "topSkills", top.size() > 5 ? top.subList(0, 5) : top));
        out.put("knowledge", Map.of("total", allKnowledge.size(), "weekly", weekly));
        out.put("savedSteps", saved);
        return out;
    }

    private java.time.LocalDate parseDate(String s) {
        try {
            return java.time.LocalDate.parse(s == null ? "" : s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> toDraftView(SkillDraftStore.SkillDraft d) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", d.id());
        m.put("name", d.name() == null ? "" : d.name());
        m.put("content", d.content() == null ? "" : d.content());
        m.put("sampleTestInput", d.sampleTestInput() == null ? "" : d.sampleTestInput());
        m.put("status", d.status());
        m.put("traceId", d.traceId());
        m.put("clusterId", d.clusterId());
        m.put("createdAt", d.createdAt());
        m.put("updatedAt", d.updatedAt());
        if (d.reportJson() != null && !d.reportJson().isBlank()) {
            try {
                m.put("report", om.readValue(d.reportJson(), Map.class));
            } catch (Exception e) {
                m.put("reportRaw", d.reportJson());
            }
        }
        return m;
    }
}
