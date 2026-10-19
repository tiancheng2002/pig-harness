package com.example.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP stdio 传输客户端：以本地子进程运行 MCP 服务器，JSON-RPC 2.0 走
 * stdin/stdout（按 MCP 规范为换行分隔 JSON，单行内不得有嵌入换行）。
 *
 * <p>要点：
 * <ul>
 *   <li>常驻读线程按 id 分发响应到挂起请求；通知帧与非 JSON 行（部分服务器会往 stdout 打日志）宽容忽略；</li>
 *   <li>进程退出（onExit/EOF）时让所有挂起调用以明确错误失败，调用方（连接管理器）走 lazy 重连；</li>
 *   <li>initialize 用独立的更长超时（npx 等冷启动需下载依赖）；</li>
 *   <li>Windows 上把 npx/npm 等批处理 shim 解析为 .cmd/.bat 并经 {@code cmd /c} 启动
 *       （ProcessBuilder 无法直接执行批处理），参数引号由 Java 注入；</li>
 *   <li>close 先关 stdin 触发服务器优雅退出，兜底 destroy / destroyForcibly。</li>
 * </ul>
 */
public class McpStdioClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpStdioClient.class);

    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper om = new ObjectMapper();
    private final String label;
    private final List<String> args;
    private final Map<String, String> env;
    private final long initTimeoutMs;
    private final long callTimeoutMs;
    private final String resolvedExecutable;
    /** stdin 写锁：请求写入与 close 关流互斥。 */
    private final Object stdinLock = new Object();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private volatile Process process;
    private BufferedWriter stdin;
    private volatile String deadReason;

    public McpStdioClient(String label, String command, List<String> args, Map<String, String> env,
                          long initTimeoutMs, long callTimeoutMs) {
        this.label = label == null ? "mcp" : label;
        this.args = args == null ? List.of() : args;
        this.env = env == null ? Map.of() : env;
        this.initTimeoutMs = Math.max(1_000, initTimeoutMs);
        this.callTimeoutMs = Math.max(1_000, callTimeoutMs);
        this.resolvedExecutable = resolveExecutable(command == null ? "" : command.trim());
    }

    /** 完整启动命令（解析后的可执行文件 + 参数；Windows 批处理经 cmd /c）。 */
    private List<String> fullCommand() {
        List<String> out = new ArrayList<>();
        String lower = resolvedExecutable.toLowerCase();
        if (WINDOWS && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
            out.add("cmd.exe");
            out.add("/c");
        }
        out.add(resolvedExecutable);
        out.addAll(args);
        return out;
    }

    /**
     * Windows 命令解析：裸命令名优先在 PATH 中找 .cmd/.bat（Node 的 npx/npm shim 是批处理，
     * 必须先于 .exe 命中），找不到则原样交给 CreateProcess（自动补 .exe 并搜 PATH）；
     * 显式路径无扩展名时按 .cmd/.bat/.exe 探测补全。非 Windows 原样返回。
     */
    private static String resolveExecutable(String command) {
        if (!WINDOWS || command.isBlank()) return command;
        boolean hasPath = command.contains("\\") || command.contains("/") || command.matches("^[A-Za-z]:.*");
        String lower = command.toLowerCase();
        boolean hasExt = lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat")
                || lower.endsWith(".com") || lower.endsWith(".ps1");
        if (!hasExt && hasPath) {
            for (String ext : new String[]{".cmd", ".bat", ".exe"}) {
                Path cand = Paths.get(command + ext);
                if (Files.isRegularFile(cand)) return cand.toString();
            }
            return command;
        }
        if (!hasExt) {
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                for (String dir : pathEnv.split(";", -1)) {
                    if (dir.isBlank()) continue;
                    for (String ext : new String[]{".cmd", ".bat"}) {
                        Path cand = Paths.get(dir.trim()).resolve(command + ext);
                        if (Files.isRegularFile(cand)) return cand.toString();
                    }
                }
            }
        }
        return command;
    }

    @Override
    public Map<String, Object> initialize() throws Exception {
        if (process == null) startProcess();
        Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "pig-harness", "version", "0.1"));
        JsonNode result = rpc("initialize", params, initTimeoutMs, false);
        rpc("notifications/initialized", null, 0, true);
        return om.convertValue(result, MAP_TYPE);
    }

    @Override
    public List<Map<String, Object>> listTools() throws Exception {
        if (process == null) startProcess();
        JsonNode result = rpc("tools/list", Map.of(), callTimeoutMs, false);
        JsonNode tools = result.path("tools");
        List<Map<String, Object>> out = new ArrayList<>();
        if (tools.isArray()) {
            for (JsonNode t : tools) {
                out.add(om.convertValue(t, MAP_TYPE));
            }
        }
        return out;
    }

    @Override
    public CallResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        if (process == null) startProcess();
        JsonNode result = rpc("tools/call",
                Map.of("name", toolName, "arguments", arguments == null ? Map.of() : arguments),
                callTimeoutMs, false);
        JsonNode content = result.path("content");
        List<Map<String, Object>> items = new ArrayList<>();
        if (content.isArray()) {
            for (JsonNode c : content) {
                items.add(om.convertValue(c, MAP_TYPE));
            }
        }
        return new CallResult(items, result.path("isError").asBoolean(false));
    }

    @Override
    public void close() {
        Process p;
        synchronized (stdinLock) {
            if (stdin != null) {
                try {
                    stdin.close();
                } catch (Exception ignored) {
                    // 尽力而为
                }
                stdin = null;
            }
            p = process;
        }
        if (p == null) return;
        try {
            if (p.isAlive() && !p.waitFor(1_500, TimeUnit.MILLISECONDS)) {
                p.destroy();
                if (!p.waitFor(1_000, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    // ---------- 进程与协议底层 ----------

    private void startProcess() throws IOException {
        List<String> cmd = fullCommand();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        pb.redirectErrorStream(false);
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("无法启动 MCP 服务器进程 " + cmd.get(0) + ": " + e.getMessage(), e);
        }
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        BufferedReader stderr = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

        Thread reader = new Thread(() -> readLoop(stdout), "mcp-stdio-" + label);
        reader.setDaemon(true);
        reader.start();
        Thread errDrain = new Thread(() -> drainStderr(stderr), "mcp-stdio-" + label + "-err");
        errDrain.setDaemon(true);
        errDrain.start();
        process.onExit().thenRun(this::onProcessExit);
    }

    /** 读线程主循环：按 id 分发响应；EOF 或解析失败不影响其余帧。 */
    private void readLoop(BufferedReader stdout) {
        String line;
        try {
            while ((line = stdout.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) != '{') continue;
                JsonNode node;
                try {
                    node = om.readTree(trimmed);
                } catch (Exception ignored) {
                    continue;
                }
                JsonNode idNode = node.get("id");
                if (idNode == null || idNode.isNull()) continue; // 通知帧，忽略
                long id = idNode.isNumber() ? idNode.asLong() : -1;
                if (id < 0) continue;
                CompletableFuture<JsonNode> f = pending.remove(id);
                if (f != null) f.complete(node);
            }
        } catch (Exception ignored) {
            // 流被 close 关闭属正常路径
        }
        markDead("子进程输出流已关闭");
    }

    private void drainStderr(BufferedReader stderr) {
        String line;
        try {
            while ((line = stderr.readLine()) != null) {
                // stderr 持续排空防子进程卡死；内容进日志便于排查
                log.info("[mcp-stdio:{}] {}", label, line);
            }
        } catch (Exception ignored) {
            // 进程退出后流关闭
        }
    }

    private void onProcessExit() {
        int code;
        try {
            code = process.exitValue();
        } catch (Exception e) {
            code = -1;
        }
        markDead("子进程已退出（code=" + code + "）");
    }

    private void markDead(String reason) {
        deadReason = reason;
        for (Map.Entry<Long, CompletableFuture<JsonNode>> e : pending.entrySet()) {
            if (pending.remove(e.getKey(), e.getValue())) {
                e.getValue().completeExceptionally(new IllegalStateException(reason));
            }
        }
    }

    /** 发送请求并等待响应。notification=true 时不等待（stdin 单行写入后即返回）。 */
    private JsonNode rpc(String method, Object params, long timeoutMs, boolean notification) throws Exception {
        String dead = deadReason;
        if (dead != null) throw new IllegalStateException(dead);
        long id = notification ? -1 : nextId.getAndIncrement();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) msg.put("params", params);
        if (!notification) msg.put("id", id);
        CompletableFuture<JsonNode> f = null;
        if (!notification) {
            f = new CompletableFuture<>();
            pending.put(id, f);
        }
        String line;
        try {
            line = om.writeValueAsString(msg);
        } catch (Exception e) {
            if (f != null) pending.remove(id);
            throw new IOException("序列化失败: " + e.getMessage(), e);
        }
        try {
            synchronized (stdinLock) {
                if (stdin == null) throw new IOException("stdin 已关闭（连接正在关闭）");
                stdin.write(line);
                stdin.newLine();
                stdin.flush();
            }
        } catch (IOException e) {
            if (f != null) pending.remove(id);
            throw new IOException("写入 stdin 失败: " + e.getMessage(), e);
        }
        if (notification) return null;
        try {
            JsonNode resp = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (resp.hasNonNull("error")) {
                JsonNode err = resp.path("error");
                throw new IllegalStateException("MCP 错误 " + err.path("code").asInt()
                        + ": " + err.path("message").asText("unknown"));
            }
            return resp.path("result");
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IllegalStateException("请求 " + method + " 超时（" + timeoutMs + "ms）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }
}
