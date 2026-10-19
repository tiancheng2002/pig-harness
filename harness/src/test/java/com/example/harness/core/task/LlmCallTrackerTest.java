package com.example.harness.core.task;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.memory.Db;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.ChatResult;
import com.example.harness.llm.LlmMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmCallTrackerTest {
    @TempDir Path temp;

    @Test void tracksRealAndEstimatedUsageCostAndAgentGroups() {
        HarnessProperties props=new HarnessProperties();
        HarnessProperties.Price price=new HarnessProperties.Price();price.setInput(1);price.setOutput(2);
        props.getPricing().put("priced-model",price);
        Db db=new Db(temp.resolve("llm.db").toString());
        LlmCallStore store=new LlmCallStore(db);
        LlmCallTracker tracker=new LlmCallTracker(store,props);
        TaskRunContext ctx=new TaskRunContext("run",null,null,"fp",Instant.now(),System.nanoTime(),true);

        ChatClient priced=client("priced-model",new ChatResult("ok",null,List.of(),new ChatResult.Usage(100,20)));
        tracker.tracked(priced,ctx,"planner","agent-a").chat(List.of(LlmMessage.user("hello")),List.of(),null);
        ChatClient estimated=client("missing-price",new ChatResult("estimated reply",List.of()));
        tracker.tracked(estimated,ctx,"agent_loop","agent-b").chat(List.of(LlmMessage.user("hello")),List.of(),null);

        assertEquals(2,ctx.metrics().llmCalls());
        assertEquals(1,ctx.metrics().estimatedCalls());
        assertNull(ctx.metrics().costUsd(),"任一调用缺价时 Run 总成本应为空");
        assertEquals(2,store.count("run"));
        assertEquals(2,store.group("run","agent_id").size());
        var models=store.group("run","model");
        assertNotNull(models.stream().filter(x->"priced-model".equals(x.get("key"))).findFirst().orElseThrow().get("costUsd"));
        assertNull(models.stream().filter(x->"missing-price".equals(x.get("key"))).findFirst().orElseThrow().get("costUsd"));
        db.close();
    }

    private ChatClient client(String name,ChatResult result){return new ChatClient(){
        @Override public ChatResult chat(List<LlmMessage> messages,List<com.example.harness.llm.LlmToolSpec> tools,StreamListener listener){if(listener!=null)listener.onText(result.content());return result;}
        @Override public String modelName(){return name;}
    };}
}
