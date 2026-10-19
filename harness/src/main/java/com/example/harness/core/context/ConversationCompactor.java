package com.example.harness.core.context;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.LlmMessage;
import com.example.harness.llm.TokenEstimator;
import com.example.harness.model.StoredMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话级上下文压缩（LLM 摘要）：
 * 把「上次摘要水位线之后、最近 keep-recent-turns 条之前」的旧消息交给 LLM 压成一份结构化摘要
 * （任务目标 / 关键决定 / 产出文件 / 用户偏好 / 未完事项），与既有摘要合并后持久化到会话，
 * 并推进水位线。落库消息不动——DB 始终是完整回放真相源，摘要只作用于发给 LLM 的上下文。
 */
@Component
public class ConversationCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompactor.class);

    /** 单条消息进摘要提示词的最大字符数（约束摘要调用自身的体积）。 */
    private static final int PER_MSG_CHARS = 1200;
    /** 单次摘要提示词最多携带的消息条数（更早的忽略，摘要场景可接受）。 */
    private static final int MAX_MSGS = 60;

    private final ConversationStore store;
    private final HarnessProperties props;

    public ConversationCompactor(ConversationStore store, HarnessProperties props) {
        this.store = store;
        this.props = props;
    }

    /** 压缩结果：压缩前后（被压缩部分）的估算 token。 */
    public record CompactResult(int before, int after) {
    }

    /**
     * 执行一次对话级压缩。
     *
     * @return 无可压缩内容（逐字窗口内消息不足）时返回 null
     */
    public CompactResult compact(String conversationId, ChatClient client) {
        List<StoredMessage> all = store.messages(conversationId);
        if (all.isEmpty()) return null;
        long watermark = store.contextSummary(conversationId)
                .map(ConversationStore.ContextSummary::untilId).orElse(0L);
        List<StoredMessage> candidates = all.stream().filter(m -> m.id() > watermark).toList();
        int keep = Math.max(2, props.getContext().getKeepRecentTurns());
        if (candidates.size() <= keep) return null;

        List<StoredMessage> toSummarize = candidates.subList(0, candidates.size() - keep);
        String existing = store.contextSummary(conversationId)
                .map(ConversationStore.ContextSummary::summary).orElse(null);

        String summary = summarize(toSummarize, existing, client);
        if (summary == null || summary.isBlank()) {
            log.warn("[conv:{}] 对话摘要生成失败（模型无输出），本次跳过", conversationId);
            return null;
        }
        long newWatermark = toSummarize.get(toSummarize.size() - 1).id();
        store.saveContextSummary(conversationId, summary, newWatermark);

        int before = 0;
        for (StoredMessage m : toSummarize) {
            before += 4 + TokenEstimator.estimate(m.content());
        }
        int after = TokenEstimator.estimate(summary);
        log.info("[conv:{}] 对话上下文已压缩：{} 条消息（约 {} tokens）→ 摘要（约 {} tokens），水位线 {}",
                conversationId, toSummarize.size(), before, after, newWatermark);
        return new CompactResult(before, after);
    }

    /** 调 LLM 生成（或合并生成）结构化摘要；异常返回 null（压缩失败绝不阻断消息流）。 */
    private String summarize(List<StoredMessage> msgs, String existing, ChatClient client) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (StoredMessage m : msgs) {
            if (count++ >= MAX_MSGS) break;
            String content = m.content() == null ? "" : m.content().replace('\n', ' ');
            if (content.length() > PER_MSG_CHARS) {
                content = content.substring(0, PER_MSG_CHARS) + "…（截断）";
            }
            sb.append("user".equals(m.role()) ? "用户: " : "助手: ")
                    .append(content).append('\n');
        }
        String sys = """
                [ROLE=COMPACTOR] 你是对话上下文压缩器。把给定的历史对话压缩成一份给后续对话用的结构化摘要，
                让助手在看不到原文的情况下仍能自然承接。要求：
                1. 中文、条目化，总量不超过 400 字；
                2. 保留：任务目标与结果、关键决定、产出/修改过的文件路径、用户表达的偏好与约束、未尽事项；
                3. 丢弃：寒暄、重复内容、过程性细节；
                4. 若提供了「已有摘要」，在其基础上合并新内容，输出合并后的完整摘要（不要输出两份）。
                仅输出摘要正文，不要任何前后缀说明。
                """;
        List<LlmMessage> msgs2 = new ArrayList<>();
        msgs2.add(LlmMessage.system(sys));
        if (existing != null && !existing.isBlank()) {
            msgs2.add(LlmMessage.user("[已有摘要]\n" + existing));
        }
        msgs2.add(LlmMessage.user("[待压缩对话]\n" + sb));
        try {
            return client.chat(msgs2, List.of()).content();
        } catch (Exception e) {
            log.warn("[conv] 对话摘要 LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }
}
