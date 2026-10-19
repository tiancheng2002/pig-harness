package com.example.harness.embedding;

import com.example.harness.config.HarnessProperties;
import com.example.harness.skill.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 技能向量索引：加载全部 embeddings，提供余弦相似度 top-k 检索。
 */
@Component
public class EmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndex.class);

    private final EmbeddingStore store;
    private final EmbeddingClient client;
    private final HarnessProperties props;

    /** 连续失败熔断：超过阈值后本次运行不再尝试（检索自动降级关键词），避免逐技能超时拖慢启动。 */
    private static final int FAILURE_TRIP_LIMIT = 3;
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean disabled = false;

    public EmbeddingIndex(EmbeddingStore store, EmbeddingClient client, HarnessProperties props) {
        this.store = store;
        this.client = client;
        this.props = props;
    }

    /** 为单个技能生成/更新向量。 */
    public void index(Skill skill) {
        if (skill == null || !skill.enabled() || disabled) return;
        if (unconfigured()) return; // 未配 Key：不建向量也不打日志（路由已自动走关键词匹配）
        try {
            float[] vector = client.embed(toEmbeddingText(skill));
            store.save(skill.id(), props.getEmbedding().getModel(), vector);
            consecutiveFailures.set(0);
        } catch (Exception e) {
            int fails = consecutiveFailures.incrementAndGet();
            if (fails >= FAILURE_TRIP_LIMIT) {
                disabled = true;
                log.warn("Embedding 连续失败 {} 次，本次运行停用向量索引，技能检索降级为关键词匹配（最后错误: {}）",
                        fails, e.getMessage());
            } else {
                log.warn("技能 [{}] embedding 构建失败: {}", skill.name(), e.getMessage());
            }
        }
    }

    private boolean unconfigured() {
        String key = props.getEmbedding().getApiKey();
        return key == null || key.isBlank();
    }

    /** 删除技能向量。 */
    public void remove(String skillId) {
        store.deleteBySkillId(skillId);
    }

    /**
     * 检索与 query 最相似的技能。
     *
     * @return 按相似度降序排列的结果（仅包含 >= threshold 的项）
     */
    public List<ScoredSkill> retrieve(String query, int topK, double threshold) {
        if (disabled) {
            throw new IllegalStateException("Embedding 索引已停用（连续调用失败），请改用关键词路由");
        }
        float[] q = client.embed(query);
        List<EmbeddingStore.EmbeddingRow> rows = store.findAll();
        List<ScoredSkill> out = new ArrayList<>();
        for (EmbeddingStore.EmbeddingRow row : rows) {
            double score = cosine(q, row.vector());
            if (score >= threshold) {
                out.add(new ScoredSkill(row.skillId(), score));
            }
        }
        out.sort(Comparator.comparingDouble(ScoredSkill::score).reversed());
        if (topK > 0 && out.size() > topK) {
            return out.subList(0, topK);
        }
        return out;
    }

    public static String toEmbeddingText(Skill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append(skill.name()).append("\n");
        if (skill.description() != null) sb.append(skill.description()).append("\n");
        if (!skill.triggers().isEmpty()) {
            sb.append("触发词：").append(String.join(", ", skill.triggers())).append("\n");
        }
        if (skill.whenToUse() != null) {
            sb.append("使用时机：").append(skill.whenToUse()).append("\n");
        }
        sb.append("步骤摘要：");
        List<String> steps = new ArrayList<>();
        skill.steps().forEach(s -> steps.add(s.tool() + "(" + s.rawLine() + ")"));
        sb.append(String.join("; ", steps));
        return sb.toString();
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public record ScoredSkill(String skillId, double score) {
    }
}
