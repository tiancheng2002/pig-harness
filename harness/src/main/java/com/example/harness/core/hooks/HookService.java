package com.example.harness.core.hooks;

import com.example.harness.config.HarnessProperties;
import com.example.harness.tools.ShellTool;
import com.example.harness.tools.ToolContext;
import com.example.harness.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * P1 验证 Hooks：项目可在工作根放 `.harness/hooks.json` 声明交付前必须通过的验证命令。
 * AgentLoop 在模型给出最终结论前运行 verify 钩子——失败回灌模型自动修复重试（max-retries 上限），
 * 耗尽后结论强制附带失败详情，绝不静默放过。命令经 ShellTool 执行（超时/解码/截断/deny-patterns 复用）。
 * 读取时机 = 每任务开始（不缓存，改文件即生效）；无文件 = 零行为，完全向后兼容。
 */
@Component
public class HookService {

    private static final Logger log = LoggerFactory.getLogger(HookService.class);

    private final ShellTool shellTool;
    private final HarnessProperties props;
    private final ObjectMapper om;
    /** shell 工具被禁用时 hooks 同步禁用（启动时判定一次并告警）。 */
    private boolean disabledByShell;

    public HookService(ShellTool shellTool, HarnessProperties props, ObjectMapper om) {
        this.shellTool = shellTool;
        this.props = props;
        this.om = om;
    }

    @PostConstruct
    void checkShellEnabled() {
        disabledByShell = !props.getShell().isEnabled();
        if (disabledByShell) {
            log.warn("harness.shell.enabled=false：验证钩子（.harness/hooks.json）已同步禁用");
        }
    }

    public record Hook(String name, String command, int timeoutSeconds) {
    }

    public record HookResult(String name, String command, boolean ok, String output, long durationMs) {
    }

    /** 读取工作根的 hooks 配置；无文件/解析失败 = 空清单（零行为）。 */
    public List<Hook> load(Path root) {
        if (disabledByShell || root == null) return List.of();
        Path file = root.resolve(".harness").resolve("hooks.json");
        if (!Files.isRegularFile(file)) return List.of();
        try {
            JsonNode node = om.readTree(Files.readString(file));
            JsonNode verify = node.path("verify");
            if (!verify.isArray() || verify.isEmpty()) return List.of();
            List<Hook> out = new ArrayList<>();
            for (JsonNode h : verify) {
                String command = h.path("command").asText("");
                if (command.isBlank()) continue;
                out.add(new Hook(
                        h.path("name").asText(command.length() > 30 ? command.substring(0, 30) : command),
                        command,
                        h.path("timeout").asInt(props.getShell().getDefaultTimeoutSeconds())));
            }
            log.info("已加载验证钩子 {} 条（{}）", out.size(), file);
            return out;
        } catch (Exception e) {
            log.warn("hooks.json 解析失败（忽略钩子）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 依次运行 verify 钩子；每条发 `hook {name, ok, durationMs, output}` 事件（落库回放）。
     * 返回失败的钩子清单（全部通过 = 空清单）。
     */
    public List<HookResult> runAll(List<Hook> hooks, ToolContext ctx, AgentEventSink out) {
        List<HookResult> failures = new ArrayList<>();
        for (Hook hook : hooks) {
            long start = System.currentTimeMillis();
            ToolResult r;
            try {
                r = shellTool.execute(java.util.Map.of(
                        "command", hook.command(),
                        "timeout_seconds", Math.min(hook.timeoutSeconds(), props.getShell().getMaxTimeoutSeconds())),
                        ctx);
            } catch (Exception e) {
                r = ToolResult.error("钩子执行异常: " + e.getMessage());
            }
            long duration = System.currentTimeMillis() - start;
            if (ctx.runContext() != null) ctx.runContext().metrics().addPhase("verification", duration);
            HookResult result = new HookResult(hook.name(), hook.command(), r.ok(),
                    truncate(r.output(), 1500), duration);
            if (out != null) {
                java.util.Map<String, Object> ev = new java.util.HashMap<>();
                ev.put("type", "hook");
                ev.put("name", result.name());
                ev.put("ok", result.ok());
                ev.put("durationMs", duration);
                ev.put("output", truncate(result.output(), 800));
                out.send(ev);
            }
            if (!result.ok()) {
                failures.add(result);
            }
        }
        return failures;
    }

    public int maxRetries() {
        return Math.max(0, props.getHooks().getMaxRetries());
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }

    /** AgentLoop.Emitter 的最小接口（避免循环依赖直接引 Emitter）。 */
    public interface AgentEventSink {
        void send(java.util.Map<String, Object> event);
    }
}
