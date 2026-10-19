package com.example.harness.skill;

import java.util.Map;

/**
 * 技能参数填充器：根据用户输入把 SKILL.md 中的 {{placeholder}} 替换为具体值。
 */
public interface ParameterFiller {

    Map<String, String> fill(Skill skill, String userInput);

    default Map<String, String> fill(Skill skill, String userInput,
                                     com.example.harness.llm.ChatClient client) {
        return fill(skill, userInput);
    }
}
