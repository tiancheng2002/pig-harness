package com.example.harness.core.policy;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.example.harness.core.policy.ExecutionEvidence.VerificationKind.BUILD;
import static com.example.harness.core.policy.ExecutionEvidence.VerificationKind.TEST;
import static org.junit.jupiter.api.Assertions.*;

class CompletionGuardTest {
    @Test void pagingPromptRequiresAllDeliveryEvidence() {
        TaskIntent intent = TaskIntent.infer("修复分页问题：先构造失败测试，最小修复，运行目标测试和构建");
        assertTrue(intent.requiresMutation());
        assertTrue(intent.requiresReproduction());
        assertTrue(intent.requiresTestChange());
        assertTrue(intent.requiresTestRun());
        assertTrue(intent.requiresBuild());

        ExecutionEvidence evidence = new ExecutionEvidence();
        evidence.nextSequence(); evidence.recordMutation("src/test/java/PagingTest.java", true);
        evidence.nextSequence(); evidence.recordVerification(TEST, false); evidence.finishVerificationCall(false);
        evidence.nextSequence(); evidence.recordMutation("src/main/java/PagingService.java", true);
        evidence.nextSequence(); evidence.recordVerification(TEST, true); evidence.finishVerificationCall(true);
        evidence.nextSequence(); evidence.recordVerification(BUILD, true); evidence.finishVerificationCall(true);

        assertTrue(new CompletionGuard().evaluate(intent, evidence).isEmpty());
    }

    @Test void inferredReproductionIsAdvisoryAndDoesNotHoldDeliveryBack() {
        TaskIntent intent = TaskIntent.infer("请修复并发请求导致重复写入的缺陷");
        ExecutionEvidence evidence = new ExecutionEvidence();
        evidence.nextSequence(); evidence.recordMutation("fileEdit", "src/main/OrderService.java", true);
        evidence.nextSequence(); evidence.recordVerification(TEST, true); evidence.finishVerificationCall(true);

        java.util.List<CompletionGuard.Missing> missing = new CompletionGuard().evaluate(intent, evidence);
        assertEquals(java.util.List.of("NEEDS_REPRODUCTION"),
                missing.stream().map(CompletionGuard.Missing::code).toList());
        assertTrue(CompletionGuard.blocking(missing).isEmpty(),
                "未被用户要求的复现只是建议，不应把已完成的修复判为交付未完成");
    }

    @Test void explicitlyRequestedReproductionStillBlocks() {
        TaskIntent intent = TaskIntent.infer("请先构造能稳定失败的测试再修复这个缺陷");
        ExecutionEvidence evidence = new ExecutionEvidence();
        evidence.nextSequence(); evidence.recordMutation("fileEdit", "src/main/OrderService.java", true);
        evidence.nextSequence(); evidence.recordVerification(TEST, true); evidence.finishVerificationCall(true);

        assertFalse(CompletionGuard.blocking(new CompletionGuard().evaluate(intent, evidence)).isEmpty());
    }

    @Test void prePatchFailureDoesNotReplacePostPatchSuccess() {
        TaskIntent intent = new TaskIntent(true, true, false, true, true);
        ExecutionEvidence evidence = new ExecutionEvidence();
        evidence.nextSequence(); evidence.recordVerification(TEST, false); evidence.finishVerificationCall(false);
        evidence.nextSequence(); evidence.recordMutation("src/main/App.java", true);
        Set<String> codes = new CompletionGuard().evaluate(intent, evidence).stream()
                .map(CompletionGuard.Missing::code).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("NEEDS_TEST", "NEEDS_BUILD"), codes);
    }
}
