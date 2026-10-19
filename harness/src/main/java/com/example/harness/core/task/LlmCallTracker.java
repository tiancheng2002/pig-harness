package com.example.harness.core.task;

import com.example.harness.config.HarnessProperties;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.ChatResult;
import com.example.harness.llm.LlmMessage;
import com.example.harness.llm.LlmToolSpec;
import com.example.harness.llm.TokenEstimator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 显式 ChatClient 装饰器：一次 chat() 对应一条 llm_calls，失败和重试同样记录。 */
@Component
public class LlmCallTracker {
    private final LlmCallStore store;
    private final HarnessProperties props;
    public LlmCallTracker(LlmCallStore store,HarnessProperties props){this.store=store;this.props=props;}

    public ChatClient tracked(ChatClient delegate,TaskRunContext ctx,String phase,String agentId){
        if (!props.getTaskRuntime().isEnabled() || (ctx != null && !ctx.enabled())) return delegate;
        return new ChatClient(){
            @Override public ChatResult chat(List<LlmMessage> messages,List<LlmToolSpec> tools,StreamListener listener){
                Instant at=Instant.now();long start=System.nanoTime();AtomicLong first=new AtomicLong(-1);
                StreamListener wrapped=listener==null?null:new StreamListener(){
                    private void mark(){if(first.compareAndSet(-1,System.nanoTime())){if(ctx!=null)ctx.markFirstToken();}}
                    @Override public void onText(String d){mark();listener.onText(d);}
                    @Override public void onReasoning(String d){mark();listener.onReasoning(d);}
                    @Override public void onToolCallDelta(){mark();listener.onToolCallDelta();}
                };
                try{
                    ChatResult r=delegate.chat(messages,tools,wrapped);long ms=elapsed(start);
                    boolean estimated=r.usage()==null||r.usage().promptTokens()==null||r.usage().promptTokens()<=0
                            ||"mock".equalsIgnoreCase(delegate.modelName());
                    int prompt=estimated?TokenEstimator.estimateMessages(messages):r.usage().promptTokens();
                    int completion=r.usage()!=null&&r.usage().completionTokens()!=null&&r.usage().completionTokens()>=0
                            ?r.usage().completionTokens():TokenEstimator.estimate((r.content()==null?"":r.content())+(r.reasoning()==null?"":r.reasoning()));
                    record(ctx,phase,agentId,delegate.modelName(),prompt,completion,estimated,at,ms,firstMs(first,start),"success",null);
                    return r;
                }catch(RuntimeException e){long ms=elapsed(start);record(ctx,phase,agentId,delegate.modelName(),null,null,false,at,ms,firstMs(first,start),"error",e.getMessage());throw e;}
            }
            @Override public String modelName(){return delegate.modelName();}
        };}

    public void recordExternal(TaskRunContext ctx,String phase,String agentId,String model,Integer prompt,Integer completion,
                               boolean estimated,Instant at,long durationMs,Long firstTokenMs,String status,String error){
        if (!props.getTaskRuntime().isEnabled() || (ctx != null && !ctx.enabled())) return;
        record(ctx,phase,agentId,model,prompt,completion,estimated,at,durationMs,firstTokenMs,status,error);
    }

    private void record(TaskRunContext ctx,String phase,String agentId,String model,Integer prompt,Integer completion,
                        boolean estimated,Instant at,long durationMs,Long firstTokenMs,String status,String error){
        Double cost=price(model,prompt,completion);if(ctx!=null){var m=ctx.metrics();m.llmCalls.increment();m.llmDurationMs.add(durationMs);
            if(prompt!=null)m.promptTokens.add(prompt);if(completion!=null)m.completionTokens.add(completion);if(estimated)m.estimatedCalls.increment();
            if(cost==null)m.unpriced.set(true);else m.knownCostUsd.add(cost);m.addPhase(phase,durationMs);}
        boolean persist=props.getTaskRuntime().getMetrics().isPersistLlmCalls();
        if(persist)store.insert(ctx==null?null:ctx.runId(),phase,agentId,model,prompt,completion,estimated,at,durationMs,firstTokenMs,cost,status,error);
    }
    private Double price(String model,Integer p,Integer c){HarnessProperties.Price price=props.getPricing().get(model);if(price==null)return null;return (p==null?0:p)/1_000_000d*price.getInput()+(c==null?0:c)/1_000_000d*price.getOutput();}
    private static long elapsed(long start){return Math.max(0,(System.nanoTime()-start)/1_000_000L);}
    private static Long firstMs(AtomicLong first,long start){long v=first.get();return v<0?null:Math.max(0,(v-start)/1_000_000L);}
}
