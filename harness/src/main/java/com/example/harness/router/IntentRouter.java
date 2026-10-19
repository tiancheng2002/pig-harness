package com.example.harness.router;

import com.example.harness.config.HarnessProperties;
import com.example.harness.embedding.EmbeddingIndex;
import com.example.harness.skill.Skill;
import com.example.harness.skill.SkillLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 意图路由：用户输入 → 向量/关键词检索 → 命中返回最优技能，否则 Optional.empty()。
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private final SkillLibrary skillLibrary;
    private final EmbeddingIndex embeddingIndex;
    private final HarnessProperties props;

    public IntentRouter(SkillLibrary skillLibrary,
                        EmbeddingIndex embeddingIndex,
                        HarnessProperties props) {
        this.skillLibrary = skillLibrary;
        this.embeddingIndex = embeddingIndex;
        this.props = props;
    }

    public Optional<Skill> route(String query) {
        if (query == null || query.isBlank()) return Optional.empty();

        // 无 Key 时直接关键词匹配，避免无效网络调用
        String embeddingKey = props.getEmbedding().getApiKey();
        if (embeddingKey == null || embeddingKey.isBlank()) {
            return keywordRoute(query);
        }

        try {
            List<EmbeddingIndex.ScoredSkill> hits = embeddingIndex.retrieve(
                    query, props.getRouting().getTopK(), props.getRouting().getThreshold());
            if (!hits.isEmpty()) {
                EmbeddingIndex.ScoredSkill best = hits.get(0);
                log.info("意图路由命中 [score={}]: skillId={}", best.score(), best.skillId());
                return skillLibrary.get(best.skillId());
            }
        } catch (Exception e) {
            log.warn("Embedding 路由失败，降级关键词匹配: {}", e.getMessage());
            return keywordRoute(query);
        }

        return keywordRoute(query);
    }

    private Optional<Skill> keywordRoute(String query) {
        String lower = query.toLowerCase();
        List<Skill> enabled = skillLibrary.listEnabled();
        return enabled.stream()
                .map(s -> new Scored(s, keywordScore(s, lower)))
                .filter(s -> s.score > 0)
                .max(Comparator.comparingDouble(s -> s.score))
                .map(s -> s.skill);
    }

    private double keywordScore(Skill skill, String queryLower) {
        for (String t : skill.triggers()) {
            if (t == null || t.isBlank()) continue;
            String tl = t.toLowerCase();
            if (queryLower.contains(tl)) return 1.0;
            // 触发词包含用户输入也视为强相关（如 trigger="生成登录页"，query="登录页"）
            if (tl.contains(queryLower)) return 0.9;
        }
        if (skill.description() != null && queryLower.contains(skill.description().toLowerCase())) {
            return 0.5;
        }
        return 0;
    }

    private record Scored(Skill skill, double score) {
    }
}
