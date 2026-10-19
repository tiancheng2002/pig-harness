package com.example.harness.core.policy;

import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic delivery requirements inferred from the user's explicit wording. */
public record TaskIntent(boolean requiresMutation, boolean requiresReproduction,
                         boolean requiresTestChange, boolean requiresTestRun,
                         boolean requiresBuild, boolean boundedBugFix,
                         boolean reproductionExplicit, boolean broadChange) {
    private static final Pattern MUTATION = Pattern.compile(
            "修复|修改|实现|新增|添加|补充|重构|删除|创建|fix|implement|add|change|refactor|create",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern READ_ONLY = Pattern.compile(
            "不要修改任何文件|不修改(?:任何)?(?:代码|文件)|不改(?:代码|文件)|只读|仅分析|仅诊断|"
                    + "只输出.*报告|do not (?:change|modify) any|read[- ]only",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REPRODUCE = Pattern.compile(
            "复现|重现|先.{0,12}(?:失败|红灯).{0,8}测试|failing test|reproduc", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST_CHANGE = Pattern.compile(
            "(?:补充|新增|添加|编写|构造).{0,8}(?:失败|自动化)?测试|(?:add|write|create).{0,12}tests?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST_RUN = Pattern.compile(
            "运行.{0,6}测试|执行.{0,6}测试|跑.{0,4}测试|run.{0,8}tests?|pytest|mvn test|gradle test",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUILD = Pattern.compile(
            "构建|编译|打包|build|compile|package", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPAIR_VERB = Pattern.compile(
            "修复|解决|排查|\\bfix(?:ing|ed)?\\b|\\bresolve[ds]?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUG_SIGNAL = Pattern.compile(
            "bug|缺陷|错误|异常|问题|defect|error|issue", Pattern.CASE_INSENSITIVE);
    private static final Pattern BROAD_CHANGE = Pattern.compile(
            "重构|迁移|架构|全量|多个模块|完整功能|从零|全栈|前后端|端到端|整套|"
                    + "redesign|migration|architecture|refactor|multiple modules|"
                    + "end[- ]to[- ]end|full[- ]stack",
            Pattern.CASE_INSENSITIVE);

    /** Kept for callers/tests compiled against the original five-field shape. */
    public TaskIntent(boolean requiresMutation, boolean requiresReproduction,
                      boolean requiresTestChange, boolean requiresTestRun,
                      boolean requiresBuild) {
        this(requiresMutation, requiresReproduction, requiresTestChange,
                requiresTestRun, requiresBuild, false, requiresReproduction, false);
    }

    public static TaskIntent infer(String prompt) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        boolean mutation = MUTATION.matcher(text).find() && !READ_ONLY.matcher(text).find();
        boolean tests = TEST_CHANGE.matcher(text).find();
        boolean broad = BROAD_CHANGE.matcher(text).find();
        boolean boundedFix = mutation && REPAIR_VERB.matcher(text).find()
                && BUG_SIGNAL.matcher(text).find() && !broad;
        // A red/green signal is worth asking for on any bounded fix, but only the user's own
        // wording makes it a precondition of delivery: inferring it unconditionally sends the agent
        // reverse-engineering a baseline (git stash / checkout) whenever the repo is already fixed.
        boolean reproExplicit = REPRODUCE.matcher(text).find() || tests;
        return new TaskIntent(mutation,
                boundedFix || reproExplicit,
                tests,
                boundedFix || tests || TEST_RUN.matcher(text).find(),
                BUILD.matcher(text).find(),
                boundedFix,
                reproExplicit,
                broad);
    }

    public boolean hasDeliveryRequirements() {
        return requiresMutation || requiresReproduction || requiresTestChange
                || requiresTestRun || requiresBuild;
    }
}
