package com.example.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * OpenAI 兼容协议实现（DeepSeek / 通义 / OpenAI 均可）。
 * 使用 JDK 内置 HttpClient，POST {base-url}/chat/completions，统一走 SSE 流式（stream=true）；
 * listener 为 null 时仅累积结果（等价于阻塞调用）。
 * 支持 delta.content / delta.reasoning_content（DeepSeek-R1 等）与 tool_calls 增量。
 */
public class OpenAiCompatChatClient implements ChatClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 流式读取的无输出上限：超过视为上游断流（正在生成时 token 持续到达，长时间无字节=连接已死）。 */
    private static final long STALL_TIMEOUT_MS = 120_000;

    private final ObjectMapper om = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    /** 是否在请求体携带 stream_options.include_usage（DeepSeek/通义/OpenAI 均支持；个别兼容端点可能不认，可关闭） */
    private final boolean requestUsage;
    private final int maxCompletionTokens;

    public OpenAiCompatChatClient(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, true, 0);
    }

    public OpenAiCompatChatClient(String baseUrl, String apiKey, String model, boolean requestUsage) {
        this(baseUrl, apiKey, model, requestUsage, 0);
    }

    public OpenAiCompatChatClient(String baseUrl, String apiKey, String model,
                                  boolean requestUsage, int maxCompletionTokens) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://api.deepseek.com/v1" : baseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.endpoint = base + "/chat/completions";
        this.apiKey = apiKey;
        this.model = model;
        this.requestUsage = requestUsage;
        this.maxCompletionTokens = Math.max(0, maxCompletionTokens);
    }

    @Override
    public String modelName() {
        return model;
    }

    public ChatResult chat(List<LlmMessage> messages, List<LlmToolSpec> tools, StreamListener listener) {
        try {
            ObjectNode body = om.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.2);
            body.put("stream", true);
            if (maxCompletionTokens > 0) body.put("max_tokens", maxCompletionTokens);
            if (requestUsage) {
                // 请求回传真实用量（最后一个 chunk 带 usage 且 choices 为空），上下文压缩决策以此为基准
                body.putObject("stream_options").put("include_usage", true);
            }

            ArrayNode msgs = body.putArray("messages");
            for (LlmMessage m : messages) {
                ObjectNode n = msgs.addObject();
                n.put("role", m.role());
                if (m.content() != null) n.put("content", m.content());
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    ArrayNode tcs = n.putArray("tool_calls");
                    for (LlmToolCall c : m.toolCalls()) {
                        ObjectNode tc = tcs.addObject();
                        tc.put("id", c.id());
                        tc.put("type", "function");
                        ObjectNode fn = tc.putObject("function");
                        fn.put("name", c.name());
                        fn.put("arguments", c.arguments());
                    }
                }
                if (m.toolCallId() != null) n.put("tool_call_id", m.toolCallId());
            }

            if (tools != null && !tools.isEmpty()) {
                ArrayNode ts = body.putArray("tools");
                for (LlmToolSpec t : tools) {
                    ObjectNode t0 = ts.addObject();
                    t0.put("type", "function");
                    ObjectNode fn = t0.putObject("function");
                    fn.put("name", t.name());
                    fn.put("description", t.description());
                    fn.set("parameters", om.readTree(t.parametersJson()));
                }
            }

            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(300))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            om.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String errBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("LLM HTTP " + resp.statusCode()
                        + ": " + truncate(errBody, 500));
            }

            return parseStream(resp.body(), listener);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    /** 逐行解析 SSE：data: {...} 帧 → delta 增量累积；[DONE] 结束。带断流看门狗与末尾 usage 采集。 */
    private ChatResult parseStream(InputStream in, StreamListener listener) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        int[] usage = null; // {promptTokens, completionTokens}（include_usage 时最后一个 chunk 携带）

        // tool_calls 增量按 index 聚合（个别端点缺 index 时按出现顺序兜底）
        TreeMap<Integer, StringBuilder> argBuf = new TreeMap<>();
        TreeMap<Integer, String> idBuf = new TreeMap<>();
        TreeMap<Integer, String> nameBuf = new TreeMap<>();
        int fallbackIdx = 0;

        // 看门狗：读取阻塞超过 STALL_TIMEOUT_MS 时关闭流，避免上游断连后永久挂起
        java.util.concurrent.atomic.AtomicLong lastActivity =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        java.util.concurrent.atomic.AtomicBoolean stalled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                while (!stalled.get()) {
                    Thread.sleep(5_000);
                    if (System.currentTimeMillis() - lastActivity.get() > STALL_TIMEOUT_MS) {
                        stalled.set(true);
                        try (InputStream dead = in) {
                            // close 触发阻塞中的 readLine 抛出/返回
                        }
                        break;
                    }
                }
            } catch (InterruptedException e) {
                // 正常结束，看门狗退出
            } catch (IOException ignored) {
            }
        });

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastActivity.set(System.currentTimeMillis());
                if (stalled.get()) break;
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) continue;
                if ("[DONE]".equals(payload)) break;

                JsonNode root;
                JsonNode delta;
                try {
                    root = om.readTree(payload);
                    delta = root.path("choices").path(0).path("delta");
                } catch (Exception e) {
                    continue; // 心跳/坏帧跳过
                }
                // usage-only 末尾 chunk（choices 为空、usage 有值）：记录真实用量
                JsonNode usageNode = root.path("usage");
                if (usageNode.isObject() && usageNode.path("prompt_tokens").isNumber()) {
                    usage = new int[]{usageNode.path("prompt_tokens").asInt(),
                            usageNode.path("completion_tokens").asInt()};
                }
                if (delta.isMissingNode() || delta.isNull()) continue;

                String text = asText(delta, "content");
                if (text != null) {
                    content.append(text);
                    if (listener != null) listener.onText(text);
                }
                // 思考内容：DeepSeek reasoning_content，兼容 OpenRouter/Qwen 的 reasoning
                String think = asText(delta, "reasoning_content");
                if (think == null) think = asText(delta, "reasoning");
                if (think != null) {
                    reasoning.append(think);
                    if (listener != null) listener.onReasoning(think);
                }

                JsonNode tcs = delta.path("tool_calls");
                if (tcs.isArray()) {
                    if (listener != null && !tcs.isEmpty()) listener.onToolCallDelta();
                    for (JsonNode tc : tcs) {
                        int idx = tc.path("index").isInt()
                                ? tc.path("index").asInt() : fallbackIdx++;
                        idBuf.putIfAbsent(idx, asText(tc, "id"));
                        JsonNode fn = tc.path("function");
                        nameBuf.putIfAbsent(idx, asText(fn, "name"));
                        String args = asText(fn, "arguments");
                        if (args != null) {
                            argBuf.computeIfAbsent(idx, k -> new StringBuilder()).append(args);
                        }
                    }
                }
            }
        } finally {
            watchdog.interrupt();
        }
        if (stalled.get()) {
            throw new IOException("模型响应中断（超过 120 秒没有任何输出，已断开并重试）");
        }

        List<LlmToolCall> calls = new ArrayList<>();
        for (Integer idx : idBuf.isEmpty() ? nameBuf.keySet() : idBuf.keySet()) {
            String id = idBuf.get(idx);
            String name = nameBuf.get(idx);
            if (name == null || name.isBlank()) continue;
            calls.add(new LlmToolCall(id == null ? "call_" + idx : id, name,
                    argBuf.getOrDefault(idx, new StringBuilder()).toString()));
        }
        return new ChatResult(
                content.isEmpty() ? null : content.toString(),
                reasoning.isEmpty() ? null : reasoning.toString(),
                calls,
                usage == null ? null
                        : new ChatResult.Usage(usage[0] <= 0 ? null : usage[0],
                                usage[1] <= 0 ? null : usage[1]));
    }

    private String asText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isTextual() ? n.asText() : null;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
