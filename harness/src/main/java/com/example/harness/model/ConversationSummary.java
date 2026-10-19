package com.example.harness.model;

/** 会话列表项（F12）：标题、最后活跃时间、消息数；projectName 非空表示项目会话。 */
public record ConversationSummary(String id, String title, String updatedAt, int messageCount,
                                  String projectId, String projectName) {

    /** 兼容构造：普通对话。 */
    public ConversationSummary(String id, String title, String updatedAt, int messageCount) {
        this(id, title, updatedAt, messageCount, null, null);
    }
}
