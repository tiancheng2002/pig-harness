package com.example.harness.core.policy;

import java.util.Locale;

/** Converts noisy tool errors into stable recovery categories and actionable advice. */
public final class FailureClassifier {
    public record Failure(String signature, String advice) {}

    private FailureClassifier() {}

    public static Failure classify(String tool, String output) {
        String value = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (value.contains("syntax error") && (value.contains("unexpected end")
                || value.contains("unexpected eof") || value.contains("end of file"))) {
            return new Failure("shell:incomplete-syntax",
                    "这是命令结构/引号错误，不是系统环境故障。不要重试同一段复杂内联命令；"
                            + "改成单条原生命令，或先写入脚本再执行。Bash 本身支持多行，问题在于命令不完整。");
        }
        if (value.contains("offline mode") || value.contains("in offline mode")
                || value.contains("cannot access central") || value.contains("离线模式")) {
            return new Failure("maven:offline-cache-miss",
                    "本机 Maven 缓存不完整，失败来自 -o/离线环境而非业务代码。移除 -o 后只重试一次。"
                            + "不要遍历本机依赖缓存。 ");
        }
        if ((value.contains("port") && value.contains("already in use"))
                || value.contains("address already in use") || value.contains("端口") && value.contains("占用")) {
            return new Failure("runtime:port-in-use",
                    "端口冲突属于运行环境问题。不要终止来源不明的进程；优先使用框架内测试，"
                            + "只有用户明确要求真实启动时才改用随机端口，并保证清理自己启动的进程。 ");
        }
        if (value.contains("compilation failure") || value.contains("cannot find symbol")
                || value.contains("编译失败") || value.contains("找不到符号")) {
            return new Failure("code:compilation",
                    "这是补丁不完整导致的代码错误。按第一条编译诊断回读相关文件，"
                            + "一次补齐 import、类型名和实现，再重跑最小验证。 ");
        }
        if (value.contains("command not found") || value.contains("is not recognized as an internal")
                || value.contains("不是内部或外部命令")) {
            return new Failure("shell:command-not-found",
                    "命令与当前 Shell 方言或 PATH 不匹配。读取 WORKSPACE_SNAPSHOT 中的真实方言，"
                            + "改用该环境的原生命令，不要原样重试。 ");
        }
        if (value.contains("执行超时") || value.contains("timed out") || value.contains("timeout")) {
            return new Failure((tool == null ? "tool" : tool) + ":timeout",
                    "调用超时。缩小验证范围或换用确定性测试；不要仅提高超时时间后原样重试。 ");
        }
        String normalized = value.replaceAll("[a-z]:[/\\\\][^\\s:]+", "<path>")
                .replaceAll("\\b\\d+\\b", "#")
                .replaceAll("\\s+", " ").trim();
        if (normalized.length() > 160) normalized = normalized.substring(0, 160);
        return new Failure((tool == null ? "tool" : tool) + ":" + normalized,
                "同类失败已经重复出现。停止原样重试，回读最小相关证据并更换策略；无法恢复时如实收尾。 ");
    }
}
