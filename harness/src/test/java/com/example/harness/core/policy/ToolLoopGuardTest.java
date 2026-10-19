package com.example.harness.core.policy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolLoopGuardTest {
    @Test void blocksOnlyIdenticalNoProgressRepeat() {
        ToolLoopGuard guard = new ToolLoopGuard(1);
        Map<String,Object> args = Map.of("path", "README.md", "offset", 1);

        var first = guard.before("fileRead", args);
        assertFalse(first.blocked());
        assertTrue(guard.record(first.fingerprint(), true, "same content"));

        var retry = guard.before("fileRead", args);
        assertFalse(retry.blocked());
        assertFalse(guard.record(retry.fingerprint(), true, "same content"));

        assertTrue(guard.before("fileRead", args).blocked());
        assertFalse(guard.before("fileRead", Map.of("path", "OTHER.md")).blocked());
    }

    @Test void aNewOutcomeResetsLocalLoopState() {
        ToolLoopGuard guard = new ToolLoopGuard(1);
        Map<String,Object> args = Map.of("command", "mvn test");
        var first = guard.before("bash", args);
        guard.record(first.fingerprint(), false, "Tests run: 1, Failures: 1");
        var retry = guard.before("bash", args);
        guard.record(retry.fingerprint(), true, "Tests run: 1, Failures: 0");

        assertFalse(guard.before("bash", args).blocked(),
                "red -> green is progress even when the command is identical");
    }

    @Test void argumentOrderDoesNotBypassFingerprinting() {
        ToolLoopGuard guard = new ToolLoopGuard(1);
        Map<String,Object> firstArgs = new LinkedHashMap<>();
        firstArgs.put("path", "a.md");
        firstArgs.put("offset", 1);
        Map<String,Object> reordered = new LinkedHashMap<>();
        reordered.put("offset", 1);
        reordered.put("path", "a.md");

        var first = guard.before("fileRead", firstArgs);
        guard.record(first.fingerprint(), true, "x");
        var retry = guard.before("fileRead", reordered);
        guard.record(retry.fingerprint(), true, "x");

        assertTrue(guard.before("fileRead", reordered).blocked());
    }

    @Test void changingArgumentsDoesNotPretendTheSameFailureCategoryIsProgress() {
        ToolLoopGuard guard = new ToolLoopGuard(1);
        var first = guard.before("bash", Map.of("command", "broken one"));
        assertTrue(guard.record(first.fingerprint(), false,
                "syntax error: unexpected end of file"));
        long version = guard.progressVersion();
        var changed = guard.before("bash", Map.of("command", "broken two"));

        assertFalse(guard.record(changed.fingerprint(), false,
                "syntax error: unexpected end of file"));
        assertEquals(version, guard.progressVersion());
    }
}
