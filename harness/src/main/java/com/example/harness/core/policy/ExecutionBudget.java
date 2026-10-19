package com.example.harness.core.policy;

/**
 * Cost ceiling for the main agent loop.
 *
 * <p>The first version counted every tool call, so a full-stack feature touching twenty files hit
 * the same ceiling as an agent spinning on one file — and got its editing tools withdrawn
 * mid-implementation. What actually needs bounding is <em>wasted</em> work, so the hard stop is
 * driven by the absence of progress; raw volume only survives as a safety net far above anything
 * a real task needs, and the scope of the request scales the advisory nudge.
 */
public final class ExecutionBudget {

    public enum State { OK, SOFT, HARD }

    public record Verdict(State state, String code, String message) {
        public boolean hard() { return state == State.HARD; }
        public boolean soft() { return state == State.SOFT; }
    }

    private static final Verdict OK = new Verdict(State.OK, null, null);

    private final int softCalls;
    private final int callCeiling;
    private final int wallClockSeconds;
    private final int unproductiveLimit;
    private boolean softAnnounced;
    private boolean hardAnnounced;

    public ExecutionBudget(int softCalls, int callCeiling, int wallClockSeconds,
                           int unproductiveLimit, TaskIntent intent) {
        double scope = scopeMultiplier(intent);
        this.softCalls = Math.max(1, (int) Math.round(Math.max(1, softCalls) * scope));
        this.callCeiling = Math.max(this.softCalls * 2, callCeiling);
        this.wallClockSeconds = Math.max(60, wallClockSeconds);
        this.unproductiveLimit = Math.max(2, unproductiveLimit);
    }

    /** A bounded fix should stay tight; a migration or a whole feature legitimately costs more. */
    private static double scopeMultiplier(TaskIntent intent) {
        if (intent == null) return 2.0;
        if (intent.boundedBugFix()) return 1.0;
        return intent.broadChange() ? 3.0 : 2.0;
    }

    /**
     * @param totalCalls       tool calls issued so far
     * @param unproductiveCalls calls since the last one that produced a new observation,
     *                          mutation or verification state
     * @param elapsedSeconds   wall-clock time since the loop started
     */
    public Verdict evaluate(int totalCalls, int unproductiveCalls, long elapsedSeconds) {
        if (!hardAnnounced) {
            if (unproductiveCalls >= unproductiveLimit) {
                hardAnnounced = true;
                return new Verdict(State.HARD, "BUDGET_HARD",
                        "连续 " + unproductiveCalls + " 次工具调用没有产生任何新的事实、修改或验证结果，"
                                + "判定为无进展。写入与命令类工具已收回；请基于现有证据立即输出最终结论，"
                                + "并逐条列出尚未完成的部分。");
            }
            if (totalCalls >= callCeiling) {
                hardAnnounced = true;
                return new Verdict(State.HARD, "BUDGET_HARD",
                        "已达单次任务的工具调用安全上限（" + callCeiling + " 次）。写入与命令类工具已收回；"
                                + "请基于现有证据立即输出最终结论，并逐条列出尚未完成的部分。");
            }
            // Time alone never stops a task that is still making progress: a large feature simply
            // takes longer than a one-line fix.
            if (elapsedSeconds >= wallClockSeconds && unproductiveCalls >= unproductiveLimit / 2) {
                hardAnnounced = true;
                return new Verdict(State.HARD, "BUDGET_HARD",
                        "已运行 " + elapsedSeconds + " 秒且近期没有实质进展。写入与命令类工具已收回；"
                                + "请基于现有证据立即输出最终结论，并逐条列出尚未完成的部分。");
            }
        }
        if (!softAnnounced && !hardAnnounced && totalCalls >= softCalls) {
            softAnnounced = true;
            return new Verdict(State.SOFT, "BUDGET_SOFT",
                    "已使用 " + totalCalls + " 次工具调用。任务不会因此停止，"
                            + "但请合并剩余步骤、优先完成必要验证，不要展开与本次需求无关的探索。");
        }
        return OK;
    }

    public int softCalls() { return softCalls; }
    public int callCeiling() { return callCeiling; }
}
