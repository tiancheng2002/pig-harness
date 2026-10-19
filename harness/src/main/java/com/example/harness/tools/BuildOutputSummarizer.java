package com.example.harness.tools;

import com.example.harness.core.policy.ExecutionEvidence;
import com.example.harness.core.policy.VerificationClassifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pulls the decisive lines out of a build or test log and pins them to the front of the tool
 * result.
 *
 * <p>Without this the agent only sees the head and tail of a truncated Maven log — and the
 * Surefire totals plus the assertion diff live in the middle. It then writes the log to a file
 * and greps it back, which costs a handful of tool calls per verification and produces
 * misleading exit codes whenever the pipeline ends in a grep that matched nothing.
 */
public final class BuildOutputSummarizer {

    private static final int MAX_LINES = 24;
    private static final int MAX_CHARS = 2_000;
    private static final int MAX_LINE_CHARS = 240;

    private BuildOutputSummarizer() {
    }

    /** True when the command is a build/test invocation whose log is worth condensing. */
    public static boolean applies(String command) {
        Set<ExecutionEvidence.VerificationKind> kinds = VerificationClassifier.classify(command);
        return kinds.contains(ExecutionEvidence.VerificationKind.TEST)
                || kinds.contains(ExecutionEvidence.VerificationKind.BUILD);
    }

    /**
     * Returns the pinned summary block, or an empty string when nothing recognizable was found
     * (unknown build tools fall back to the raw output unchanged).
     */
    public static String summarize(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        Set<String> kept = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        List<String> diffs = new ArrayList<>();
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (isOutcome(line, lower)) {
                kept.add(trim(line));
            } else if (isFailureDetail(lower)) {
                if (diffs.size() < 8) {
                    diffs.add(trim(line));
                }
            } else if (line.startsWith("[ERROR]")) {
                if (errors.size() < 10) {
                    errors.add(trim(line));
                }
            }
        }
        kept.addAll(diffs);
        kept.addAll(errors);
        if (kept.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【测试/构建结论（已自动摘录，无需再翻日志）】\n");
        int lines = 0;
        for (String line : kept) {
            if (lines >= MAX_LINES || sb.length() >= MAX_CHARS) {
                break;
            }
            sb.append(line).append('\n');
            lines++;
        }
        return sb.append("【以下为原始输出】\n").toString();
    }

    /** Totals and verdicts: Maven/Surefire, Gradle, Jest, pytest, go test, cargo test. */
    private static boolean isOutcome(String line, String lower) {
        return line.contains("Tests run:")
                || lower.contains("build success")
                || lower.contains("build failure")
                || line.startsWith("Tests:")
                || line.startsWith("Test Suites:")
                || (lower.startsWith("ok ") && lower.contains("coverage:"))
                || lower.startsWith("test result:")
                || (lower.contains("passed") && lower.contains("failed") && lower.startsWith("="))
                || lower.startsWith("failures (")
                || lower.startsWith("failed tests:")
                || lower.startsWith("--- fail")
                || lower.startsWith("failed ");
    }

    /** The assertion itself, which is what the agent actually needs to fix the code. */
    private static boolean isFailureDetail(String lower) {
        return lower.contains("but was:")
                || lower.contains("expected:")
                || lower.contains("assertionerror")
                || lower.contains("assertionfailederror")
                || lower.contains("comparisonfailure");
    }

    private static String trim(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + "…";
    }
}
