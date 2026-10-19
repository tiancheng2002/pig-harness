package com.example.harness.model;

import java.util.List;

/**
 * 子智能体档案（spawnAgent 体系）：主 Agent 可按需派出的可复用角色。
 * - builtin：启动播种（如「代码探索」），可禁用不可删；
 * - user：用户在设置页创建（法律助手、医疗助手等自定义角色）。
 * tools 为工具白名单（空 = 继承除 spawnAgent 外的全部工具）；prompt 为角色职责说明，
 * 派发时作为最高优先级指令注入子 Agent 循环（guided 模式，跳过 Planner）。
 */
public record AgentProfile(String id, String name, String description, String prompt,
                           List<String> tools, Integer maxSteps, boolean enabled, String origin,
                           String createdAt, String updatedAt) {

    public static final String ORIGIN_BUILTIN = "builtin";
    public static final String ORIGIN_USER = "user";

    /** 工具白名单（永不为 null；空列表 = 不限制）。 */
    public List<String> allowedTools() {
        return tools == null ? List.of() : tools;
    }

    /** 生效步数上限：未配置时回退全局子 Agent 默认值。 */
    public int effectiveMaxSteps(int fallback) {
        return maxSteps == null || maxSteps <= 0 ? fallback : Math.min(30, maxSteps);
    }
}
