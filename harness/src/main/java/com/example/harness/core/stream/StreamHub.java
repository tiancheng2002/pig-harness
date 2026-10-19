package com.example.harness.core.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话级事件流枢纽（体验升级七）：每个运行中的会话对应一个 Hub——
 * 事件缓冲（含 delta 类，供任何客户端重放）+ 多订阅者广播 + 取消标志。
 *
 * 设计要点：
 * - 挂接点在 AgentLoop.Emitter / ChatExecutor 等发送处（那里还看得到事件 Map），
 *   SseEmitter 门面层拿不到 payload，不做包装；
 * - 「客户端断开」不再取消任务（发送失败只摘除该连接）——任务后台继续跑完并落库，
 *   刷新页面 / 换标签页 / 稍后回来都可通过 GET /live 重放+续流；
 * - 停止只能显式触发：POST /stop 置 cancelFlag，各执行循环据此收尾（结论「（已停止）」照常落库）；
 * - 旧语义兼容：无 Hub 的入口（/api/task）发送失败仍按断开处理（各 Emitter 内部逻辑）。
 */
@Component
public class StreamHub {

    private static final Logger log = LoggerFactory.getLogger(StreamHub.class);

    /** 订阅端在缓冲重放前收到：目标会话当前没有运行中的任务。 */
    public static final String TYPE_IDLE = "hub_idle";
    /** Hub 结束时向订阅者广播的最后一帧，随后关闭订阅连接。 */
    public static final String TYPE_END = "hub_end";

    private final ConcurrentHashMap<String, Hub> hubs = new ConcurrentHashMap<>();

    /** 单个运行中会话的事件枢纽。 */
    public static final class Hub {
        public final String conversationId;
        /** 事件缓冲（send/sendLive 都进，按真实顺序；供 /live 重放）。以 buffer 自身为锁同步。 */
        public final List<Map<String, Object>> buffer = new ArrayList<>();
        /** 实时订阅者（GET /live）；发送失败由发送方摘除。 */
        public final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        /** 显式取消标志（/stop 置位）：作为各 Emitter 的 shared 标志与 Orchestrator 的 sharedCancel。 */
        public final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        public volatile boolean running = true;

        Hub(String conversationId) {
            this.conversationId = conversationId;
        }

        /** 追加事件并广播给所有订阅者（单个订阅者发送失败只摘除，不影响任务）。 */
        public void record(Map<String, Object> event) {
            synchronized (buffer) {
                buffer.add(event);
            }
            for (SseEmitter sub : subscribers) {
                if (!SafeSseEmitter.isAlive(sub)) {
                    subscribers.remove(sub);
                    continue;
                }
                try {
                    sub.send(SseEmitter.event().data(event, org.springframework.http.MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    subscribers.remove(sub);
                }
            }
        }
    }

    /** 会话任务开始：创建 Hub。若同会话已有 Hub（入口 409 保护应已拦截），复用旧的。 */
    public Hub begin(String conversationId) {
        Hub hub = hubs.computeIfAbsent(conversationId, Hub::new);
        hub.running = true;
        return hub;
    }

    /** 会话任务结束：广播结束帧、关闭订阅者、摘除会话（之后 /live 返回 idle，历史走 messages 接口）。 */
    public void end(String conversationId) {
        Hub hub = hubs.remove(conversationId);
        if (hub == null) {
            return;
        }
        hub.running = false;
        Map<String, Object> endFrame = Map.of("type", TYPE_END, "conversationId", conversationId);
        for (SseEmitter sub : hub.subscribers) {
            if (!SafeSseEmitter.isAlive(sub)) {
                continue;
            }
            try {
                sub.send(SseEmitter.event().data(endFrame, org.springframework.http.MediaType.APPLICATION_JSON));
            } catch (Exception ignore) {
                // 订阅者已断开
            }
            try {
                sub.complete();
            } catch (Exception ignore) {
                // 可能已 complete
            }
        }
        hub.subscribers.clear();
    }

    /** 是否有运行中的任务（会话列表 running 徽标、发送入口 409 保护）。 */
    public boolean isRunning(String conversationId) {
        return hubs.containsKey(conversationId);
    }

    /** 取运行中会话的 Hub；无则返回 null（/live 据此返回 idle）。 */
    public Hub get(String conversationId) {
        return hubs.get(conversationId);
    }

    /** 显式停止：置取消标志并返回是否存在运行中的任务。 */
    public boolean cancel(String conversationId) {
        Hub hub = hubs.get(conversationId);
        if (hub == null) {
            return false;
        }
        hub.cancelFlag.set(true);
        log.info("[conv:{}] 收到停止请求，任务将尽快收尾", conversationId);
        return true;
    }
}
