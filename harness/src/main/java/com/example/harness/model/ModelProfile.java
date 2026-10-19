package com.example.harness.model;

/**
 * 一个可用的对话模型档案。
 * - builtin：来自 application.yml（harness.llm / harness.models[]），不可在 UI 删除
 * - user：用户在设置页添加，存 SQLite model_profiles 表
 * thinkingModel 非空表示支持「深度思考」（切换到推理模型，如 deepseek-reasoner）。
 * contextLength 为该模型的上下文窗口（token），null 时由 ModelRegistry 按全局默认值兜底。
 */
public record ModelProfile(String id, String name, String baseUrl, String apiKey,
                           String model, String thinkingModel, Integer contextLength, boolean builtin) {

    /** 兼容构造：无上下文长度（旧调用点）。 */
    public ModelProfile(String id, String name, String baseUrl, String apiKey,
                        String model, String thinkingModel, boolean builtin) {
        this(id, name, baseUrl, apiKey, model, thinkingModel, null, builtin);
    }

    /** 对外（API/前端）视图：不暴露 apiKey，只暴露是否有 Key 与是否可用；baseUrl 供编辑表单预填。 */
    public record View(String id, String name, String baseUrl, String model, String thinkingModel,
                       boolean hasKey, boolean builtin, boolean supportsThinking,
                       Integer contextLength) {
    }
}
