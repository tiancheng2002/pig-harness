package com.example.harness.mcp;

import com.example.harness.tools.Tool;
import com.example.harness.tools.ToolContext;
import com.example.harness.tools.ToolResult;

import java.util.Map;

/**
 * MCP 工具适配器（M5-F8）：把远端 MCP 服务器的工具包装成本地 Tool 接入 ToolRegistry。
 * 命名约定 mcp__{server}__{tool}，天然避免多服务器同名工具冲突。
 * 外部副作用能力：审批策略由 AgentLoop/SkillExecutor 按 mcp__ 前缀 + require-approval 判定。
 */
public class McpTool implements Tool {

    private final String serverId;
    private final String remoteToolName;
    private final String localName;
    private final String description;
    private final String parametersJson;
    private final McpConnectionManager manager;

    public McpTool(String serverId, String serverName, String toolName,
                   String description, String parametersJson, McpConnectionManager manager) {
        this.serverId = serverId;
        this.remoteToolName = toolName;
        this.localName = "mcp__" + sanitize(serverName) + "__" + sanitize(toolName);
        this.description = description == null || description.isBlank()
                ? "MCP 工具 " + toolName + "（服务器 " + serverName + "）" : description.trim();
        this.parametersJson = parametersJson == null || parametersJson.isBlank()
                ? "{\"type\":\"object\",\"properties\":{}}" : parametersJson;
        this.manager = manager;
    }

    private static String sanitize(String s) {
        return s == null ? "_" : s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public String serverId() {
        return serverId;
    }

    public String remoteToolName() {
        return remoteToolName;
    }

    @Override
    public String name() {
        return localName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String parametersJson() {
        return parametersJson;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        McpConnectionManager.ToolRegistryHolder r = manager.invoke(serverId, remoteToolName, args);
        return r.ok() ? ToolResult.success(r.output()) : ToolResult.error(r.output());
    }
}
