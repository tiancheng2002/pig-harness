package com.example.harness.skill;

import java.util.Map;

/**
 * 技能中的一个步骤。
 */
public record SkillStep(
        int index,
        String tool,
        Map<String, String> args,
        String rawLine
) {
}
