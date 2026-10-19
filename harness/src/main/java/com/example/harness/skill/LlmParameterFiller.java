package com.example.harness.skill;

import com.example.harness.llm.ChatClient;
import com.example.harness.llm.ChatResult;
import com.example.harness.llm.LlmMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用 LLM 一次性提取占位符对应的具体参数。
 */
public class LlmParameterFiller implements ParameterFiller {

    private static final Logger log = LoggerFactory.getLogger(LlmParameterFiller.class);
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*?\\}");

    private final ChatClient chatClient;
    private final ObjectMapper om;

    public LlmParameterFiller(ChatClient chatClient, ObjectMapper om) {
        this.chatClient = chatClient;
        this.om = om;
    }

    @Override
    public Map<String, String> fill(Skill skill, String userInput) {
        return fill(skill, userInput, chatClient);
    }

    @Override
    public Map<String, String> fill(Skill skill, String userInput, ChatClient client) {
        if (skill.params().isEmpty()) {
            return Map.of();
        }
        try {
            String sys = """
                    你是参数提取器。根据下面的 SKILL 与 USER 请求，把所有 {{placeholder}} 替换为具体值。
                    仅输出一个 JSON 对象，键为占位符名（不含花括号），值为替换后的字符串。不要输出任何其他内容。
                    """;
            StringBuilder skillText = new StringBuilder();
            if (!skill.steps().isEmpty()) {
                skillText.append("steps:\n");
                for (SkillStep step : skill.steps()) {
                    skillText.append(step.index()).append(". ").append(step.rawLine()).append("\n");
                }
            } else {
                // 提示词技能（M5）：正文即执行指南，截断后交给 LLM 理解上下文
                String body = SkillParser.stripFrontmatter(skill.source());
                if (body.length() > 2000) body = body.substring(0, 2000) + "…（已截断）";
                skillText.append("正文指南:\n").append(body).append('\n');
            }
            String user = "SKILL:\nname: " + skill.name() + "\n" + skillText
                    + "\nUSER: " + userInput;
            ChatResult r = client.chat(
                    java.util.List.of(LlmMessage.system(sys), LlmMessage.user(user)), java.util.List.of());
            String content = r.content();
            if (content == null || content.isBlank()) {
                return Map.of();
            }
            Matcher m = JSON_OBJECT.matcher(content);
            if (!m.find()) {
                return Map.of();
            }
            Map<String, String> map = om.readValue(m.group(), new TypeReference<Map<String, String>>() {
            });
            // 确保所有占位符都有值
            for (String p : skill.params()) {
                map.putIfAbsent(p, p);
            }
            return map;
        } catch (Exception e) {
            log.warn("LLM 填参失败，使用占位符名兜底: {}", e.getMessage());
            return defaultValues(skill);
        }
    }

    private Map<String, String> defaultValues(Skill skill) {
        return skill.params().stream().collect(java.util.stream.Collectors.toMap(p -> p, p -> p));
    }
}
