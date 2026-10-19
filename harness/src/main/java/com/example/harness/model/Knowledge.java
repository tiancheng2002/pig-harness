package com.example.harness.model;

import java.time.LocalDateTime;

/** 一条长期记忆（自进化知识库）：用户偏好 / 项目事实 / 工作习惯 / 手动笔记。 */
public record Knowledge(Long id, String category, String key, String content,
                        String source, boolean pinned, String status, String projectId,
                        String evidence, String sourceConversationId,
                        String createdAt, String updatedAt) {

    public static Knowledge manual(String category, String key, String content) {
        String now = LocalDateTime.now().toString();
        return new Knowledge(null, category, key, content, "manual", false,
                "active", null, null, null, now, now);
    }
}
