package com.example.harness.tools;

import com.example.harness.core.memory.WorkingMemory;
import com.example.harness.model.Task;
import com.example.harness.sandbox.Sandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileReadToolTest {
    @TempDir Path temp;

    @Test void readsSeveralRelatedFilesInOneCall() throws Exception {
        Files.writeString(temp.resolve("Service.java"), "class Service {}");
        Files.writeString(temp.resolve("Repository.java"), "interface Repository {}");
        Task task = new Task("t", "read", temp.toString(), null, LocalDateTime.now());

        ToolResult result = new FileReadTool().execute(
                Map.of("paths", List.of("Service.java", "Repository.java")),
                new ToolContext(new Sandbox(temp.toString()), new WorkingMemory(task)));

        assertTrue(result.ok(), result.output());
        assertTrue(result.output().contains("class Service"));
        assertTrue(result.output().contains("interface Repository"));
    }

    @Test void batchSizeIsBoundedForContextQualityNotTaskTermination() {
        Task task = new Task("t", "read", temp.toString(), null, LocalDateTime.now());
        List<String> paths = java.util.stream.IntStream.range(0, 9)
                .mapToObj(i -> i + ".txt").toList();

        ToolResult result = new FileReadTool().execute(Map.of("paths", paths),
                new ToolContext(new Sandbox(temp.toString()), new WorkingMemory(task)));

        assertFalse(result.ok());
        assertTrue(result.output().contains("最多批量读取 8 个文件"));
    }
}
