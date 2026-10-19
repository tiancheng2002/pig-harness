package com.example.harness.core.policy;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.example.harness.core.policy.ExecutionEvidence.VerificationKind.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VerificationClassifierTest {
    @Test void classifiesTargetedMavenTestAndBuild() {
        // Maven cannot run a test without compiling it, so a green test run is build evidence too.
        assertEquals(Set.of(TEST, BUILD), VerificationClassifier.classify("mvn -q -Dtest=PagingTest test"));
        assertEquals(Set.of(TEST, BUILD), VerificationClassifier.classify("mvn clean test"));
        assertEquals(Set.of(TEST), VerificationClassifier.classify("npm test"));
        assertEquals(Set.of(BUILD), VerificationClassifier.classify("mvn -q -DskipTests package"));
        assertEquals(Set.of(TEST, BUILD), VerificationClassifier.classify("mvn -q verify"));
    }

    @Test void arbitraryShellIsNotPretendedToBeVerification() {
        assertEquals(Set.of(), VerificationClassifier.classify("ls -la && git status"));
        assertEquals(Set.of(REPRO), VerificationClassifier.classify("./scripts/repro-pagination.sh"));
    }
}
