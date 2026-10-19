package com.example.harness.core.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local circuit breaker for repeated tool calls. It never ends the task: it only blocks an
 * identical call after the same outcome has already been observed without intervening progress.
 */
public final class ToolLoopGuard {
    public record Decision(boolean blocked, String fingerprint, String message) {}

    private final int noProgressRepeatLimit;
    private final Map<String, Integer> repeatsSinceProgress = new HashMap<>();
    private final Set<String> observedOutcomes = new HashSet<>();
    private long progressVersion;

    public ToolLoopGuard(int noProgressRepeatLimit) {
        this.noProgressRepeatLimit = Math.max(1, noProgressRepeatLimit);
    }

    public Decision before(String tool, Map<String, Object> args) {
        String fingerprint = fingerprint(tool, args);
        int repeats = repeatsSinceProgress.getOrDefault(fingerprint, 0);
        if (repeats >= noProgressRepeatLimit) {
            return new Decision(true, fingerprint,
                    "检测到相同工具与参数在没有新增证据、文件修改或验证状态变化时重复调用。"
                            + "本次重复调用已跳过，但任务不会终止；请改用不同检索条件、修正代码、"
                            + "执行下一阶段验证，或在确认全部要求完成后报告结果。");
        }
        return new Decision(false, fingerprint, "");
    }

    /** Returns true only when this result adds a previously unseen observation. */
    public boolean record(String fingerprint, boolean ok, String output) {
        // Successful observations are tied to their target. Failures deliberately are not:
        // changing arguments while hitting the same failure category is not meaningful progress.
        String outcome = ok
                ? fingerprint + "|true|" + outcomeSignature(true, output)
                : "failure|" + outcomeSignature(false, output);
        if (observedOutcomes.add(outcome)) {
            progressVersion++;
            repeatsSinceProgress.clear();
            return true;
        }
        repeatsSinceProgress.merge(fingerprint, 1, Integer::sum);
        return false;
    }

    public long progressVersion() {
        return progressVersion;
    }

    static String fingerprint(String tool, Map<String, Object> args) {
        return (tool == null ? "tool" : tool) + ':' + canonical(args);
    }

    private static String outcomeSignature(boolean ok, String output) {
        if (!ok) return FailureClassifier.classify("tool", output).signature();
        String normalized = normalize(output);
        return normalized.length() <= 600 ? normalized : normalized.substring(0, 600);
    }

    @SuppressWarnings("unchecked")
    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(e -> String.valueOf(e.getKey())));
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : entries) {
                if (out.length() > 1) out.append(',');
                out.append(entry.getKey()).append('=').append(canonical(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : iterable) {
                if (out.length() > 1) out.append(',');
                out.append(canonical(item));
            }
            return out.append(']').toString();
        }
        return normalize(String.valueOf(value));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replace('\\', '/')
                .replaceAll("\\b\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?\\b", "<time>")
                .replaceAll("\\b\\d+ms\\b", "<duration>")
                .replaceAll("\\s+", " ").trim();
    }
}
