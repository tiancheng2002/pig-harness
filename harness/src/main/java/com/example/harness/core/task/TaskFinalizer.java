package com.example.harness.core.task;

import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.stream.StreamHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 主任务唯一收尾入口：Outcome、消息、终态、SSE 与连接完成。 */
@Component
public class TaskFinalizer {
    private static final Logger log=LoggerFactory.getLogger(TaskFinalizer.class);
    private final TaskOutcomeBuilder builder;private final TaskRunStore runs;private final ConversationStore conversations;private final ObjectMapper om;
    public TaskFinalizer(TaskOutcomeBuilder builder,TaskRunStore runs,ConversationStore conversations,ObjectMapper om){this.builder=builder;this.runs=runs;this.conversations=conversations;this.om=om;}

    public TaskOutcome finish(TaskRunContext ctx,ExecutionResult result,SseEmitter emitter,List<Map<String,Object>> transcript,StreamHub.Hub hub){
        try{
            if(!ctx.tryFinalize())return null;
            if(!ctx.enabled()){Map<String,Object> done=done(ctx,result,null);transcript.add(done);persist(ctx,result,transcript);send(emitter,hub,done);return null;}
            TaskOutcome outcome=builder.build(ctx,result,transcript);Map<String,Object> metrics=new LinkedHashMap<>();metrics.put("type","task_metrics");metrics.put("runId",ctx.runId());metrics.putAll(om.convertValue(outcome.metrics(),Map.class));
            Map<String,Object> outcomeEv=new LinkedHashMap<>();outcomeEv.put("type","task_outcome");outcomeEv.put("runId",ctx.runId());outcomeEv.put("outcome",outcome);
            Map<String,Object> done=done(ctx,result,outcome.status());transcript.add(metrics);transcript.add(outcomeEv);transcript.add(done);
            String json=om.writeValueAsString(outcome);runs.finalizeWithAssistant(ctx,result,outcome,json,om.writeValueAsString(transcript));
            send(emitter,hub,metrics);send(emitter,hub,outcomeEv);send(emitter,hub,done);return outcome;
        }catch(Exception e){log.error("TaskFinalizer 收尾失败",e);Map<String,Object> done=done(ctx,result,result.status().name());send(emitter,hub,done);return null;}
        finally{try{emitter.complete();}catch(Exception ignore){}}
    }
    private Long persist(TaskRunContext ctx,ExecutionResult r,List<Map<String,Object>> transcript)throws Exception{if(ctx.conversationId()==null||ctx.conversationId().isBlank())return null;long id=conversations.addMessage(ctx.conversationId(),"assistant",r.conclusion(),om.writeValueAsString(transcript),r.traceId(),ctx.enabled()?ctx.runId():null);return id<0?null:id;}
    private Map<String,Object> done(TaskRunContext ctx,ExecutionResult r,String status){Map<String,Object> m=new LinkedHashMap<>();m.put("type","done");m.put("conclusion",r.conclusion()==null?"":r.conclusion());if(r.traceId()!=null)m.put("traceId",r.traceId());if(ctx.enabled())m.put("runId",ctx.runId());if(status!=null)m.put("status",status);return m;}
    private void send(SseEmitter emitter,StreamHub.Hub hub,Map<String,Object> e){if(hub!=null)hub.record(e);try{emitter.send(SseEmitter.event().data(e,MediaType.APPLICATION_JSON));}catch(Exception ex){log.debug("收尾事件发送失败: {}",ex.getMessage());}}
}
