package com.example.harness.core;

import com.example.harness.core.memory.EpisodicStore;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.task.ExecutionResult;
import com.example.harness.core.task.RunStatus;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.ChatResult;
import com.example.harness.llm.LlmMessage;
import com.example.harness.llm.TokenEstimator;
import com.example.harness.model.Knowledge;
import com.example.harness.model.Task;
import com.example.harness.model.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话执行器：闲聊/问答不走规划与工具，按用户选择的模型直接流式回答。
 * SSE 事件：text_delta（增量正文，不落库）→ thinking（聚合思考，可选）→ done（完整结论）。
 */
@Component
public class ChatExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChatExecutor.class);

    private final EpisodicStore episodicStore;
    private final ConversationStore conversationStore;

    public ChatExecutor(EpisodicStore episodicStore, ConversationStore conversationStore) {
        this.episodicStore = episodicStore;
        this.conversationStore = conversationStore;
    }

    public ExecutionResult execute(Task task, SseEmitter emitter, ChatClient client,
                        List<Knowledge> knowledge,
                        List<LlmMessage> history, List<Map<String, Object>> transcript) {
        return execute(task, emitter, client, knowledge, history, transcript, null);
    }

    /** 带 Hub 的会话入口（体验升级七）：事件同步进会话缓冲；hub.cancelFlag 置位后尽快收尾。 */
    public ExecutionResult execute(Task task, SseEmitter emitter, ChatClient client,
                        List<Knowledge> knowledge,
                        List<LlmMessage> history, List<Map<String, Object>> transcript,
                        com.example.harness.core.stream.StreamHub.Hub hub) {
        java.util.concurrent.atomic.AtomicBoolean cancelFlag =
                hub != null ? hub.cancelFlag : null;
        try {
            if (cancelFlag != null && cancelFlag.get()) {
                return ExecutionResult.stopped("（已停止）", null, "CHAT");
            }
            List<LlmMessage> msgs = new ArrayList<>(ChatModeRouter.chatSystemPrompt());
            String memory = TaskDispatcher.knowledgePrompt(knowledge);
            if (!memory.isEmpty()) {
                msgs.add(LlmMessage.system(memory));
            }
            msgs.addAll(history);
            msgs.add(LlmMessage.user(task.prompt()));

            StringBuilder answer = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            ChatResult resp = client.chat(msgs, List.of(), new ChatClient.StreamListener() {
                @Override
                public void onText(String delta) {
                    answer.append(delta);
                    send(emitter, Map.of("type", "text_delta", "delta", delta), null, hub);
                }

                @Override
                public void onReasoning(String delta) {
                    reasoning.append(delta);
                    send(emitter, Map.of("type", "thinking_delta", "delta", delta), null, hub);
                }
            });

            String conclusion = answer.isEmpty()
                    ? (resp.content() == null ? "（模型未返回内容）" : resp.content())
                    : answer.toString();
            if (cancelFlag != null && cancelFlag.get()) {
                conclusion = answer.isEmpty() ? "（已停止）" : answer + "\n\n（已停止）";
            }
            // usage 事件（仅推流不落库）：真实 prompt_tokens 优先，无则估算——前端实时更新容量徽标；
            // 真实用量同时持久化到会话（容量徽标取 max(真实, 估算)，不再随轮次结束回落）
            ChatResult.Usage usage = resp.usage();
            Integer prompt = usage != null ? usage.promptTokens() : null;
            if (task.conversationId() != null && prompt != null && prompt > 0) {
                conversationStore.updateLastPromptTokens(task.conversationId(), prompt);
            }
            send(emitter, Map.of("type", "context_usage",
                    "promptTokens", prompt != null && prompt > 0
                            ? prompt : TokenEstimator.estimateMessages(msgs),
                    "estimated", prompt == null || prompt <= 0), null, hub);
            if (!reasoning.isEmpty()) {
                send(emitter, Map.of("type", "thinking", "text", reasoning.toString()), transcript, hub);
            }
            long traceId = episodicStore.save(Trace.of(task.prompt(), "[]", conclusion));
            log.info("[{}] 对话直答完成: {}", task.id(), truncate(conclusion, 100));
            return cancelFlag != null && cancelFlag.get()
                    ? ExecutionResult.stopped(conclusion, traceId, "CHAT")
                    : ExecutionResult.success(conclusion, traceId, "CHAT");
        } catch (Exception e) {
            log.error("[{}] 对话直答异常", task.id(), e);
            String conclusion = "回答异常：" + e.getMessage();
            Long traceId = null;
            try {
                traceId = episodicStore.save(Trace.of(task.prompt(), "[]", "ERROR: " + e.getMessage()));
            } catch (Exception ignore) {
                log.warn("异常轨迹写入失败: {}", ignore.getMessage());
            }
            return ExecutionResult.failed(conclusion, traceId, "CHAT", "CHAT_ERROR", e.getMessage());
        }
    }

    /** 流式事件发送；transcript 为 null 表示不落库（如 delta 类事件）；hub 非空时同步进会话事件缓冲。 */
    private void send(SseEmitter emitter, Map<String, Object> event,
                      List<Map<String, Object>> transcript,
                      com.example.harness.core.stream.StreamHub.Hub hub) {
        if (transcript != null) transcript.add(event);
        if (hub != null) hub.record(event);
        try {
            emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }
}
