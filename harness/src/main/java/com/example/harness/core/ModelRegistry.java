package com.example.harness.core;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.memory.ModelStore;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.MockChatClient;
import com.example.harness.llm.OpenAiCompatChatClient;
import com.example.harness.model.ModelProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册中心：内置档案（application.yml 的 harness.llm / harness.models[]）+ 用户自配档案（SQLite）。
 * 按请求解析模型：modelId + deepThink（有 thinkingModel 时切换到推理模型）→ 缓存的 ChatClient。
 * 档案未配 Key 时降级为 MockChatClient（本地演示）。
 */
@Component
public class ModelRegistry {

    public static final String DEFAULT_ID = "default";
    public static final String PRO_ID = "pro";

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final HarnessProperties props;
    private final ModelStore store;
    private final ChatClient defaultClient; // LlmConfig 装配（真实或 Mock）
    private final Map<String, ChatClient> cache = new ConcurrentHashMap<>();

    public ModelRegistry(HarnessProperties props, ModelStore store, ChatClient defaultClient) {
        this.props = props;
        this.store = store;
        this.defaultClient = defaultClient;
    }

    /** 全部可用档案（内置在前），供 /api/models 与前端选择器。 */
    public List<ModelProfile> profiles() {
        List<ModelProfile> out = new ArrayList<>();
        out.add(builtinDefault());
        out.add(builtinPro());
        for (HarnessProperties.ModelDef m : props.getModels()) {
            String id = m.getId() == null || m.getId().isBlank()
                    ? "sys-" + UUID.randomUUID().toString().substring(0, 8) : m.getId();
            out.add(new ModelProfile(id, m.getName(), m.getBaseUrl(), m.getApiKey(),
                    m.getModel(), m.getThinkingModel(), m.getContextLength(), true));
        }
        out.addAll(store.list());
        return out;
    }

    public Optional<ModelProfile> find(String modelId) {
        if (modelId == null || modelId.isBlank()) return Optional.of(builtinDefault());
        return profiles().stream().filter(p -> p.id().equals(modelId)).findFirst();
    }

    /**
     * 按请求解析 ChatClient。deepThink=true 且档案配置了 thinkingModel 时使用推理模型。
     * 未配 Key 的档案降级 Mock。
     */
    public ChatClient resolve(String modelId, boolean deepThink) {
        ModelProfile p = find(modelId).orElse(builtinDefault());
        boolean useThinking = deepThink && p.thinkingModel() != null && !p.thinkingModel().isBlank();
        if (p.id().equals(DEFAULT_ID) && !useThinking) {
            return defaultClient; // LlmConfig 装配的默认客户端（可能是 Mock）
        }
        if (p.apiKey() == null || p.apiKey().isBlank()) {
            return defaultClient; // 无 Key → Mock 演示
        }
        String actualModel = useThinking ? p.thinkingModel() : p.model();
        String cacheKey = p.id() + ":" + actualModel;
        return cache.computeIfAbsent(cacheKey,
                k -> new OpenAiCompatChatClient(p.baseUrl(), p.apiKey(), actualModel,
                        props.getContext().isRequestUsage(), props.getLlm().getMaxCompletionTokens()));
    }

    /** 默认客户端（非思考模式），供意图分类等内部轻量调用。 */
    public ChatClient defaultClient() {
        return defaultClient;
    }

    public boolean defaultMockMode() {
        String key = props.getLlm().getApiKey();
        return key == null || key.isBlank();
    }

    private ModelProfile builtinDefault() {
        var llm = props.getLlm();
        String displayName = llm.getName() == null || llm.getName().isBlank()
                ? "默认模型" : llm.getName();
        return new ModelProfile(DEFAULT_ID, displayName, llm.getBaseUrl(), llm.getApiKey(),
                llm.getModel(), blankToNull(llm.getThinkingModel()),
                llm.getContextLength() > 0 ? llm.getContextLength() : null, true);
    }

    /**
     * 内置 Pro 档案：即深度思考推理模型本身（thinking-model 配置项），无需再叠加思考开关。
     * 未配 thinking-model 时兜底 deepseek-reasoner。
     */
    private ModelProfile builtinPro() {
        var llm = props.getLlm();
        String model = blankToNull(llm.getThinkingModel());
        if (model == null) model = "deepseek-reasoner";
        return new ModelProfile(PRO_ID, "DeepSeek V4 Pro", llm.getBaseUrl(), llm.getApiKey(),
                model, null,
                llm.getContextLength() > 0 ? llm.getContextLength() : null, true);
    }

    /**
     * 档案的有效上下文窗口（token）：档案自带值优先，否则回退 harness.llm.context-length（默认 131072）。
     * 供上下文容量显示与压缩阈值计算使用。
     */
    public int effectiveContextLength(ModelProfile p) {
        if (p != null && p.contextLength() != null && p.contextLength() > 0) {
            return p.contextLength();
        }
        int fallback = props.getLlm().getContextLength();
        return fallback > 0 ? fallback : 131_072;
    }

    /** 按 modelId 解析有效上下文窗口（找不到档案时按默认档案兜底）。 */
    public int contextLengthOf(String modelId) {
        return effectiveContextLength(find(modelId).orElse(builtinDefault()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
