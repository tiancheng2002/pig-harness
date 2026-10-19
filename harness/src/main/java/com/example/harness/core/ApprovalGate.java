package com.example.harness.core;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 工具审批闸门（权限模式=请求审批时使用）：
 * AgentLoop 执行写操作前挂起等待用户决定；前端通过审批接口完成/拒绝。
 * key = conversationId:callId，超时视为拒绝（防 SSE 连接超时前无限挂起）。
 */
@Component
public class ApprovalGate {

    private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    /**
     * 预登记挂起项：必须先于审批事件广播调用。事件先于注册到达客户端时，
     * 秒回的审批 POST 会在 resolve 落空（409）——自动化测试/极快点击必踩的竞态。
     */
    public void register(String conversationId, String callId) {
        pending.computeIfAbsent(conversationId + ":" + callId, k -> new CompletableFuture<>());
    }

    /** 阻塞等待用户对某次工具调用的决定。超时/中断一律视为拒绝。 */
    public boolean await(String conversationId, String callId, long timeoutMs) {
        String key = conversationId + ":" + callId;
        CompletableFuture<Boolean> future = pending.computeIfAbsent(key, k -> new CompletableFuture<>());
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        } finally {
            pending.remove(key);
        }
    }

    /** 完成一次待审批调用；返回 false 表示没有对应的等待请求（可能已超时）。
     *  只完成不移除：resolve 可能落在 register 与 await 之间，移除会让 await 另建
     *  一个永远无人完成的 future（任务挂满超时）；已完成项由 await 的 finally 清理。 */
    public boolean resolve(String conversationId, String callId, boolean approved) {
        CompletableFuture<Boolean> future = pending.get(conversationId + ":" + callId);
        return future != null && future.complete(approved);
    }

    /** 会话停止时排水：所有挂起中的审批按「拒绝」收尾（修复停止后任务仍挂满超时的缺口）。 */
    public void cancelAll(String conversationId) {
        String prefix = conversationId + ":";
        pending.forEach((key, future) -> {
            if (key.startsWith(prefix)) {
                pending.remove(key, future);
                future.complete(false);
            }
        });
    }
}
