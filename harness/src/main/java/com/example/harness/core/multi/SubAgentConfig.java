package com.example.harness.core.multi;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 子 Agent 运行约束（M4-F5/F7/F8）：AgentLoop.runLoop 在 cfg 非空时启用
 * callId 前缀（审批闸门不串扰）、输出路径占坑兜底、独立步数上限与共享取消标志。
 * allowedTools 为子智能体工具白名单（null/空 = 不限制，但 spawnAgent 对子 Agent 永远关闭）。
 */
public record SubAgentConfig(String agentId,
                             List<String> allocatedPaths,
                             SharedBlackboard blackboard,
                             AtomicBoolean sharedCancel,
                             int maxSteps,
                             List<String> allowedTools) {

    /** 涉及新建/覆盖/删除文件的写操作，写入前做占坑校验（fileEdit 只改内容不拦）。 */
    private static final Set<String> CLAIM_TOOLS = Set.of("fileWrite", "docxWrite", "fileDelete");

    /** 子 Agent 永不可用的工具（防递归派出）。 */
    private static final String SPAWN_AGENT = "spawnAgent";

    /** 兼容构造（M4 Orchestrator 调用点：无工具白名单）。 */
    public SubAgentConfig(String agentId, List<String> allocatedPaths, SharedBlackboard blackboard,
                          AtomicBoolean sharedCancel, int maxSteps) {
        this(agentId, allocatedPaths, blackboard, sharedCancel, maxSteps, null);
    }

    /** 白名单判定：空 = 不限制；spawnAgent 对子 Agent 一律拒绝。 */
    public boolean canUse(String toolName) {
        if (SPAWN_AGENT.equals(toolName)) {
            return false;
        }
        return allowedTools == null || allowedTools.isEmpty() || allowedTools.contains(toolName);
    }

    public SubAgentConfig {
        allocatedPaths = allocatedPaths == null ? List.of() : allocatedPaths.stream()
                .map(SubAgentConfig::normalize).toList();
    }

    /**
     * 占坑兜底（M4-F7）：目标路径已被其它子 Agent 占用且非本任务分配路径时返回拒绝文案（回灌 LLM），
     * 否则登记占坑并返回 null。relPath 为相对工作根的路径（绝对路径/工作根外不参与占坑）。
     */
    public String claimConflict(String toolName, String relPath) {
        if (relPath == null || relPath.isBlank() || !CLAIM_TOOLS.contains(toolName)) {
            return null;
        }
        String norm = normalize(relPath);
        if (allocatedPaths.contains(norm)) {
            blackboard.claim("file:" + norm, agentId);
            return null;
        }
        String owner = blackboard.claim("file:" + norm, agentId);
        if (owner == null) {
            return null;
        }
        return "文件路径「" + relPath + "」已被并行子 Agent " + owner + " 占用（多 Agent 拆分时"
                + "各子任务的输出文件不得重叠）。请不要写入该路径，改用一个不冲突的新文件名后重试。";
    }

    /** callId 加 agent 前缀：并发子 Agent 的审批闸门 key（conversationId:callId）互不串扰。 */
    public String gateCallId(String callId) {
        return agentId + "::" + callId;
    }

    public static String normalize(String path) {
        String p = path.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p;
    }
}
