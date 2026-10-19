package com.example.harness.core;

import com.example.harness.config.HarnessProperties;
import com.example.harness.sandbox.Sandbox;
import com.example.harness.tools.ShellEnvironmentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceSnapshotServiceTest {
    @TempDir Path temp;

    @Test void capturesManifestAndSkipsHeavyDirectories() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Files.createDirectories(temp.resolve("src/main"));
        Files.writeString(temp.resolve("src/main/App.java"), "class App {}");
        Files.createDirectories(temp.resolve("target/classes"));
        Files.writeString(temp.resolve("target/classes/Hidden.java"), "class Hidden {}");
        ShellEnvironmentResolver shell = org.mockito.Mockito.mock(ShellEnvironmentResolver.class);
        org.mockito.Mockito.when(shell.promptSummary()).thenReturn("bash（测试）");

        var snapshot = new WorkspaceSnapshotService(shell).inspect(new Sandbox(temp.toString()));
        assertTrue(snapshot.manifests().contains("pom.xml"));
        assertEquals(1, snapshot.languages().get("Java"));
        assertTrue(snapshot.entries().stream().noneMatch(path -> path.startsWith("target")));
        assertTrue(snapshot.toPrompt().contains("shell=bash（测试）"));
    }
}
