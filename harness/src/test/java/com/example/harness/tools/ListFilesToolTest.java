package com.example.harness.tools;

import com.example.harness.core.memory.WorkingMemory;
import com.example.harness.model.Task;
import com.example.harness.sandbox.Sandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ListFilesToolTest {
    @TempDir Path temp;

    @Test void recursiveListingPrunesGeneratedAndGitDirectories() throws Exception {
        Files.createDirectories(temp.resolve(".git/objects"));
        Files.writeString(temp.resolve(".git/objects/noise"), "noise");
        Files.createDirectories(temp.resolve("target/classes"));
        Files.writeString(temp.resolve("target/classes/App.class"), "noise");
        Files.createDirectories(temp.resolve("src/main"));
        Files.writeString(temp.resolve("src/main/App.java"), "class App {}");
        Task task = new Task("t", "list", temp.toString(), null, LocalDateTime.now());

        ToolResult result = new ListFilesTool().execute(Map.of("recursive", true),
                new ToolContext(new Sandbox(temp.toString()), new WorkingMemory(task)));

        assertTrue(result.ok());
        assertTrue(result.output().contains("src/main/App.java"), result.output());
        assertFalse(result.output().contains(".git"), result.output());
        assertFalse(result.output().contains("target"), result.output());
    }
}
