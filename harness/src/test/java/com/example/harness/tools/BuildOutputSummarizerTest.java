package com.example.harness.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildOutputSummarizerTest {

    private static final String SUREFIRE_LOG = """
            [INFO] Scanning for projects...
            [INFO] --- maven-surefire-plugin:3.2.5:test (default-test) @ bugdemo ---
            [INFO] Running com.example.demo.OrderConcurrencyTest
            [ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 -- in com.example.demo.OrderConcurrencyTest
            [ERROR] createOrder_isIdempotentUnderConcurrency -- Time elapsed: 1.2 s <<< FAILURE!
            org.opentest4j.AssertionFailedError: expected: 1 but was: 10
            [INFO] Results:
            [ERROR] Tests run: 9, Failures: 2, Errors: 0, Skipped: 0
            [INFO] BUILD FAILURE
            """;

    @Test void surefireTotalsAndAssertionDiffAreLiftedToTheTop() {
        String summary = BuildOutputSummarizer.summarize(SUREFIRE_LOG);

        assertTrue(summary.startsWith("【测试/构建结论"), summary);
        assertTrue(summary.contains("Tests run: 9, Failures: 2"), summary);
        assertTrue(summary.contains("expected: 1 but was: 10"), summary);
        assertTrue(summary.contains("BUILD FAILURE"), summary);
    }

    @Test void onlyBuildAndTestCommandsAreCondensed() {
        assertTrue(BuildOutputSummarizer.applies("mvn -q clean test"));
        assertTrue(BuildOutputSummarizer.applies("npm run build"));
        assertFalse(BuildOutputSummarizer.applies("git status --porcelain"));
    }

    @Test void unrecognizedOutputFallsBackToTheRawLog() {
        assertEquals("", BuildOutputSummarizer.summarize("hello world"));
        assertEquals("", BuildOutputSummarizer.summarize(""));
        assertEquals("", BuildOutputSummarizer.summarize(null));
    }

    @Test void aGreenRunStillReportsItsTotals() {
        String summary = BuildOutputSummarizer.summarize(
                "[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS\n");

        assertTrue(summary.contains("Tests run: 9, Failures: 0"), summary);
        assertTrue(summary.contains("BUILD SUCCESS"), summary);
    }
}
