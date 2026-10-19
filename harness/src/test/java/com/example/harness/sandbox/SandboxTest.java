package com.example.harness.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 沙箱校验（F10）单测。 */
class SandboxTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveRelativeInsideRoot() {
        Sandbox sb = new Sandbox(tempDir.toString());
        Path p = sb.resolve("login.html");
        assertEquals(tempDir.resolve("login.html").toAbsolutePath().normalize(), p);
    }

    @Test
    void resolveSubDirNormalizes() {
        Sandbox sb = new Sandbox(tempDir.toString());
        Path p = sb.resolve("a/./b/../c.txt");
        assertEquals(tempDir.resolve("a/c.txt").toAbsolutePath().normalize(), p);
    }

    @Test
    void rejectsEscapeWithDotDot() {
        Sandbox sb = new Sandbox(tempDir.toString());
        assertThrows(SecurityException.class, () -> sb.resolve("../../outside.txt"));
        assertThrows(SecurityException.class, () -> sb.resolve("sub/../../outside.txt"));
    }

    @Test
    void rejectsAbsoluteOutsideRoot() {
        Sandbox sb = new Sandbox(tempDir.toString());
        assertThrows(SecurityException.class, () -> sb.resolve("C:/Windows/system32/evil.txt"));
        assertThrows(SecurityException.class, () -> sb.resolve("/etc/passwd"));
    }

    @Test
    void rejectsNullPath() {
        Sandbox sb = new Sandbox(tempDir.toString());
        assertThrows(SecurityException.class, () -> sb.resolve(null));
        assertThrows(SecurityException.class, () -> sb.resolve("  "));
    }

    @Test
    void rootDirectoryCreated() {
        Path dir = tempDir.resolve("fresh-root");
        Sandbox sb = new Sandbox(dir.toString());
        assertTrue(java.nio.file.Files.isDirectory(sb.root()));
    }
}
