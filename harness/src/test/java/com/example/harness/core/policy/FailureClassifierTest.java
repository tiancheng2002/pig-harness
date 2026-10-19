package com.example.harness.core.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailureClassifierTest {
    @Test void distinguishesShellSyntaxFromEnvironmentAndPortConflicts() {
        var syntax = FailureClassifier.classify("bash",
                "bash: -c: line 5: syntax error: unexpected end of file");
        assertEquals("shell:incomplete-syntax", syntax.signature());
        assertTrue(syntax.advice().contains("不是系统环境"));

        var offline = FailureClassifier.classify("bash",
                "Cannot access central in offline mode and artifact has not been downloaded");
        assertEquals("maven:offline-cache-miss", offline.signature());

        var port = FailureClassifier.classify("bash", "Web server failed: Port 8080 was already in use");
        assertEquals("runtime:port-in-use", port.signature());
    }
}
