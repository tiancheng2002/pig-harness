package com.example.harness.core.task;

import com.example.harness.core.git.GitService;
import com.example.harness.core.memory.ProjectStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskOutcomeBuilderTest {
    @Test void finalHookFailureAndFailedAgentOverrideOptimisticConclusion() {
        LlmCallStore llm=mock(LlmCallStore.class);when(llm.group(anyString(),anyString())).thenReturn(List.of());
        TaskOutcomeBuilder builder=new TaskOutcomeBuilder(llm,mock(ProjectStore.class),mock(GitService.class));
        TaskRunContext ctx=new TaskRunContext("run",null,null,"fp",Instant.now(),System.nanoTime(),true);
        List<Map<String,Object>> events=new ArrayList<>();
        events.add(Map.of("type","plan","steps",List.of(Map.of("index",1),Map.of("index",2))));
        events.add(Map.of("type","plan_step","index",1,"status","done"));
        events.add(Map.of("type","hook","name","mvn test","ok",false,"durationMs",20,"output","1 failure"));
        Map<String,Object> agent=new LinkedHashMap<>();agent.put("type","agent_done");agent.put("agent","sub-1");agent.put("status","failed");agent.put("summary","boom");events.add(agent);
        TaskOutcome out=builder.build(ctx,ExecutionResult.success("全部完成",1L,"MULTI_AGENT"),events);
        assertEquals("PARTIAL",out.status());
        assertEquals(2,out.plan().total());assertEquals(1,out.plan().completed());
        assertEquals("FAILED",out.verifications().getFirst().status());
        assertEquals("sub-1",out.failedAgents().getFirst().agentId());
        assertFalse(out.changeSummaryComplete());
    }

    @Test void lastHookResultWinsAfterRepair() {
        LlmCallStore llm=mock(LlmCallStore.class);when(llm.group(anyString(),anyString())).thenReturn(List.of());
        TaskOutcomeBuilder builder=new TaskOutcomeBuilder(llm,mock(ProjectStore.class),mock(GitService.class));
        TaskRunContext ctx=new TaskRunContext("run",null,null,"fp",Instant.now(),System.nanoTime(),true);
        List<Map<String,Object>> events=List.of(
                Map.of("type","hook","name","test","ok",false,"durationMs",10,"output","bad"),
                Map.of("type","hook","name","test","ok",true,"durationMs",12,"output","ok"));
        TaskOutcome out=builder.build(ctx,ExecutionResult.success("完成",1L,"TASK"),events);
        assertEquals("SUCCESS",out.status());assertEquals("PASSED",out.verifications().getFirst().status());
    }

    @Test void terminalPriorityIsStoppedThenFailureThenPartial() {
        LlmCallStore llm=mock(LlmCallStore.class);when(llm.group(anyString(),anyString())).thenReturn(List.of());
        TaskOutcomeBuilder builder=new TaskOutcomeBuilder(llm,mock(ProjectStore.class),mock(GitService.class));
        TaskRunContext ctx=new TaskRunContext("run",null,null,"fp",Instant.now(),System.nanoTime(),true);
        assertEquals("STOPPED",builder.build(ctx,ExecutionResult.stopped("stopped",null,"TASK"),List.of()).status());
        assertEquals("FAILED",builder.build(ctx,ExecutionResult.failed("failed",null,"TASK","X","bad"),List.of()).status());
        ExecutionResult exhausted=new ExecutionResult(RunStatus.SUCCESS,"summary",null,"TASK",null,null,true);
        assertEquals("PARTIAL",builder.build(ctx,exhausted,List.of()).status());
        assertEquals("FAILED",builder.build(ctx,ExecutionResult.success("",null,"TASK"),List.of()).status());
    }
}
