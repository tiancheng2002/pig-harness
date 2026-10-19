package com.example.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 MCP Streamable HTTP 客户端（M5-F7，手写 JSON-RPC 2.0 实现）。
 *
 * <p>协议要点（MCP Streamable HTTP 传输）：
 * <ul>
 *   <li>所有消息 POST 到服务器端点，Accept 同时声明 application/json 与 text/event-stream；</li>
 *   <li>initialize 响应头可能携带 Mcp-Session-Id，后续请求须带回；</li>
 *   <li>initialize 成功后发送 notifications/initialized 通知（期待 202）；</li>
 *   <li>响应可能是普通 JSON，也可能是 SSE 帧（逐行 data:），两者都解析；</li>
 *   <li>不订阅服务器主动推送（GET SSE）——本客户端只做工具调用，无需该能力。</li>
 * </ul>
 *
 * <p>实现可替换：McpConnectionManager 只依赖 {@link McpClient} 接口，日后如切换官方
 * io.modelcontextprotocol.sdk，重写本类即可。
 */
public class McpHttpClient implements McpClient {

    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final ObjectMapper om = new ObjectMapper();
    private final String endpoint;
    private final Map<String, String> extraHeaders;
    private final HttpClient http;
    private final Duration callTimeout;
    private volatile String sessionId;
    private long nextId = 1;

    public McpHttpClient(String url, Map<String, String> headers, int connectTimeoutMs, int callTimeoutMs) {
        this.endpoint = url.trim();
        this.extraHeaders = headers == null ? Map.of() : headers;
        this.callTimeout = Duration.ofMillis(Math.max(1_000, callTimeoutMs));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1_000, connectTimeoutMs)))
                .build();
    }

    /** initialize 握手并记录会话；随后发送 initialized 通知。返回 serverInfo+capabilities 结果。 */
    @Override
    public Map<String, Object> initialize() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "pig-harness", "version", "0.1"));
        JsonNode result = postRpc("initialize", params, false);
        sendNotification("notifications/initialized", null);
        return om.convertValue(result, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    /** tools/list：返回工具清单（name/description/inputSchema）。 */
    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTools() throws Exception {
        JsonNode result = postRpc("tools/list", Map.of(), false);
        JsonNode tools = result.path("tools");
        List<Map<String, Object>> out = new ArrayList<>();
        if (tools.isArray()) {
            for (JsonNode t : tools) {
                out.add(om.convertValue(t, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                }));
            }
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CallResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        JsonNode result = postRpc("tools/call",
                Map.of("name", toolName, "arguments", arguments == null ? Map.of() : arguments), false);
        JsonNode content = result.path("content");
        List<Map<String, Object>> items = new ArrayList<>();
        if (content.isArray()) {
            for (JsonNode c : content) {
                items.add(om.convertValue(c, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                }));
            }
        }
        return new CallResult(items, result.path("isError").asBoolean(false));
    }

    @Override
    public void close() {
        if (sessionId == null) return;
        try {
            HttpRequest request = baseBuilder().DELETE().build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // 尽力而为
        }
        sessionId = null;
    }

    // ---------- 协议底层 ----------

    private JsonNode postRpc(String method, Object params, boolean notification) throws Exception {
        long id = nextId++;
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        if (params != null) body.put("params", params);
        if (!notification) body.put("id", id);

        HttpRequest request = baseBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<java.io.InputStream> resp =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = resp.statusCode();
        if (notification) {
            if (status / 100 != 2) {
                throw new IllegalStateException("通知 " + method + " 失败: HTTP " + status);
            }
            resp.body().close();
            return null;
        }
        if (status / 100 != 2) {
            String text = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("HTTP " + status + (text.isBlank() ? "" : ": " + abbreviate(text)));
        }
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        JsonNode rpcResponse;
        if (contentType.toLowerCase().contains("text/event-stream")) {
            rpcResponse = readSseResponse(resp.body(), id);
        } else {
            String text = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            rpcResponse = om.readTree(text);
        }
        if (rpcResponse.hasNonNull("error")) {
            JsonNode err = rpcResponse.path("error");
            throw new IllegalStateException("MCP 错误 " + err.path("code").asInt()
                    + ": " + err.path("message").asText("unknown"));
        }
        return rpcResponse.path("result");
    }

    private void sendNotification(String method, Object params) throws Exception {
        postRpc(method, params, true);
    }

    /** 从 SSE 帧中取出与请求 id 匹配的 JSON-RPC 响应。 */
    private JsonNode readSseResponse(java.io.InputStream body, long expectedId) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            JsonNode fallback = null;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                try {
                    JsonNode node = om.readTree(data);
                    if (node.hasNonNull("id") && node.get("id").asLong() == expectedId) {
                        return node;
                    }
                    if (node.has("result") || node.has("error")) fallback = node;
                } catch (Exception ignored) {
                    // 非本请求的帧（如注释/事件）忽略
                }
            }
            if (fallback != null) return fallback;
            throw new IllegalStateException("SSE 流中未找到响应帧");
        }
    }

    private HttpRequest.Builder baseBuilder() {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(callTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            b.header(SESSION_HEADER, sessionId);
        }
        for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
            if (h.getKey() != null && h.getValue() != null) {
                b.header(h.getKey(), h.getValue());
            }
        }
        return b;
    }

    private String abbreviate(String s) {
        s = s.replace('\n', ' ');
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
