package com.example.harness.core.evolution;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.memory.EpisodicStore;
import com.example.harness.core.memory.KnowledgeStore;
import com.example.harness.embedding.EmbeddingClient;
import com.example.harness.embedding.EmbeddingIndex;
import com.example.harness.model.Knowledge;
import com.example.harness.model.StoredMessage;
import com.example.harness.model.Task;
import com.example.harness.model.Trace;
import com.example.harness.skill.Skill;
import com.example.harness.skill.SkillDraftStore;
import com.example.harness.skill.SkillLibrary;
import com.example.harness.skill.SkillStore;
import com.example.harness.skill.SkillSynthesizer;
import com.example.harness.skill.SkillValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P0 自进化闭环流水线：
 * 任务成功（未命中技能路径）→ 写 task_vectors 聚类计数 → 同簇成功 ≥ 阈值 →
 * SkillSynthesizer 合成参数化 SKILL.md 草稿 → SkillValidator 隔离沙箱校验（失败带报告重试）→
 * validated（autoCommit=true 时自动入库，否则等人工确认）→ 入库后参与 IntentRouter 路由复用。
 * 全程异步虚拟线程 + 单飞防抖，任何异常只记日志，绝不影响对话主链路。
 */
@Component
public class EvolutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(EvolutionPipeline.class);

    private final HarnessProperties props;
    private final ConversationStore conversationStore;
    private final EpisodicStore episodicStore;
    private final SkillDraftStore draftStore;
    private final SkillStore skillStore;
    private final SkillLibrary skillLibrary;
    private final SkillSynthesizer synthesizer;
    private final SkillValidator validator;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeStore knowledgeStore;
    private final TaskVectorStore taskVectorStore;
    private final ObjectMapper om;

    /** 同一时刻仅允许一个合成/校验流水线在跑（聚簇写入在 store 层已 synchronized）。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EvolutionPipeline(HarnessProperties props,
                             ConversationStore conversationStore,
                             EpisodicStore episodicStore,
                             SkillDraftStore draftStore,
                             SkillStore skillStore,
                             SkillLibrary skillLibrary,
                             SkillSynthesizer synthesizer,
                             SkillValidator validator,
                             EmbeddingClient embeddingClient,
                             KnowledgeStore knowledgeStore,
                             TaskVectorStore taskVectorStore,
                             ObjectMapper om) {
        this.props = props;
        this.conversationStore = conversationStore;
        this.episodicStore = episodicStore;
        this.draftStore = draftStore;
        this.skillStore = skillStore;
        this.skillLibrary = skillLibrary;
        this.synthesizer = synthesizer;
        this.validator = validator;
        this.embeddingClient = embeddingClient;
        this.knowledgeStore = knowledgeStore;
        this.taskVectorStore = taskVectorStore;
        this.om = om;
    }

    /** TaskDispatcher finally 块的挂接点：所有分支（含早 return）都会经过。 */
    public void onTaskFinished(Task task, boolean usedSkill,
                               com.example.harness.core.task.TaskOutcome outcome) {
        HarnessProperties.Evolution cfg = props.getEvolution();
        if (!cfg.isEnabled()) return;
        if (task == null || task.conversationId() == null || task.conversationId().isBlank()) return;
        if (usedSkill) return;                 // 已是技能复用，不再为它合成
        if ("plan".equalsIgnoreCase(task.approval())) return; // 计划模式产出计划文档，不合成
        if (outcome == null || !"SUCCESS".equals(outcome.status())) return;
        Thread.ofVirtual().name("evolution-pipeline").start(() -> {
            if (!running.compareAndSet(false, true)) {
                log.debug("上一次进化流水线仍在运行，跳过本轮触发");
                return;
            }
            try {
                process(task, cfg);
            } catch (Exception e) {
                log.warn("进化流水线异常（忽略，不影响对话）: {}", e.getMessage());
            } finally {
                running.set(false);
            }
        });
    }

    private void process(Task task, HarnessProperties.Evolution cfg) {
        // 1. 取本会话最新带 traceId 的 assistant 消息（TaskDispatcher 不持有 traceId，由执行器落库）
        StoredMessage last = null;
        try {
            for (StoredMessage m : conversationStore.messages(task.conversationId())) {
                if ("assistant".equals(m.role()) && m.traceId() != null) last = m;
            }
        } catch (Exception e) {
            log.debug("读取会话消息失败: {}", e.getMessage());
            return;
        }
        if (last == null) return;
        Trace trace = episodicStore.get(last.traceId()).orElse(null);
        if (trace == null) return;
        int stepsCount = countSteps(trace);
        if (stepsCount <= 0) return; // CHAT 直答 steps=[]，排除

        // 2. 聚类：向量（有 Embedding Key）或字符 bigram Jaccard（无 Key 降级）
        String taskText = task.prompt() == null ? "" : task.prompt();
        float[] vector = embedOrNull(taskText);
        int clusterId = assignCluster(taskText, vector, cfg);
        boolean recorded = vector != null
                ? taskVectorStore.insert(task.conversationId(), trace.id(), clusterId,
                        toJson(vector), null, stepsCount)
                : taskVectorStore.insert(task.conversationId(), trace.id(), clusterId,
                        null, taskText, stepsCount);
        if (!recorded) return;
        int clusterCount = taskVectorStore.countByCluster(clusterId);
        log.info("进化：任务 #{} 归入簇 {}（簇内成功 {} 次，阈值 {}）",
                trace.id(), clusterId, clusterCount, cfg.getRecurrenceThreshold());
        if (clusterCount < cfg.getRecurrenceThreshold()) return;
        if (draftStore.existsActiveForCluster(clusterId)) return;

        // 3. 合成草稿（SkillSynthesizer 内含 Mock/异常确定性兜底）
        SkillSynthesizer.DraftMeta meta = synthesizer.synthesize(trace, recentMsgs(task.conversationId()));
        String name = meta.name();
        String content = synthesizer.buildSkillMarkdown(name, meta, trace);
        long draftId = draftStore.insert(new SkillDraftStore.SkillDraft(-1, name, content,
                meta.testInput(), SkillDraftStore.PENDING, null, trace.id(), clusterId, null, null));
        if (draftId < 0) {
            log.warn("进化：草稿写入失败，本轮放弃");
            return;
        }
        log.info("进化：簇 {} 达到阈值，已合成技能草稿 #{}「{}」", clusterId, draftId, name);

        // 4. 校验（失败按 validatorRetries 带报告重试）
        validateDraft(draftId, cfg);
        SkillDraftStore.SkillDraft draft = draftStore.get(draftId).orElse(null);
        if (draft != null && SkillDraftStore.VALIDATED.equals(draft.status())
                && cfg.isAutoCommit()) {
            commitDraft(draftId);
        }
    }

    /** 校验一份草稿；失败时以报告为反馈重新合成再校验（共 validatorRetries 次）。 */
    public SkillDraftStore.SkillDraft validateDraft(long draftId, HarnessProperties.Evolution cfg) {
        SkillDraftStore.SkillDraft draft = draftStore.get(draftId).orElse(null);
        if (draft == null) return null;
        int retries = Math.max(0, cfg.getValidatorRetries());
        while (true) {
            draftStore.updateStatus(draftId, SkillDraftStore.VALIDATING);
            SkillValidator.ValidationReport report;
            try {
                report = validator.validate(draftId, draft.content(), draft.sampleTestInput());
            } catch (Exception e) {
                log.warn("草稿 #{} 校验异常: {}", draftId, e.getMessage());
                report = new SkillValidator.ValidationReport(false, false, false, true, false,
                        List.of(), List.of("校验执行异常: " + e.getMessage()), 0);
            }
            String reportJson = toJson(report);
            if (report.allPassed()) {
                draftStore.updateReport(draftId, reportJson);
                draftStore.updateStatus(draftId, SkillDraftStore.VALIDATED);
                log.info("进化：草稿 #{} 校验通过（产出 {} 个文件，{}ms）",
                        draftId, report.producedFiles().size(), report.durationMs());
                return draftStore.get(draftId).orElse(null);
            }
            if (retries-- <= 0) {
                draftStore.updateReport(draftId, reportJson);
                draftStore.updateStatus(draftId, SkillDraftStore.REJECTED);
                log.info("进化：草稿 #{} 校验未通过，标记 rejected（{} 项问题）",
                        draftId, report.issues().size());
                return draftStore.get(draftId).orElse(null);
            }
            // 报告回灌：把校验问题作为附加上下文重新合成一次
            log.info("进化：草稿 #{} 校验未通过，带报告重试（剩余 {} 次）", draftId, retries + 1);
            List<StoredMessage> feedback = new ArrayList<>(recentMsgsByTrace(draft.traceId()));
            feedback.add(new StoredMessage(null, null, "user",
                    "上一次合成的技能草稿在隔离沙箱校验中未通过，问题清单：" + report.issues()
                            + "。请据此调整（修正步骤/参数/占位符），使其在「相似但不相同」的输入上可执行。",
                    null, null, null, null));
            Trace trace = draft.traceId() == null ? null
                    : episodicStore.get(draft.traceId()).orElse(null);
            if (trace == null) {
                draftStore.updateReport(draftId, reportJson);
                draftStore.updateStatus(draftId, SkillDraftStore.REJECTED);
                return draftStore.get(draftId).orElse(null);
            }
            SkillSynthesizer.DraftMeta meta = synthesizer.synthesize(trace, feedback);
            String content = synthesizer.buildSkillMarkdown(meta.name(), meta, trace);
            draftStore.updateContent(draftId, content, meta.name(), reportJson);
            draft = draftStore.get(draftId).orElse(null);
            if (draft == null) return null;
        }
    }

    /** 提交草稿入库（人工确认或 autoCommit）：落盘 + 建向量 + 置 synth 标记 + 启用。 */
    public synchronized Map<String, Object> commitDraft(long draftId) {
        SkillDraftStore.SkillDraft draft = draftStore.get(draftId)
                .orElseThrow(() -> new IllegalArgumentException("草稿不存在: " + draftId));
        if (!SkillDraftStore.VALIDATED.equals(draft.status())) {
            throw new IllegalStateException("仅 validated 状态的草稿可提交（当前: " + draft.status() + "）");
        }
        Skill skill = skillLibrary.create(draft.name(), draft.content());
        skillLibrary.update(skill.id(), null, null, true); // 用户显式确认 → 启用并参与路由
        skillStore.markSynthesized(skill.id());
        draftStore.updateStatus(draftId, SkillDraftStore.COMMITTED);
        log.info("进化：草稿 #{} 已提交入库为技能「{}」（synth=1，enabled）", draftId, skill.name());
        return Map.of("ok", true, "skillId", skill.id(), "skillName", String.valueOf(skill.name()));
    }

    /** 拒绝草稿（终态）。 */
    public boolean rejectDraft(long draftId) {
        return draftStore.updateStatus(draftId, SkillDraftStore.REJECTED);
    }

    // ---------- 聚类 ----------

    private int assignCluster(String taskText, float[] vector, HarnessProperties.Evolution cfg) {
        double threshold = cfg.getClusterSimilarity();
        List<TaskVectorStore.TaskVector> recent = taskVectorStore.listRecent(cfg.getScanRecent());
        Set<String> bigrams = vector == null ? bigrams(taskText) : Set.of();
        for (TaskVectorStore.TaskVector row : recent) {
            double sim = -1;
            if (vector != null && row.vector() != null) {
                sim = EmbeddingIndex.cosine(vector, fromJson(row.vector()));
            } else if (vector == null && row.keywords() != null) {
                sim = jaccard(bigrams, bigrams(row.keywords()));
            }
            if (sim >= threshold) return row.clusterId();
        }
        return taskVectorStore.maxClusterId() + 1;
    }

    private float[] embedOrNull(String text) {
        if (props.getEmbedding().getApiKey() == null || props.getEmbedding().getApiKey().isBlank()) {
            return null;
        }
        try {
            return embeddingClient.embed(text);
        } catch (Exception e) {
            log.debug("任务向量计算失败，降级关键词聚类: {}", e.getMessage());
            return null;
        }
    }

    /** 字符 bigram Jaccard 相似度（中文友好，无词典依赖）。 */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = a.size() <= b.size() ? b : a;
        int hit = 0;
        for (String g : small) if (large.contains(g)) hit++;
        return (double) hit / (a.size() + b.size() - hit);
    }

    static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        String t = s.replaceAll("\\s+", "").toLowerCase();
        if (t.length() < 2) {
            if (!t.isEmpty()) out.add(t);
            return out;
        }
        for (int i = 0; i < t.length() - 1; i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    // ---------- 工具 ----------

    private boolean isSuccessfulTrace(Trace trace) {
        if (trace == null || trace.outcome() == null) return false;
        String outcome = trace.outcome().trim();
        if (outcome.isEmpty()) return false;
        if (outcome.startsWith("ERROR:")) return false;
        return !"（已停止）".equals(outcome);
    }

    private int countSteps(Trace trace) {
        try {
            List<?> steps = om.readValue(trace.steps() == null ? "[]" : trace.steps(), List.class);
            return steps.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private List<StoredMessage> recentMsgs(String conversationId) {
        try {
            List<StoredMessage> all = conversationStore.messages(conversationId);
            return all.size() > 8 ? all.subList(all.size() - 8, all.size()) : all;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<StoredMessage> recentMsgsByTrace(Long traceId) {
        if (traceId == null) return List.of();
        try {
            var convId = conversationStore.findConversationIdByTrace(traceId);
            return convId.map(s -> recentMsgs(s)).orElse(List.of());
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private float[] fromJson(String json) {
        try {
            return om.readValue(json, float[].class);
        } catch (Exception e) {
            return new float[0];
        }
    }
}
