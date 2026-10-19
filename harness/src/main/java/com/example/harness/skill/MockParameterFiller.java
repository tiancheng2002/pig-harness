package com.example.harness.skill;

import java.util.HashMap;
import java.util.Map;

/**
 * 无 Key / Mock 模式下的参数填充兜底：启发式处理常见占位符。
 */
public class MockParameterFiller implements ParameterFiller {

    @Override
    public Map<String, String> fill(Skill skill, String userInput) {
        Map<String, String> out = new HashMap<>();
        String lower = userInput == null ? "" : userInput.toLowerCase();
        for (String p : skill.params()) {
            out.put(p, switch (p.toLowerCase()) {
                case "page" -> pageValue(lower);
                case "title" -> titleValue(lower);
                case "filename", "file" -> filenameValue(lower);
                default -> p;
            });
        }
        return out;
    }

    private String pageValue(String lower) {
        if (lower.contains("注册") || lower.contains("signup")) return "signup";
        if (lower.contains("登录") || lower.contains("login")) return "login";
        if (lower.contains("首页") || lower.contains("home")) return "index";
        return "page";
    }

    private String titleValue(String lower) {
        if (lower.contains("周报")) return "本周工作周报";
        if (lower.contains("登录")) return "用户登录";
        if (lower.contains("注册")) return "用户注册";
        return "自动生成标题";
    }

    private String filenameValue(String lower) {
        if (lower.contains("登录")) return "login.html";
        if (lower.contains("注册")) return "signup.html";
        return "output.txt";
    }
}
