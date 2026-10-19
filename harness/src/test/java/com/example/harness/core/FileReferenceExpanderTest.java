package com.example.harness.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReferenceExpanderTest {
    Path project;

    @BeforeEach void setup() throws Exception {
        project = Files.createTempDirectory(Path.of("target"), "file-ref-");
    }

    @AfterEach void cleanup() throws Exception {
        for (String name : new String[] {"first.js", "second.js", "binary.dat", "long.txt"}) {
            Files.deleteIfExists(project.resolve(name));
        }
        Files.deleteIfExists(project);
    }

    @Test void expandsContentsAtEachMentionWithoutReorderingInstructions() throws Exception {
        Files.writeString(project.resolve("first.js"), "const first = 1;", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("second.js"), "const second = 2;", StandardCharsets.UTF_8);

        String expanded = FileReferenceExpander.expand(
                "@{first.js} 先检查这里，再用 @{second.js} 对照。", project);

        int first = expanded.indexOf("const first = 1;");
        int instruction = expanded.indexOf("先检查这里，再用");
        int second = expanded.indexOf("const second = 2;");
        assertTrue(first >= 0 && first < instruction && instruction < second, expanded);
        assertFalse(expanded.contains("[引用文件]"));
    }

    @Test void rejectsTraversalAndDoesNotInjectBinaryFiles() throws Exception {
        Files.write(project.resolve("binary.dat"), new byte[] {1, 0, 2});
        String expanded = FileReferenceExpander.expand("@{../secret.txt} @{binary.dat}", project);
        assertTrue(expanded.contains("文件不存在或不在项目目录内"), expanded);
        assertTrue(expanded.contains("二进制文件未注入"));
    }

    @Test void marksTruncatedContentClearly() throws Exception {
        Files.writeString(project.resolve("long.txt"), "a".repeat(60_000), StandardCharsets.UTF_8);
        String expanded = FileReferenceExpander.expand("查看 @{long.txt}", project);
        assertTrue(expanded.contains("文件内容已截断"), expanded.substring(0, Math.min(300, expanded.length())));
        assertFalse(expanded.contains("a".repeat(60_000)));
    }
}
