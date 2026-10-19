package com.example.harness.core.task;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.ToolRegistry;
import com.example.harness.tools.ToolResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class ToolCallTracker {
    private final ToolCallStore store;
    private final HarnessProperties props;
    public ToolCallTracker(ToolCallStore store,HarnessProperties props){this.store=store;this.props=props;}

    public ToolResult track(TaskRunContext ctx,String callId,String agentId,String name,Map<String,Object> args,
                            java.util.function.Supplier<ToolResult> action){if(!props.getTaskRuntime().isEnabled()||(ctx!=null&&!ctx.enabled()))return action.get();Instant at=Instant.now();long start=System.nanoTime();
        ToolResult result;try{result=action.get();}catch(RuntimeException e){record(ctx,callId,agentId,name,args,"error",e.getMessage(),at,elapsed(start));throw e;}
        record(ctx,callId,agentId,name,args,result.ok()?"success":status(result.output()),result.ok()?null:result.output(),at,elapsed(start));return result;}
    public void rejected(TaskRunContext ctx,String callId,String agentId,String name,Map<String,Object> args,String reason){if(!props.getTaskRuntime().isEnabled()||(ctx!=null&&!ctx.enabled()))return;record(ctx,callId,agentId,name,args,"rejected",reason,Instant.now(),0);}
    public void synthetic(TaskRunContext ctx,String callId,String agentId,String name,Map<String,Object> args,
                          boolean ok,String error,Instant startedAt,long durationMs){if(!props.getTaskRuntime().isEnabled()||(ctx!=null&&!ctx.enabled()))return;record(ctx,callId,agentId,name,args,ok?"success":"error",error,startedAt,durationMs);}
    private void record(TaskRunContext ctx,String callId,String agentId,String name,Map<String,Object> args,String status,String error,Instant at,long ms){
        if(ctx!=null){ctx.metrics().toolCalls.increment();ctx.metrics().toolDurationMs.add(ms);if(!"success".equals(status))ctx.metrics().failedToolCalls.increment();ctx.metrics().addPhase("tool",ms);}
        if(props.getTaskRuntime().getMetrics().isPersistToolCalls())store.insert(ctx==null?null:ctx.runId(),callId,agentId,name,target(args),ToolRegistry.MUTATING_TOOLS.contains(name),at,ms,status,error);}
    private String target(Map<String,Object> a){if(a==null)return "";Object path=a.get("path");if(path!=null)return String.valueOf(path);
        Object url=a.get("url");if(url!=null){String s=String.valueOf(url);int q=s.indexOf('?');return q<0?s:s.substring(0,q)+"?<redacted>";}
        if(a.containsKey("command"))return "<command redacted>";if(a.containsKey("query"))return "<query redacted>";return "";}
    private String status(String out){String s=out==null?"":out.toLowerCase();return s.contains("超时")||s.contains("timeout")?"timeout":"error";}
    private long elapsed(long s){return Math.max(0,(System.nanoTime()-s)/1_000_000L);}
}
