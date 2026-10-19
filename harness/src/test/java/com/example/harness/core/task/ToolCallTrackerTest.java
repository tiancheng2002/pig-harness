package com.example.harness.core.task;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.memory.Db;
import com.example.harness.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallTrackerTest {
    @TempDir Path temp;

    @Test void tracksSuccessFailureRejectionTimeoutAndConcurrentCalls() throws Exception {
        HarnessProperties props=new HarnessProperties();
        Db db=new Db(temp.resolve("tools.db").toString());
        ToolCallStore store=new ToolCallStore(db);
        ToolCallTracker tracker=new ToolCallTracker(store,props);
        TaskRunContext ctx=new TaskRunContext("run",null,null,"fp",Instant.now(),System.nanoTime(),true);
        tracker.track(ctx,"ok",null,"fileRead",Map.of("path","a.txt"),()->ToolResult.success("ok"));
        tracker.track(ctx,"bad",null,"fileWrite",Map.of("path","b.txt"),()->ToolResult.error("failed"));
        tracker.track(ctx,"timeout",null,"bash",Map.of("command","secret"),()->ToolResult.error("执行超时"));
        tracker.rejected(ctx,"reject",null,"fileDelete",Map.of("path","c.txt"),"用户拒绝");

        var executor=Executors.newFixedThreadPool(4);
        for(int i=0;i<20;i++){int n=i;executor.submit(()->tracker.track(ctx,"c"+n,"agent-"+(n%2),"fileRead",Map.of("path","p"+n),()->ToolResult.success("ok")));}
        executor.shutdown();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));

        List<Map<String,Object>> rows=new ArrayList<>();rows.addAll(store.list("run",100));
        assertEquals(24,rows.size());assertEquals(24,ctx.metrics().toolCalls());assertEquals(3,ctx.metrics().failedToolCalls());
        assertTrue(rows.stream().anyMatch(x->"rejected".equals(x.get("status"))));
        assertTrue(rows.stream().anyMatch(x->"timeout".equals(x.get("status"))));
        assertTrue(rows.stream().filter(x->"bash".equals(x.get("name"))).allMatch(x->"<command redacted>".equals(x.get("target"))));
        db.close();
    }
}
