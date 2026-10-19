package com.example.harness.core.task;

import com.example.harness.config.HarnessProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TaskRunService {
    private static final Logger log=LoggerFactory.getLogger(TaskRunService.class);
    private final TaskRunStore store;
    private final LlmCallStore llmCalls;
    private final ToolCallStore toolCalls;
    private final HarnessProperties props;
    private final ObjectMapper om;
    private final com.example.harness.core.memory.ConversationStore conversations;
    private final AtomicLong lastCleanupDay=new AtomicLong(-1);

    public TaskRunService(TaskRunStore store,LlmCallStore llmCalls,ToolCallStore toolCalls,
                          HarnessProperties props,ObjectMapper om,
                          com.example.harness.core.memory.ConversationStore conversations){this.store=store;this.llmCalls=llmCalls;this.toolCalls=toolCalls;this.props=props;this.om=om;this.conversations=conversations;}

    @PostConstruct
    void recover(){if(!props.getTaskRuntime().isEnabled())return;try{
        String json=om.writeValueAsString(Map.of("status","INTERRUPTED","mode","TASK",
                "summary","服务在任务完成前退出，本次运行已中断。","changedFiles",List.of(),
                "changeSummaryComplete",false,"verifications",List.of(),"failedAgents",List.of(),
                "unresolved",List.of("任务未完成，需重新执行"),"risks",List.of(),"metrics",Map.of()));
        List<Map<String,Object>> orphaned=store.interruptOrphans(json);for(Map<String,Object> row:orphaned){String conv=(String)row.get("conversationId");if(conv==null||row.get("userMessageId")==null||row.get("assistantMessageId")!=null)continue;
            try{String id=String.valueOf(row.get("id"));Map<String,Object> outcome=om.readValue(json,Map.class);List<Map<String,Object>> events=List.of(Map.of("type","task_outcome","runId",id,"outcome",outcome),Map.of("type","done","runId",id,"status","INTERRUPTED","conclusion","服务在任务完成前退出，本次运行已中断。"));
                long assistant=conversations.addMessage(conv,"assistant","服务在任务完成前退出，本次运行已中断。",om.writeValueAsString(events),null,id);if(assistant>0)store.linkRecoveredAssistant(id,assistant);
            }catch(Exception e){log.warn("补充中断结论失败（{}）: {}",row.get("id"),e.getMessage());}}
        int n=orphaned.size();if(n>0)log.warn("已将 {} 条遗留 TaskRun 标记为 INTERRUPTED",n);
        cleanupIfDue();
    }catch(Exception e){log.warn("TaskRun 启动恢复失败: {}",e.getMessage());}}

    public TaskRunContext begin(String runId,String conversationId,String projectId,Path root,String requestedModel){
        boolean enabled=props.getTaskRuntime().isEnabled();TaskRunContext ctx=new TaskRunContext(runId,conversationId,projectId,
                fingerprint(root),Instant.now(),System.nanoTime(),enabled);if(enabled){store.insert(ctx,requestedModel);store.markRunning(runId);cleanupIfDue();}return ctx;}
    public void linkUser(TaskRunContext ctx,Long id){ctx.userMessageId(id);if(ctx.enabled())store.linkUser(ctx.runId(),id);}
    public void mode(TaskRunContext ctx,String mode){if(ctx.enabled())store.setMode(ctx.runId(),mode);}
    public void beginWait(TaskRunContext ctx){if(ctx.beginWait()&&ctx.enabled())store.markWaiting(ctx.runId());}
    public void endWait(TaskRunContext ctx){if(ctx.endWait()&&ctx.enabled())store.markResumed(ctx.runId());}

    private void cleanupIfDue(){long day=Instant.now().getEpochSecond()/86400;long previous=lastCleanupDay.get();if(previous==day||!lastCleanupDay.compareAndSet(previous,day))return;
        int keep=Math.max(1,props.getTaskRuntime().getMetrics().getDetailRetentionDays());String cutoff=Instant.now().minus(keep,ChronoUnit.DAYS).toString();
        llmCalls.deleteBefore(cutoff);toolCalls.deleteBefore(cutoff);}
    private String fingerprint(Path root){if(root==null)return null;try{byte[] b=MessageDigest.getInstance("SHA-256").digest(root.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));return java.util.HexFormat.of().formatHex(b);}catch(Exception e){return null;}}
}
