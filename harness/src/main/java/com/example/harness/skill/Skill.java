package com.example.harness.skill;

import java.util.List;
import java.util.Set;

/**
 * 技能（SKILL.md）的内存表示。
 * origin 区分来源：system（系统内置）/ user（用户创建或从轨迹合成）。
 * mode 区分形态：steps（步骤脚本，N. tool(...)）/ prompt（散文指南，注入 AgentLoop 由 LLM 执行）。
 */
public record Skill(
        String id,
        String name,
        String description,
        List<String> triggers,
        String whenToUse,
        String source,
        String origin,
        String mode,
        String filePath,
        boolean enabled,
        int version,
        String createdAt,
        String updatedAt,
        List<SkillStep> steps,
        Set<String> params
) {
}
