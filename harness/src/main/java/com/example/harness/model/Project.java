package com.example.harness.model;

/** 本地文件夹项目（项目模式）：会话绑定后沙箱根切换到 rootPath。 */
public record Project(String id, String name, String rootPath, String createdAt) {
}
