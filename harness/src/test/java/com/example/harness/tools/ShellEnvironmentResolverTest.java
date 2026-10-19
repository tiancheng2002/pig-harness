package com.example.harness.tools;

import com.example.harness.config.HarnessProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShellEnvironmentResolverTest {
    @Test void derivesGitBashFromGitCmdDirectory() {
        org.junit.jupiter.api.Assumptions.assumeTrue(ShellEnvironmentResolver.isWindows());
        List<Path> candidates = ShellEnvironmentResolver.deriveGitBashCandidates(
                Path.of("D:\\Git\\cmd\\git.exe"));
        assertEquals(Path.of("D:\\Git\\bin\\bash.exe"), candidates.getFirst());
        assertEquals(Path.of("D:\\Git\\usr\\bin\\bash.exe"), candidates.get(1));
    }


    @Test void bashIsNonInteractiveAndEnablesPipefail() {
        var env = new ShellEnvironmentResolver.Environment(
                ShellEnvironmentResolver.Dialect.BASH, "bash.exe");
        assertEquals(List.of("bash.exe", "--noprofile", "--norc", "-o", "pipefail", "-c", "false | true"),
                env.commandLine("false | true"));
    }

    @Test void fallsBackToPowerShellThenCmd() {
        HarnessProperties props = new HarnessProperties();
        ShellEnvironmentResolver powershell = new StubResolver(props, null, "powershell.exe");
        assertEquals(ShellEnvironmentResolver.Dialect.POWERSHELL, powershell.resolve().dialect());
        assertTrue(powershell.resolve().commandLine("exit 7").getLast().contains("$harnessExit"));

        ShellEnvironmentResolver cmd = new StubResolver(props, null, null);
        assertEquals(ShellEnvironmentResolver.Dialect.CMD, cmd.resolve().dialect());
        assertEquals(List.of("cmd.exe", "/d", "/s", "/c", "exit /b 7"),
                cmd.resolve().commandLine("exit /b 7"));
    }

    private static final class StubResolver extends ShellEnvironmentResolver {
        private final String bash;
        private final String powershell;
        StubResolver(HarnessProperties props, String bash, String powershell) {
            super(props); this.bash = bash; this.powershell = powershell;
        }
        @Override protected boolean windows() { return true; }
        @Override protected String firstWorkingBash(List<String> ignored) { return bash; }
        @Override protected String firstWorkingPowerShell() { return powershell; }
    }
}
