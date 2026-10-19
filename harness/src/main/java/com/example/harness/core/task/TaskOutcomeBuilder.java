package com.example.harness.core.task;

import com.example.harness.core.git.GitService;
import com.example.harness.core.memory.ProjectStore;
import com.example.harness.model.GitStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TaskOutcomeBuilder {
    private final LlmCallStore llmCalls;
    private final ProjectStore projects;
    private final GitService git;
    public TaskOutcomeBuilder(LlmCallStore llmCalls,ProjectStore projects,GitService git){this.llmCalls=llmCalls;this.projects=projects;this.git=git;}

    public TaskOutcome build(TaskRunContext ctx,ExecutionResult result,List<Map<String,Object>> events){
        TaskOutcome.PlanSummary plan=plan(events);List<TaskOutcome.Verification> verifies=verifications(events);
        List<TaskOutcome.FailedAgent> agents=failedAgents(events);List<String> unresolved=new ArrayList<>();List<String> risks=new ArrayList<>();
        if(plan.failed()>0)unresolved.add(plan.failed()+" 个计划步骤失败");
        if(plan.total()>plan.completed()+plan.failed())unresolved.add((plan.total()-plan.completed()-plan.failed())+" 个计划步骤未完成");
        verifies.stream().filter(v->"FAILED".equals(v.status())).forEach(v->unresolved.add("验证未通过："+v.name()));
        agents.forEach(a->unresolved.add("子智能体 "+a.agentId()+" 未完成"));
        if(result.stepLimitReached())unresolved.add("达到最大执行步数");
        for(Map<String,Object> event:events)if("guard".equals(event.get("type"))&&Boolean.TRUE.equals(event.get("terminal"))){
            String message=String.valueOf(event.getOrDefault("message","交付闸门未满足"));
            if(!unresolved.contains(message))unresolved.add(message);
        }
        List<TaskOutcome.ChangedFile> files=changedFiles(ctx,events);
        if(!files.isEmpty())risks.add("文件变更来自事件与当前 Git 状态，Shell/MCP 直接修改可能未完整统计");
        RunStatus status=decide(result,plan,verifies,agents);
        long wall=ctx.elapsedMs(),wait=Math.min(wall,ctx.metrics().approvalWaitMs());
        TaskOutcome.Metrics metrics=new TaskOutcome.Metrics(wall,ctx.metrics().firstTokenMs(),Math.max(0,wall-wait),wait,
                ctx.metrics().llmDurationMs(),ctx.metrics().toolDurationMs(),ctx.metrics().llmCalls(),ctx.metrics().toolCalls(),
                ctx.metrics().failedToolCalls(),ctx.metrics().promptTokens(),ctx.metrics().completionTokens(),ctx.metrics().estimatedCalls(),
                ctx.metrics().costUsd(),ctx.metrics().phaseDurationsMs(),llmCalls.group(ctx.runId(),"model"),
                llmCalls.group(ctx.runId(),"phase"),llmCalls.group(ctx.runId(),"agent_id"));
        return new TaskOutcome(status.name(),safeMode(result.mode()),summary(result.conclusion()),plan,files,false,
                verifies,agents,List.copyOf(unresolved),List.copyOf(risks),metrics);
    }

    private RunStatus decide(ExecutionResult r,TaskOutcome.PlanSummary p,List<TaskOutcome.Verification> v,List<TaskOutcome.FailedAgent>a){
        if(r.status()==RunStatus.STOPPED)return RunStatus.STOPPED;
        if(r.status()==RunStatus.INTERRUPTED)return RunStatus.INTERRUPTED;
        if(r.status()==RunStatus.FAILED||r.conclusion()==null||r.conclusion().isBlank())return RunStatus.FAILED;
        boolean partial=r.status()==RunStatus.PARTIAL||r.stepLimitReached()||p.failed()>0||p.total()>p.completed()+p.failed()
                ||v.stream().anyMatch(x->"FAILED".equals(x.status()))||!a.isEmpty();
        return partial?RunStatus.PARTIAL:RunStatus.SUCCESS;
    }

    @SuppressWarnings("unchecked") private TaskOutcome.PlanSummary plan(List<Map<String,Object>> e){int total=0;Map<Integer,String> states=new LinkedHashMap<>();
        for(Map<String,Object>x:e){if("plan".equals(x.get("type"))&&x.get("steps") instanceof List<?> l)total=Math.max(total,l.size());
            if("plan_step".equals(x.get("type"))&&x.get("index") instanceof Number n)states.put(n.intValue(),String.valueOf(x.get("status")));}
        int done=0,failed=0;for(String s:states.values()){if("done".equals(s))done++;else if("failed".equals(s))failed++;}return new TaskOutcome.PlanSummary(total,done,failed);}
    private List<TaskOutcome.Verification> verifications(List<Map<String,Object>> e){Map<String,TaskOutcome.Verification> last=new LinkedHashMap<>();for(Map<String,Object>x:e)if("hook".equals(x.get("type"))||"verification".equals(x.get("type"))){String n=String.valueOf(x.getOrDefault("name","verify"));String key="verification".equals(x.get("type"))?String.valueOf(x.getOrDefault("kind",n)):"hook:"+n;boolean ok=Boolean.TRUE.equals(x.get("ok"));long ms=x.get("durationMs") instanceof Number z?z.longValue():0;last.put(key,new TaskOutcome.Verification(n,ok?"PASSED":"FAILED",ms,truncate(String.valueOf(x.getOrDefault("output","")),300)));}return List.copyOf(last.values());}
    private List<TaskOutcome.FailedAgent> failedAgents(List<Map<String,Object>> e){Map<String,Map<String,Object>> last=new LinkedHashMap<>();for(Map<String,Object>x:e)if("agent_done".equals(x.get("type")))last.put(String.valueOf(x.get("agent")),x);List<TaskOutcome.FailedAgent> out=new ArrayList<>();last.forEach((id,x)->{String s=String.valueOf(x.get("status"));if(!"success".equals(s))out.add(new TaskOutcome.FailedAgent(id,s,truncate(String.valueOf(x.getOrDefault("summary","")),300)));});return out;}
    @SuppressWarnings("unchecked") private List<TaskOutcome.ChangedFile> changedFiles(TaskRunContext ctx,List<Map<String,Object>> e){Set<String> touched=new LinkedHashSet<>();Map<String,String> source=new LinkedHashMap<>();for(Map<String,Object>x:e){if("file".equals(x.get("type"))){addPath(touched,source,x.get("path"),"file_event");}
            if("tool_call".equals(x.get("type"))&&x.get("args") instanceof Map<?,?> a){String n=String.valueOf(x.get("name"));if(com.example.harness.core.ToolRegistry.MUTATING_TOOLS.contains(n))addPath(touched,source,a.get("path"),"tool_call");}}
        List<TaskOutcome.ChangedFile> out=new ArrayList<>();if(ctx.projectId()!=null){projects.get(ctx.projectId()).ifPresent(p->{GitStatus s=git.status(Path.of(p.rootPath()));if(s.repo()){for(GitStatus.Change c:s.changes())if(touched.contains(norm(c.path())))out.add(new TaskOutcome.ChangedFile(norm(c.path()),type(c.code()),c.additions(),c.deletions(),source.getOrDefault(norm(c.path()),"git_status")));}else for(String path:touched)out.add(new TaskOutcome.ChangedFile(path,"MODIFY",null,null,source.get(path)));});}
        else for(String p:touched)out.add(new TaskOutcome.ChangedFile(p,"MODIFY",null,null,source.get(p)));return out;}
    private void addPath(Set<String>s,Map<String,String>src,Object value,String source){if(value==null)return;String p=norm(String.valueOf(value));if(!p.isBlank()){s.add(p);src.putIfAbsent(p,source);}}
    private String type(String code){if(code==null)return"MODIFY";if(code.contains("D"))return"DELETE";if(code.contains("A")||code.contains("?"))return"ADD";if(code.contains("R"))return"RENAME";return"MODIFY";}
    private String safeMode(String m){return m==null||m.isBlank()?"TASK":m;}
    private String summary(String s){if(s==null||s.isBlank())return"任务未生成可用结论。";String clean=s.replaceAll("(?m)^#{1,6}\\s*","").trim();return truncate(clean,360);}
    private String norm(String p){if(p==null)return"";String value=p.replace('\\','/').replaceFirst("^\\./","");
        if(value.matches("^[A-Za-z]:/.*")||value.startsWith("/")||value.startsWith("//")){int slash=value.lastIndexOf('/');return "<absolute>/"+(slash>=0?value.substring(slash+1):value);}
        return value;}
    private static String truncate(String s,int n){return s==null?"":s.length()<=n?s:s.substring(0,n)+"…";}
}
