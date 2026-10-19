package com.example.harness.core.policy;

import java.util.ArrayList;
import java.util.List;

import static com.example.harness.core.policy.ExecutionEvidence.VerificationKind.BUILD;
import static com.example.harness.core.policy.ExecutionEvidence.VerificationKind.TEST;

/** Prevents a coding task from being reported successful without server-observed delivery evidence. */
public final class CompletionGuard {
    /**
     * A missing piece of delivery evidence. Blocking items hold the report back and can mark the
     * run PARTIAL; advisory items are surfaced once and then let the agent finish, because
     * manufacturing them can cost more than the task itself.
     */
    public record Missing(String code, String message, boolean blocking) {
        public Missing(String code, String message) {
            this(code, message, true);
        }
    }

    public List<Missing> evaluate(TaskIntent intent, ExecutionEvidence evidence) {
        List<Missing> missing = new ArrayList<>();
        if (intent.requiresMutation() && evidence.mutations() == 0) {
            missing.add(new Missing("NEEDS_MUTATION", "任务要求修改代码，但尚无成功的文件修改"));
        }
        if (intent.requiresTestChange() && !evidence.testFileChanged()) {
            missing.add(new Missing("NEEDS_TEST_FILE", "任务要求补充自动化测试，但尚未修改测试文件"));
        }
        if (intent.requiresReproduction() && !evidence.hasReproductionBeforeProductionMutation()) {
            // Only the user's own wording makes reproduction a precondition. When it was merely
            // inferred from "fix this bug", a repo that is already green must not push the agent
            // into undoing the fix just to record a red run.
            missing.add(new Missing("NEEDS_REPRODUCTION",
                    "任务要求先复现，但尚无源码修改前的失败测试或复现命令证据",
                    intent.reproductionExplicit()));
        }
        if (intent.requiresTestRun() && !evidence.hasSuccessfulAfter(TEST, evidence.lastMutation() - 1)) {
            missing.add(new Missing("NEEDS_TEST", "缺少最后一次修改后的成功测试结果"));
        }
        if (intent.requiresBuild() && !evidence.hasSuccessfulAfter(BUILD, evidence.lastMutation() - 1)) {
            missing.add(new Missing("NEEDS_BUILD", "缺少最后一次修改后的成功构建结果"));
        }
        return List.copyOf(missing);
    }

    /** The subset that actually holds delivery back. */
    public static List<Missing> blocking(List<Missing> missing) {
        return missing.stream().filter(Missing::blocking).toList();
    }
}
