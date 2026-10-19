package com.example.harness.core.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskIntentTest {
    @Test void boundedBugFixImplicitlyRequiresRedGreenEvidence() {
        TaskIntent intent = TaskIntent.infer("请修复用户列表的分页边界错误");
        assertTrue(intent.boundedBugFix());
        assertTrue(intent.requiresMutation());
        assertTrue(intent.requiresReproduction());
        assertTrue(intent.requiresTestRun());
        assertFalse(intent.requiresBuild());
    }

    @Test void reproductionIsOnlyABlockingRequirementWhenTheUserAsksForIt() {
        // Inferred from "fix this bug": worth suggesting, but not worth undoing the repo for.
        TaskIntent inferred = TaskIntent.infer("请修复用户列表的分页边界错误");
        assertTrue(inferred.requiresReproduction());
        assertFalse(inferred.reproductionExplicit());

        // Spelled out by the user: a genuine precondition of delivery.
        TaskIntent asked = TaskIntent.infer("请先构造能稳定失败的自动化测试，再做最小修复");
        assertTrue(asked.requiresReproduction());
        assertTrue(asked.reproductionExplicit());
    }

    @Test void broadRefactorDoesNotUseSmallFixBudget() {
        assertFalse(TaskIntent.infer("重构多个模块并修复相关问题").boundedBugFix());
    }

    @Test void benchmarkWordingIsRecognizedAsBoundedFix() {
        assertTrue(TaskIntent.infer("当前分页功能有三个问题：第一页从第 11 条开始、最后一页为空、"
                + "非法分页参数返回 500。请先构造失败测试，再做最小修复，运行目标测试和 Maven 构建。")
                .boundedBugFix());
    }

    @Test void diagnosticRequestThatSaysDoNotModifyFilesIsReadOnly() {
        TaskIntent intent = TaskIntent.infer("仅诊断当前环境，不修改文件");
        assertFalse(intent.requiresMutation());
        assertFalse(intent.hasDeliveryRequirements());
    }
}
