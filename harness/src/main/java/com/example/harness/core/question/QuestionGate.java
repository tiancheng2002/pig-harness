package com.example.harness.core.question;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 澄清提问闸门（计划模式与受限 TASK 澄清共用，机制与 ApprovalGate 同构但回传 String 答复）：
 * AgentLoop 执行 askUser 前挂起等待用户答复；前端通过问题应答接口回传所选选项或自由输入。
 * key = conversationId:callId，超时返回 null（TASK 会暂停为 PARTIAL；计划模式基于已有证据收尾）。
 * cancelAll 供会话停止时排水——避免停止后任务仍挂在等待上（与审批闸门同款缺口）。
 */
@Component
public class QuestionGate {

    private static final Logger log = LoggerFactory.getLogger(QuestionGate.class);

    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    public QuestionGate() {
        log.info("澄清提问闸门就绪（askUser 由 AgentLoop 按模式注入）");
    }

    /**
     * 预登记挂起项：必须先于提问事件广播调用（与 ApprovalGate.register 同款竞态防护）。
     */
    public void register(String conversationId, String callId) {
        pending.computeIfAbsent(conversationId + ":" + callId, k -> new CompletableFuture<String>());
    }

    /** 阻塞等待用户答复；超时/中断返回 null（语义=未答复）。 */
    public String await(String conversationId, String callId, long timeoutMs) {
        String key = conversationId + ":" + callId;
        CompletableFuture<String> future = pending.computeIfAbsent(key, k -> new CompletableFuture<>());
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        } finally {
            pending.remove(key);
        }
    }

    /** 完成一次待答复提问；返回 false 表示没有对应的等待请求（可能已超时或已被停止排水）。
     *  只完成不移除：resolve 可能落在 register 与 await 之间，移除会让 await 另建
     *  一个永远无人完成的 future（任务挂满超时）；已完成项由 await 的 finally 清理。 */
    public boolean resolve(String conversationId, String callId, String answer) {
        CompletableFuture<String> future = pending.get(conversationId + ":" + callId);
        return future != null && future.complete(answer);
    }

    /** 会话停止时排水：所有挂起中的提问按「未答复」收尾。 */
    public void cancelAll(String conversationId) {
        String prefix = conversationId + ":";
        pending.forEach((key, future) -> {
            if (key.startsWith(prefix)) {
                pending.remove(key, future);
                future.complete(null);
            }
        });
    }
}
