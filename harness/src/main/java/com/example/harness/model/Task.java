package com.example.harness.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次 Agent 任务。conversationId 非空时按会话续聊（F12）；modelId/deepThink 指定本次使用的模型；
 * approval 指定文件操作权限：auto（替我审批，默认）/ ask（写入/修改/删除前请求用户批准）/ full（完全访问，允许沙箱外绝对路径）/ plan（计划模式：只读调研 + askUser 澄清，产出计划文档，不改任何文件）；
 * skillId 非空时显式执行指定技能（输入框 / 选择），跳过模式路由与意图检索；
 * images 非空时先经视觉模型转述图片内容再进入路由与执行（描述追加到 prompt，原文保留）；
 * planText 非空时为「执行此计划」：计划文档随消息下发并注入执行提示词（落库用户消息保持原文，不回显计划全文）。
 */
public record Task(String id, String prompt, String root, String conversationId,
                   LocalDateTime createdAt, String modelId, boolean deepThink, String approval,
                   String skillId, List<String> images, String planText) {

    /** 兼容构造：默认模型、非深度思考、默认权限。 */
    public Task(String id, String prompt, String root, String conversationId, LocalDateTime createdAt) {
        this(id, prompt, root, conversationId, createdAt, null, false, "auto", null, null, null);
    }

    /** 兼容构造：默认权限。 */
    public Task(String id, String prompt, String root, String conversationId,
                LocalDateTime createdAt, String modelId, boolean deepThink) {
        this(id, prompt, root, conversationId, createdAt, modelId, deepThink, "auto", null, null, null);
    }

    /** 兼容构造：带权限、无显式技能。 */
    public Task(String id, String prompt, String root, String conversationId,
                LocalDateTime createdAt, String modelId, boolean deepThink, String approval) {
        this(id, prompt, root, conversationId, createdAt, modelId, deepThink, approval, null, null, null);
    }

    /** 兼容构造：带权限、无显式技能、无图片。 */
    public Task(String id, String prompt, String root, String conversationId,
                LocalDateTime createdAt, String modelId, boolean deepThink, String approval,
                String skillId) {
        this(id, prompt, root, conversationId, createdAt, modelId, deepThink, approval, skillId, null, null);
    }

    /** 换提示词（图片描述/计划文档注入后）派生新 Task，其余字段不变。 */
    public Task withPrompt(String newPrompt) {
        return new Task(id, newPrompt, root, conversationId, createdAt,
                modelId, deepThink, approval, skillId, images, planText);
    }
}
