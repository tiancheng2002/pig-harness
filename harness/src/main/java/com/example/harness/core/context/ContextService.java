package com.example.harness.core.context;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.AgentLoop;
import com.example.harness.core.ModelRegistry;
import com.example.harness.core.TaskDispatcher;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.memory.KnowledgeStore;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.LlmMessage;
import com.example.harness.llm.TokenEstimator;
import com.example.harness.model.Knowledge;
import com.example.harness.model.StoredMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文窗口服务：会话历史的「摘要 + 逐字窗口」组装、容量状态估算、软阈值自动压缩。
 * 压缩只作用于发给 LLM 的上下文；落库消息保持完整（回放真相源）。
 * 容量占用取 max(上一轮真实 prompt 用量, 基线估算)——估算对齐真实请求口径（含工具定义），真实 usage 优先。
 */
@Component
public class ContextService {

    private static final Logger log = LoggerFactory.getLogger(ContextService.class);

    private final ConversationStore store;
    private final KnowledgeStore knowledgeStore;
    private final ModelRegistry modelRegistry;
    private final ConversationCompactor compactor;
    private final com.example.harness.core.ToolRegistry toolRegistry;
    private final com.example.harness.core.memory.AgentProfileStore agentProfileStore;
    private final HarnessProperties props;

    public ContextService(ConversationStore store, KnowledgeStore knowledgeStore,
                          ModelRegistry modelRegistry, ConversationCompactor compactor,
                          com.example.harness.core.ToolRegistry toolRegistry,
                          com.example.harness.core.memory.AgentProfileStore agentProfileStore,
                          HarnessProperties props) {
        this.store = store;
        this.knowledgeStore = knowledgeStore;
        this.modelRegistry = modelRegistry;
        this.compactor = compactor;
        this.toolRegistry = toolRegistry;
        this.agentProfileStore = agentProfileStore;
        this.props = props;
    }

    /** 容量状态（前端徽标数据源）。 */
    public record Status(String modelId, int contextLength, int usedTokens,
                         int percent, String state, boolean summarized) {
    }

    /**
     * 组装会话历史：`[此前对话压缩摘要]`（若有）+ 水位线之后的逐字消息（仍受 history-limit 上限保护）。
     * 调用方需在持久化「当前用户消息」之前调用（本方法不含当前消息）。
     */
    public List<LlmMessage> buildHistory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        List<StoredMessage> all = store.messages(conversationId);
        long watermark = store.contextSummary(conversationId)
                .map(ConversationStore.ContextSummary::untilId).orElse(0L);
        List<LlmMessage> verbatim = new ArrayList<>();
        for (StoredMessage m : all) {
            if (m.id() > watermark) {
                verbatim.add("user".equals(m.role())
                        ? LlmMessage.user(m.content())
                        : LlmMessage.assistant(m.content(), null));
            }
        }
        int limit = Math.max(2, props.getHistoryLimit());
        if (verbatim.size() > limit) {
            verbatim = new ArrayList<>(verbatim.subList(verbatim.size() - limit, verbatim.size()));
        }
        List<LlmMessage> out = new ArrayList<>();
        store.contextSummary(conversationId).ifPresent(s -> out.add(LlmMessage.user(
                "[此前对话压缩摘要]\n" + s.summary() + "\n（以上为此前对话的压缩摘要，请自然承接）")));
        out.addAll(verbatim);
        return out;
    }

    /** 当前会话的容量状态：max(上一轮真实 prompt 用量, 基线估算)。 */
    public Status status(String conversationId, String modelId) {
        int limit = modelRegistry.contextLengthOf(modelId);
        int used = effectiveUsed(conversationId);
        int percent = limit > 0 ? Math.min(100, Math.round(used * 100f / limit)) : 0;
        String state = percent >= 90 ? "full" : percent >= 75 ? "warn" : "ok";
        boolean summarized = store.contextSummary(conversationId).isPresent();
        return new Status(modelId, limit, used, percent, state, summarized);
    }

    /** 发送前软阈值检查：超阈值自动对话级压缩，返回压缩结果（未触发返回 null）。 */
    public ConversationCompactor.CompactResult maybeCompact(String conversationId, String modelId,
                                                            ChatClient client) {
        if (!props.getContext().isEnabled()) return null;
        return compactIfOver(conversationId, modelId, client, false);
    }

    /** 手动立即压缩（忽略阈值，不依赖 enabled 开关）。 */
    public ConversationCompactor.CompactResult compactNow(String conversationId, String modelId,
                                                          ChatClient client) {
        return compactIfOver(conversationId, modelId, client, true);
    }

    private ConversationCompactor.CompactResult compactIfOver(String conversationId, String modelId,
                                                              ChatClient client, boolean force) {
        int limit = modelRegistry.contextLengthOf(modelId);
        int soft = (int) (limit * props.getContext().getSoftThreshold());
        int used = effectiveUsed(conversationId);
        if (!force && used <= soft) return null;
        ConversationCompactor.CompactResult r = compactor.compact(conversationId, client);
        if (r == null) return null;
        int after = Math.max(0, used - r.before() + r.after());
        // 回写用量水位：否则旧的 last_prompt_tokens 会让下一次预检继续判定超限，反复触发压缩
        store.updateLastPromptTokens(conversationId, after);
        return new ConversationCompactor.CompactResult(used, after);
    }

    /** 有效占用：max(上一轮真实 prompt 用量, 基线估算)。真实 usage 只在每轮调用后更新，取较大者为准。 */
    private int effectiveUsed(String conversationId) {
        return Math.max(estimateBaseline(conversationId), store.lastPromptTokens(conversationId));
    }

    /**
     * 基础上下文估算：对齐真实请求口径——
     * TASK 请求 = AgentLoop 系统提示（含工具清单与知识段）+ 工具定义 JSON + 历史（摘要 + 逐字窗口）；
     * CHAT 请求 = CHAT 系统提示 + 知识段（不带工具定义，体积小得多）。
     * 取较大者作保守估计（压缩预检宁高勿低）。
     */
    private int estimateBaseline(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return 0;
        List<LlmMessage> history = buildHistory(conversationId);
        String projectId = store.get(conversationId).map(c -> c.projectId()).orElse(null);
        List<Knowledge> ks = knowledgeStore.forPrompt("", projectId, 5);
        int taskScope = TokenEstimator.estimate(AgentLoop.baseSystemPrompt(
                "workspace", List.of(), ks, false, "auto", toolRegistry.extras(),
                agentProfileStore.listEnabled()))
                + TokenEstimator.estimate(toolRegistry.specsJson());
        int chatScope = 0;
        for (LlmMessage m : com.example.harness.core.ChatModeRouter.chatSystemPrompt()) {
            chatScope += TokenEstimator.estimate(m.content());
        }
        chatScope += TokenEstimator.estimate(TaskDispatcher.knowledgePrompt(ks));
        return Math.max(chatScope, taskScope) + TokenEstimator.estimateMessages(history);
    }
}
