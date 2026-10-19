package com.example.harness.core.policy;

import org.junit.jupiter.api.Test;

import static com.example.harness.core.policy.ExecutionEvidence.VerificationKind.BUILD;
import static com.example.harness.core.policy.ExecutionEvidence.VerificationKind.TEST;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionEvidenceTest {

    @Test void aLaterNoisyFailureDoesNotEraseAnAlreadyGreenTestRun() {
        ExecutionEvidence evidence = new ExecutionEvidence();
        evidence.nextSequence();
        evidence.recordMutation("fileEdit", "src/main/java/OrderService.java", true);
        evidence.nextSequence();
        evidence.recordVerification(TEST, true);
        evidence.finishVerificationCall(true);

        // "mvn ... | grep X" exits non-zero when grep matches nothing, which says nothing
        // about the tests that just passed.
        evidence.nextSequence();
        evidence.recordVerification(TEST, false);
        evidence.finishVerificationCall(false);

        assertTrue(evidence.hasSuccessfulAfter(TEST, evidence.lastMutation() - 1));
    }

    @Test void aScratchScriptCreatedAndDeletedDoesNotInvalidateGreenEvidence() {
        ExecutionEvidence evidence = new ExecutionEvidence();
        evidence.nextSequence();
        evidence.recordMutation("fileEdit", "src/main/java/OrderService.java", true);
        int patch = evidence.lastMutation();
        evidence.nextSequence();
        evidence.recordVerification(TEST, true);
        evidence.finishVerificationCall(true);

        evidence.nextSequence();
        evidence.recordMutation("fileWrite", "scripts/verify-fix.sh", true);
        evidence.nextSequence();
        evidence.recordMutation("fileDelete", "scripts/verify-fix.sh", true);

        assertEquals(patch, evidence.lastMutation(), "自清理的临时脚本不应推高最后一次修改");
        assertEquals(1, evidence.mutations());
        assertTrue(evidence.hasSuccessfulAfter(TEST, evidence.lastMutation() - 1));
    }

    @Test void aDurableNewFileStillCountsAsAMutation() {
        ExecutionEvidence evidence = new ExecutionEvidence();
        evidence.nextSequence();
        evidence.recordMutation("fileWrite", "src/main/java/NewService.java", true);

        assertEquals(1, evidence.mutations());
        assertTrue(evidence.lastMutation() > 0);
    }

    @Test void buildAndTestEvidenceAreTrackedIndependently() {
        ExecutionEvidence evidence = new ExecutionEvidence();
        evidence.nextSequence();
        evidence.recordMutation("fileEdit", "src/main/java/App.java", true);
        evidence.nextSequence();
        evidence.recordVerification(TEST, true);
        evidence.recordVerification(BUILD, true);
        evidence.finishVerificationCall(true);

        assertEquals(java.util.EnumSet.of(TEST, BUILD), evidence.successfulKinds());
    }

    @Test void logFilesAreScratchAndNeverCountAsDelivery() {
        ExecutionEvidence evidence = new ExecutionEvidence();
        evidence.nextSequence();
        evidence.recordMutation("fileWrite", "full.log", true);

        assertEquals(0, evidence.mutations());
        assertEquals(0, evidence.lastMutation());
    }
}
