package com.example.harness.tools;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.memory.WorkingMemory;
import com.example.harness.model.Task;
import com.example.harness.sandbox.Sandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShellToolTest {
    @TempDir Path temp;

    @Test void returnsTheRealExternalExitCodeAndDeclaresDialect() {
        HarnessProperties props = new HarnessProperties();
        ShellEnvironmentResolver resolver = new ShellEnvironmentResolver(props);
        ShellTool tool = new ShellTool(props, resolver);
        Task task = new Task("t", "test", temp.toString(), null, LocalDateTime.now());
        ToolContext context = new ToolContext(new Sandbox(temp.toString()), new WorkingMemory(task));
        String command = resolver.resolve().dialect() == ShellEnvironmentResolver.Dialect.CMD
                ? "exit /b 7" : "exit 7";

        ToolResult result = tool.execute(Map.of("command", command), context);
        assertFalse(result.ok());
        assertTrue(result.output().contains("退出码 7"), result.output());
        assertTrue(tool.description().contains(resolver.resolve().label()));
    }

    @Test void testCommandOutputArrivesWithItsVerdictPinnedToTheTop() {
        HarnessProperties props = new HarnessProperties();
        ShellEnvironmentResolver resolver = new ShellEnvironmentResolver(props);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                resolver.resolve().dialect() == ShellEnvironmentResolver.Dialect.BASH);
        ShellTool tool = new ShellTool(props, resolver);
        Task task = new Task("t", "test", temp.toString(), null, LocalDateTime.now());

        // The command text is what marks this as a test run; the output stands in for a real log.
        ToolResult result = tool.execute(Map.of("command",
                        "echo 'mvn test'; echo 'Tests run: 9, Failures: 0, Errors: 0'; echo 'BUILD SUCCESS'"),
                new ToolContext(new Sandbox(temp.toString()), new WorkingMemory(task)));

        assertTrue(result.ok(), result.output());
        assertTrue(result.output().startsWith("【测试/构建结论"), result.output());
        assertTrue(result.output().contains("Tests run: 9, Failures: 0"), result.output());
    }

    @Test void bashPipelineCannotHideTheFirstFailure() {
        HarnessProperties props = new HarnessProperties();
        ShellEnvironmentResolver resolver = new ShellEnvironmentResolver(props);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                resolver.resolve().dialect() == ShellEnvironmentResolver.Dialect.BASH);
        ShellTool tool = new ShellTool(props, resolver);
        Task task = new Task("t", "test", temp.toString(), null, LocalDateTime.now());
        ToolResult result = tool.execute(Map.of("command", "false | true"),
                new ToolContext(new Sandbox(temp.toString()), new WorkingMemory(task)));
        assertFalse(result.ok());
        assertTrue(result.output().contains("退出码 1"), result.output());
    }

    @Test void malformedBashIsRejectedBeforeExecution() {
        HarnessProperties props = new HarnessProperties();
        ShellEnvironmentResolver resolver = new ShellEnvironmentResolver(props);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                resolver.resolve().dialect() == ShellEnvironmentResolver.Dialect.BASH);
        ShellTool tool = new ShellTool(props, resolver);
        Task task = new Task("t", "test", temp.toString(), null, LocalDateTime.now());

        ToolResult result = tool.execute(Map.of("command", "if true; then echo broken"),
                new ToolContext(new Sandbox(temp.toString()), new WorkingMemory(task)));

        assertFalse(result.ok());
        assertTrue(result.output().contains("静态语法检查失败"), result.output());
    }

    @Test void unmanagedBackgroundProcessIsRejected() {
        HarnessProperties props = new HarnessProperties();
        ShellTool tool = new ShellTool(props, new ShellEnvironmentResolver(props));
        Task task = new Task("t", "test", temp.toString(), null, LocalDateTime.now());

        ToolResult result = tool.execute(Map.of("command", "nohup java -jar app.jar &"),
                new ToolContext(new Sandbox(temp.toString()), new WorkingMemory(task)));

        assertFalse(result.ok());
        assertTrue(result.output().contains("无托管后台进程"), result.output());
    }
}
