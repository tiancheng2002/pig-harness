package com.example.harness.core.task;

import com.example.harness.core.memory.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskRunStateMachineTest {
    @TempDir Path temp;

    @Test void terminalWriteIsIdempotentAndWaitIntervalsAreSafe() {
        Db db=new Db(temp.resolve("runs.db").toString());
        TaskRunStore store=new TaskRunStore(db);
        TaskRunContext ctx=new TaskRunContext("run-1","conv-1",null,"fp",Instant.now(),System.nanoTime(),true);
        store.insert(ctx,"mock");store.markRunning(ctx.runId());
        assertTrue(ctx.beginWait());assertFalse(ctx.beginWait());assertFalse(ctx.endWait());assertTrue(ctx.endWait());
        TaskOutcome.Metrics metrics=new TaskOutcome.Metrics(10,null,10,0,0,0,0,0,0,0,0,0,0d,Map.of(),List.of(),List.of(),List.of());
        TaskOutcome outcome=new TaskOutcome("SUCCESS","CHAT","ok",new TaskOutcome.PlanSummary(0,0,0),List.of(),false,List.of(),List.of(),List.of(),List.of(),metrics);
        ExecutionResult result=ExecutionResult.success("ok",1L,"CHAT");
        assertTrue(store.finish(ctx,result,outcome,"{}",2L));
        assertFalse(store.finish(ctx,result,outcome,"{}",3L));
        assertEquals("SUCCESS",store.get(ctx.runId()).orElseThrow().get("status"));
        assertEquals(2L,store.get(ctx.runId()).orElseThrow().get("assistantMessageId"));
        db.close();
    }

    @Test void assistantAndTerminalStateCommitTogetherExactlyOnce() throws Exception {
        Db db=new Db(temp.resolve("atomic.db").toString());
        try(var ps=db.connection().prepareStatement("INSERT INTO conversations(id,title,created_at,updated_at) VALUES('conv','t','now','now')")){ps.executeUpdate();}
        TaskRunStore store=new TaskRunStore(db);
        TaskRunContext ctx=new TaskRunContext("run-atomic","conv",null,"fp",Instant.now(),System.nanoTime(),true);
        store.insert(ctx,"mock");store.markRunning(ctx.runId());ctx.userMessageId(1L);
        TaskOutcome.Metrics metrics=new TaskOutcome.Metrics(10,null,10,0,0,0,0,0,0,0,0,0,null,Map.of(),List.of(),List.of(),List.of());
        TaskOutcome outcome=new TaskOutcome("SUCCESS","CHAT","ok",new TaskOutcome.PlanSummary(0,0,0),List.of(),false,List.of(),List.of(),List.of(),List.of(),metrics);
        ExecutionResult result=ExecutionResult.success("ok",null,"CHAT");
        assertNotNull(store.finalizeWithAssistant(ctx,result,outcome,"{}","[]"));
        assertThrows(IllegalStateException.class,()->store.finalizeWithAssistant(ctx,result,outcome,"{}","[]"));
        try(var ps=db.connection().prepareStatement("SELECT COUNT(*) FROM messages WHERE task_run_id='run-atomic'");ResultSet rs=ps.executeQuery()){
            assertTrue(rs.next());assertEquals(1,rs.getInt(1));
        }
        db.close();
    }
}
