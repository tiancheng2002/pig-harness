package com.example.harness.model;

/** 会话（F12）。projectId 非空时为项目会话：沙箱根切换到该项目的本地文件夹。 */
public record Conversation(String id, String title, String createdAt, String updatedAt, String projectId) {

    /** 兼容构造：普通对话（无项目绑定）。 */
    public Conversation(String id, String title, String createdAt, String updatedAt) {
        this(id, title, createdAt, updatedAt, null);
    }
}
