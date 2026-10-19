package com.example.harness.core;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.hooks.HookService;
import com.example.harness.core.memory.AgentProfileStore;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.memory.EpisodicStore;
import com.example.harness.core.memory.WorkingMemory;
import com.example.harness.core.question.QuestionGate;
import com.example.harness.core.task.*;
import com.example.harness.llm.*;
import com.example.harness.model.Task;
import com.example.harness.model.Trace;
import com.example.harness.sandbox.Sandbox;
import com.example.harness.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLoopPolicyTest {
    @TempDir Path temp;

    @Test void rejectsEarlyConclusionThenStopsRetryingOnceTheModelStopsActing() {
        HarnessProperties props = properties();
        FakeTool edit = new FakeTool("fileEdit", ToolResult.success("edited"));
        AgentLoop loop = loop(props, List.of(edit), mock(QuestionGate.class));
        ScriptedClient client = new ScriptedClient(
                text("[{\"step\":\"修改分页实现\",\"tool\":\"fileEdit\"},{\"step\":\"运行测试\",\"tool\":\"bash\"}]"),
                text("已经完成"), text("已经完成"), text("已经完成"));
        List<Map<String,Object>> events = new ArrayList<>();
        Task task = task("修复分页问题：先构造失败测试，最小修复，运行目标测试和构建", null);

        ExecutionResult result = execute(loop, task, client, events);

        assertEquals(RunStatus.PARTIAL, result.status());
        assertEquals(0, edit.calls.get());
        // A retry that produced no tool call at all will not produce one next time either:
        // the second retry is skipped so the remaining budget is not spent re-asserting.
        assertEquals(1, events.stream().filter(e -> "reflect".equals(e.get("type"))
                && String.valueOf(e.get("reason")).contains("交付证据不完整")).count());
        assertTrue(events.stream().anyMatch(e -> "guard".equals(e.get("type"))
                && Boolean.TRUE.equals(e.get("terminal"))));
        assertFalse(events.stream().anyMatch(e -> "plan_step".equals(e.get("type"))
                && "done".equals(e.get("status"))));
    }

    @Test void taskMismatchAsksOnceAfterTwoReadsAndTimeoutEndsPartialWithoutWrites() {
        HarnessProperties props = properties();
        FakeTool read = new FakeTool("fileRead", ToolResult.success("backend paging service only"));
        QuestionGate questions = mock(QuestionGate.class);
        when(questions.await(anyString(), anyString(), anyLong())).thenReturn(null);
        AgentLoop loop = loop(props, List.of(read), questions);
        ScriptedClient client = new ScriptedClient(
                text("[{\"step\":\"检查仓库\",\"tool\":\"fileRead\"},{\"step\":\"核对相关实现\",\"tool\":\"fileRead\"}]"),
                call("r1", "fileRead", "{\"path\":\"pom.xml\"}"),
                call("r2", "fileRead", "{\"path\":\"src/main/App.java\"}"),
                call("q1", "askUser", "{\"question\":\"仓库只有后端分页代码，是否换成分页缺陷？\"}"));
        List<Map<String,Object>> events = new ArrayList<>();
        Task task = task("修复用户快速切换搜索条件时显示旧结果的问题", "conv-1");

        ExecutionResult result = execute(loop, task, client, events);

        assertEquals(RunStatus.PARTIAL, result.status());
        assertEquals(2, read.calls.get());
        assertEquals(1, events.stream().filter(e -> "question_request".equals(e.get("type"))).count());
        assertTrue(events.stream().anyMatch(e -> "guard".equals(e.get("type"))
                && "NEEDS_INPUT".equals(e.get("code"))));
    }

    @Test void explorationCheckpointDoesNotBlockFurtherReads() {
        HarnessProperties props = properties();
        FakeTool read = new FakeTool("fileRead", ToolResult.success("ok"));
        AgentLoop loop = loop(props, List.of(read), mock(QuestionGate.class));
        List<ChatResult> script = new ArrayList<>();
        script.add(text("[{\"step\":\"调查\",\"tool\":\"fileRead\"}]"));
        for (int i = 1; i <= 13; i++) script.add(call("r" + i, "fileRead", "{\"path\":\"f" + i + ".txt\"}"));
        script.add(text("调查结束"));
        List<Map<String,Object>> events = new ArrayList<>();

        ExecutionResult result = execute(loop, task("仅分析这个项目，不修改代码", null),
                new ScriptedClient(script.toArray(ChatResult[]::new)), events);

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(13, read.calls.get());
        assertTrue(events.stream().anyMatch(e -> "guard".equals(e.get("type"))
                && "EXPLORATION_DECISION".equals(e.get("code"))));
        assertFalse(events.stream().anyMatch(e -> "EXPLORATION_LIMIT".equals(e.get("code"))));
    }

    @Test void consecutiveFailuresTriggerRecoveryButDoNotStopTheTask() {
        HarnessProperties props = properties();
        SequenceTool read = new SequenceTool("fileRead",
                ToolResult.error("missing alpha"), ToolResult.error("missing beta"),
                ToolResult.error("missing gamma"), ToolResult.error("missing delta"));
        AgentLoop loop = loop(props, List.of(read), mock(QuestionGate.class));
        ScriptedClient client = new ScriptedClient(
                text("[{\"step\":\"读取\",\"tool\":\"fileRead\"}]"),
                call("r1", "fileRead", "{\"path\":\"1\"}"), call("r2", "fileRead", "{\"path\":\"2\"}"),
                call("r3", "fileRead", "{\"path\":\"3\"}"), call("r4", "fileRead", "{\"path\":\"4\"}"),
                text("诊断完成，四个路径均不存在。"));
        List<Map<String,Object>> events = new ArrayList<>();

        ExecutionResult result = execute(loop, task("仅分析问题，不修改代码", null), client, events);

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(4, read.calls.get());
        assertTrue(events.stream().anyMatch(e -> "guard".equals(e.get("type"))
                && "REPEATED_FAILURES".equals(e.get("code"))));
        assertFalse(events.stream().anyMatch(e -> "TOOL_FAILURE_LIMIT".equals(e.get("code"))));
    }

    @Test void boundedBugFixDeliveryReadyIsAdvisoryAndDoesNotStopRemainingWork() {
        HarnessProperties props = properties();
        SequenceTool bash = new SequenceTool("bash",
                ToolResult.error("Tests run: 1, Failures: 1"),
                ToolResult.success("Tests run: 1, Failures: 0\nBUILD SUCCESS"),
                ToolResult.success("remaining verification complete"));
        FakeTool edit = new FakeTool("fileEdit", ToolResult.success("edited"));
        AgentLoop loop = loop(props, List.of(bash, edit), mock(QuestionGate.class));
        ScriptedClient client = new ScriptedClient(
                text("[{\"step\":\"复现\",\"tool\":\"bash\"},{\"step\":\"修复\",\"tool\":\"fileEdit\"},{\"step\":\"测试\",\"tool\":\"bash\"}]"),
                call("t1", "bash", "{\"command\":\"mvn -q -Dtest=PagingTest test\"}"),
                call("e1", "fileEdit", "{\"path\":\"src/main/UserService.java\"}"),
                new ChatResult("", List.of(
                        new LlmToolCall("t2", "bash", "{\"command\":\"mvn -q -Dtest=PagingTest test\"}"),
                        new LlmToolCall("extra", "bash", "{\"command\":\"mvn spring-boot:run\"}"))),
                text("分页边界已修复，失败测试已转绿，剩余验证已完成。"));
        List<Map<String,Object>> events = new ArrayList<>();

        ExecutionResult result = execute(loop, task("请修复分页边界错误", null), client, events);

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(3, bash.calls.get(), "delivery-ready 只能提醒，不能截断剩余工作");
        assertEquals(1, edit.calls.get());
        assertTrue(events.stream().anyMatch(e -> "guard".equals(e.get("type"))
                && "DELIVERY_READY".equals(e.get("code"))));
    }

    @Test void repeatedFailureKindIsDetectedEvenWhenASuccessOccurredBetweenFailures() {
        HarnessProperties props = properties();
        SequenceTool shell = new SequenceTool("bash",
                ToolResult.error("syntax error: unexpected end of file"),
                ToolResult.success("git status clean"),
                ToolResult.error("syntax error: unexpected end of file"));
        AgentLoop loop = loop(props, List.of(shell), mock(QuestionGate.class));
        ScriptedClient client = new ScriptedClient(
                text("[{\"step\":\"诊断\",\"tool\":\"bash\"}]"),
                call("b1", "bash", "{\"command\":\"broken one\"}"),
                call("b2", "bash", "{\"command\":\"git status\"}"),
                call("b3", "bash", "{\"command\":\"broken two\"}"),
                text("两次命令语法错误，已停止原样重试。"));
        List<Map<String,Object>> events = new ArrayList<>();

        ExecutionResult result = execute(loop, task("仅诊断当前环境，不修改文件", null), client, events);

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(3, shell.calls.get());
        assertTrue(events.stream().anyMatch(e -> "guard".equals(e.get("type"))
                && "REPEATED_FAILURE_KIND".equals(e.get("code"))));
    }

    @Test void identicalNoProgressCallIsLocallyBlockedButAlternativeWorkContinues() {
        HarnessProperties props = properties();
        SequenceTool read = new SequenceTool("fileRead",
                ToolResult.success("same content"), ToolResult.success("same content"),
                ToolResult.success("different file content"));
        AgentLoop loop = loop(props, List.of(read), mock(QuestionGate.class));
        ScriptedClient client = new ScriptedClient(
                text("[{\"step\":\"分析需求\",\"tool\":\"fileRead\"}]"),
                call("r1", "fileRead", "{\"path\":\"requirements.md\"}"),
                call("r2", "fileRead", "{\"path\":\"requirements.md\"}"),
                call("r3", "fileRead", "{\"path\":\"requirements.md\"}"),
                call("r4", "fileRead", "{\"path\":\"constraints.md\"}"),
                text("需求分析完成。"));
        List<Map<String,Object>> events = new ArrayList<>();

        ExecutionResult result = execute(loop, task("分析现有需求文档并给出设计方案", null), client, events);

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(3, read.calls.get(), "第三次无进展重复应跳过，后续不同读取仍执行");
        assertTrue(events.stream().anyMatch(e -> "TOOL_LOOP_BLOCKED".equals(e.get("code"))));
    }

    @Test void mainTaskContinuesBeyondConfiguredRoundCheckpoint() {
        HarnessProperties props = properties();
        props.setMaxSteps(2);
        SequenceTool read = new SequenceTool("fileRead",
                ToolResult.success("a"), ToolResult.success("b"), ToolResult.success("c"));
        AgentLoop loop = loop(props, List.of(read), mock(QuestionGate.class));
        ScriptedClient client = new ScriptedClient(
                text("[{\"step\":\"分析\",\"tool\":\"fileRead\"}]"),
                call("r1", "fileRead", "{\"path\":\"a.md\"}"),
                call("r2", "fileRead", "{\"path\":\"b.md\"}"),
                call("r3", "fileRead", "{\"path\":\"c.md\"}"),
                text("长需求分析完成。"));
        List<Map<String,Object>> events = new ArrayList<>();

        ExecutionResult result = execute(loop, task("分析多个需求文件并输出完整设计", null), client, events);

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(3, read.calls.get());
        assertTrue(events.stream().anyMatch(e -> "LONG_RUN_CHECKPOINT".equals(e.get("code"))));
        assertFalse(result.stepLimitReached());
    }

    @Test void contentGenerationCanFinishWithoutAnyToolBudgetConcept() {
        HarnessProperties props = properties();
        AgentLoop loop = loop(props, List.of(), mock(QuestionGate.class));
        ScriptedClient client = new ScriptedClient(
                text("[{\"step\":\"撰写短视频脚本\",\"tool\":null}]"),
                text("镜头一：开场。镜头二：展示冲突。镜头三：给出结论。"));
        List<Map<String,Object>> events = new ArrayList<>();

        ExecutionResult result = execute(loop, task("生成一份三镜头短视频脚本文案", null), client, events);

        assertEquals(RunStatus.SUCCESS, result.status());
        assertTrue(events.stream().noneMatch(e -> "tool_call".equals(e.get("type"))));
    }

    @Test void mainTaskCanExecuteMoreThanTwentyToolsWhenWorkRequiresIt() {
        HarnessProperties props = properties();
        FakeTool read = new FakeTool("fileRead", ToolResult.success("unique input"));
        AgentLoop loop = loop(props, List.of(read), mock(QuestionGate.class));
        List<ChatResult> script = new ArrayList<>();
        script.add(text("[{\"step\":\"读取全部需求资料\",\"tool\":\"fileRead\"}]"));
        for (int i = 1; i <= 21; i++) {
            script.add(call("r" + i, "fileRead", "{\"path\":\"requirements/" + i + ".md\"}"));
        }
        script.add(text("21 份需求资料已完成综合分析。"));
        List<Map<String,Object>> events = new ArrayList<>();

        ExecutionResult result = execute(loop, task("分析需求目录中的全部材料并给出完整设计", null),
                new ScriptedClient(script.toArray(ChatResult[]::new)), events);

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(21, read.calls.get());
        assertFalse(events.stream().anyMatch(e -> "TOOL_BUDGET_EXHAUSTED".equals(e.get("code"))));
    }

    @Test void boundedFixSkipsThePlannerRoundTrip() {
        HarnessProperties props = new HarnessProperties();
        props.setMaxSteps(30);
        props.getTaskRuntime().setEnabled(false);
        FakeTool edit = new FakeTool("fileEdit", ToolResult.success("edited"));
        SequenceTool bash = new SequenceTool("bash",
                ToolResult.success("Tests run: 1, Failures: 0"));
        AgentLoop loop = loop(props, List.of(edit, bash), mock(QuestionGate.class));
        // No planner reply in the script: a bounded fix must not spend a round trip on planning.
        ScriptedClient client = new ScriptedClient(
                call("e1", "fileEdit", "{\"path\":\"src/main/OrderService.java\"}"),
                call("t1", "bash", "{\"command\":\"mvn -q test\"}"),
                text("并发重复写入已修复，测试通过。"));
        List<Map<String,Object>> events = new ArrayList<>();

        ExecutionResult result = execute(loop, task("修复并发请求导致重复写入的缺陷", null), client, events);

        assertEquals(RunStatus.SUCCESS, result.status());
        assertTrue(events.stream().noneMatch(e -> "plan".equals(e.get("type"))));
        assertEquals(1, edit.calls.get());
    }

    @Test void aRuntimePolicyNudgeIsInjectedAtMostOncePerCode() {
        HarnessProperties props = properties();
        FakeTool read = new FakeTool("fileRead", ToolResult.success("ok"));
        AgentLoop loop = loop(props, List.of(read), mock(QuestionGate.class));
        List<ChatResult> script = new ArrayList<>();
        script.add(text("[{\"step\":\"调查\",\"tool\":\"fileRead\"}]"));
        for (int i = 1; i <= 26; i++) script.add(call("r" + i, "fileRead", "{\"path\":\"f" + i + ".txt\"}"));
        script.add(text("调查结束"));
        List<Map<String,Object>> events = new ArrayList<>();

        execute(loop, task("仅分析这个项目，不修改代码", null),
                new ScriptedClient(script.toArray(ChatResult[]::new)), events);

        // The checkpoint fires more than once as an event, but only the first one is fed back
        // into the conversation; repeating it would just dilute the user instruction.
        assertTrue(events.stream().filter(e -> "guard".equals(e.get("type"))
                && "EXPLORATION_DECISION".equals(e.get("code"))).count() >= 2);
    }

    @Test void aLongProductiveFeatureRunIsNeverTruncated() {
        // Regression: a full-stack feature used to hit the same flat ceiling as a spinning agent
        // and had fileEdit withdrawn mid-implementation, leaving the frontend half written.
        HarnessProperties props = properties();
        props.getAgentPolicy().setToolCallSoftBudget(5);
        FakeTool edit = new FakeTool("fileEdit", ToolResult.success("edited"));
        AgentLoop loop = loop(props, List.of(edit), mock(QuestionGate.class));
        List<ChatResult> script = new ArrayList<>();
        script.add(text("[{\"step\":\"实现标签功能\",\"tool\":\"fileEdit\"}]"));
        for (int i = 1; i <= 30; i++) {
            script.add(call("e" + i, "fileEdit", "{\"path\":\"web/src/views/View" + i + ".vue\"}"));
        }
        script.add(text("标签功能前后端已实现完毕。"));
        List<Map<String,Object>> events = new ArrayList<>();

        ExecutionResult result = execute(loop,
                task("新增任务标签功能，从数据库到前端交互的全栈实现", null),
                new ScriptedClient(script.toArray(ChatResult[]::new)), events);

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(30, edit.calls.get(), "每次调用都有新进展时不得收回写入类工具");
        assertTrue(events.stream().anyMatch(e -> "guard".equals(e.get("type"))
                && "BUDGET_SOFT".equals(e.get("code"))), "软预算仍应提醒收敛");
        assertFalse(events.stream().anyMatch(e -> "guard".equals(e.get("type"))
                && "BUDGET_HARD".equals(e.get("code"))));
    }

    @Test void spinningWithoutProgressWithdrawsWritingToolsAndEndsPartial() {
        HarnessProperties props = properties();
        props.getAgentPolicy().setUnproductiveCallLimit(3);
        FakeTool read = new FakeTool("fileRead", ToolResult.success("same content every time"));
        FakeTool edit = new FakeTool("fileEdit", ToolResult.success("edited"));
        AgentLoop loop = loop(props, List.of(read, edit), mock(QuestionGate.class));
        List<ChatResult> script = new ArrayList<>();
        script.add(text("[{\"step\":\"调查\",\"tool\":\"fileRead\"}]"));
        // The same call over and over: no new fact, no mutation, no verification state.
        for (int i = 1; i <= 4; i++) script.add(call("r" + i, "fileRead", "{\"path\":\"same.txt\"}"));
        // The prompt still describes fileEdit, so a model can try to write after the stop.
        script.add(call("e1", "fileEdit", "{\"path\":\"src/main/App.java\"}"));
        script.add(text("已基于现有证据收尾。"));
        List<Map<String,Object>> events = new ArrayList<>();

        ExecutionResult result = execute(loop, task("仅分析这个项目并输出报告，不修改代码", null),
                new ScriptedClient(script.toArray(ChatResult[]::new)), events);

        assertEquals(RunStatus.PARTIAL, result.status());
        assertEquals("BUDGET_EXCEEDED", result.failureCode());
        assertEquals(0, edit.calls.get(), "空转判定后写入类工具必须被拒绝执行");
        assertTrue(events.stream().anyMatch(e -> "guard".equals(e.get("type"))
                && "BUDGET_HARD".equals(e.get("code"))
                && Boolean.TRUE.equals(e.get("terminal"))));
    }

    private HarnessProperties properties() {
        HarnessProperties props = new HarnessProperties();
        props.setMaxSteps(30);
        props.getTaskRuntime().setEnabled(false);
        // These scripts start with a planner response, so they exercise the planned path.
        // Skipping the planner for bounded tasks is covered by boundedFixSkipsPlannerRoundTrip.
        props.getPlanner().setMode("always");
        return props;
    }

    private AgentLoop loop(HarnessProperties props, List<Tool> tools, QuestionGate questions) {
        ToolCallTracker toolTracker = new ToolCallTracker(mock(ToolCallStore.class), props);
        ToolRegistry registry = new ToolRegistry(tools, toolTracker);
        EpisodicStore episodes = mock(EpisodicStore.class);
        when(episodes.save(any(Trace.class))).thenReturn(1L);
        HookService hooks = mock(HookService.class);
        when(hooks.load(any(Path.class))).thenReturn(List.of());
        LlmCallTracker llmTracker = mock(LlmCallTracker.class);
        when(llmTracker.tracked(any(ChatClient.class), any(), anyString(), nullable(String.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        AgentProfileStore profiles = mock(AgentProfileStore.class);
        when(profiles.listEnabled()).thenReturn(List.of());
        ModelRegistry models = mock(ModelRegistry.class);
        when(models.contextLengthOf(any())).thenReturn(131_072);
        ShellEnvironmentResolver shell = mock(ShellEnvironmentResolver.class);
        when(shell.promptSummary()).thenReturn("bash（测试）");
        return new AgentLoop(registry, new Planner(props), episodes, mock(ConversationStore.class),
                new ApprovalGate(), questions, new ObjectMapper(), props, models, profiles,
                mock(com.example.harness.core.codeindex.CodeIndexService.class), hooks,
                mock(TaskRunService.class), llmTracker, new WorkspaceSnapshotService(shell));
    }

    private ExecutionResult execute(AgentLoop loop, Task task, ChatClient client,
                                    List<Map<String,Object>> events) {
        Sandbox sandbox = new Sandbox(temp.toString());
        WorkingMemory memory = new WorkingMemory(task);
        return loop.execute(task, new SseEmitter(), client, List.of(), memory, sandbox,
                new ToolContext(sandbox, memory), List.of(), events);
    }

    private Task task(String prompt, String conversationId) {
        return new Task(UUID.randomUUID().toString(), prompt, temp.toString(), conversationId,
                LocalDateTime.now());
    }

    private static ChatResult text(String value) { return new ChatResult(value, List.of()); }
    private static ChatResult call(String id, String name, String args) {
        return new ChatResult("", List.of(new LlmToolCall(id, name, args)));
    }

    private static final class ScriptedClient implements ChatClient {
        private final Deque<ChatResult> replies;
        ScriptedClient(ChatResult... replies) { this.replies = new ArrayDeque<>(List.of(replies)); }
        @Override public ChatResult chat(List<LlmMessage> messages, List<LlmToolSpec> tools,
                                         StreamListener listener) {
            if (replies.isEmpty()) throw new AssertionError("script exhausted");
            return replies.removeFirst();
        }
        @Override public String modelName() { return "scripted"; }
    }

    private static final class FakeTool implements Tool {
        private final String name;
        private final ToolResult result;
        private final AtomicInteger calls = new AtomicInteger();
        FakeTool(String name, ToolResult result) { this.name = name; this.result = result; }
        @Override public String name() { return name; }
        @Override public String description() { return "test tool"; }
        @Override public String parametersJson() { return "{\"type\":\"object\"}"; }
        @Override public ToolResult execute(Map<String,Object> args, ToolContext ctx) {
            calls.incrementAndGet(); return result;
        }
    }

    private static final class SequenceTool implements Tool {
        private final String name;
        private final Deque<ToolResult> results;
        private final AtomicInteger calls = new AtomicInteger();
        SequenceTool(String name, ToolResult... results) {
            this.name = name;
            this.results = new ArrayDeque<>(List.of(results));
        }
        @Override public String name() { return name; }
        @Override public String description() { return "test tool"; }
        @Override public String parametersJson() { return "{\"type\":\"object\"}"; }
        @Override public ToolResult execute(Map<String,Object> args, ToolContext ctx) {
            calls.incrementAndGet();
            if (results.isEmpty()) throw new AssertionError("unexpected extra tool execution");
            return results.removeFirst();
        }
    }
}
