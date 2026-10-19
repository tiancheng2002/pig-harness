package com.example.harness.core.task;

import com.example.harness.core.memory.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class TaskRunStore {
    private static final Logger log=LoggerFactory.getLogger(TaskRunStore.class);
    private final Connection conn;
    public TaskRunStore(Db db){this.conn=db.connection();}

    public void insert(TaskRunContext ctx,String requestedModel){synchronized(conn){try(PreparedStatement ps=conn.prepareStatement("""
            INSERT INTO task_runs(id,conversation_id,project_id,root_fingerprint,status,model_requested,
            started_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)
            """)){String now=ctx.startedAt().toString();ps.setString(1,ctx.runId());ps.setString(2,ctx.conversationId());
        ps.setString(3,ctx.projectId());ps.setString(4,ctx.rootFingerprint());ps.setString(5,RunStatus.CREATED.name());
        ps.setString(6,requestedModel);ps.setString(7,now);ps.setString(8,now);ps.setString(9,now);ps.executeUpdate();
    }catch(Exception e){throw new IllegalStateException("创建 TaskRun 失败: "+e.getMessage(),e);}}}

    public void linkUser(String runId,Long id){updateSimple("UPDATE task_runs SET user_message_id=?,updated_at=? WHERE id=?",runId,id);}
    public void linkRecoveredAssistant(String runId,Long id){updateSimple("UPDATE task_runs SET assistant_message_id=?,updated_at=? WHERE id=?",runId,id);}
    public void markRunning(String runId){transition(runId,List.of(RunStatus.CREATED),RunStatus.RUNNING);}
    public void markWaiting(String runId){transition(runId,List.of(RunStatus.RUNNING),RunStatus.WAITING_APPROVAL);}
    public void markResumed(String runId){transition(runId,List.of(RunStatus.WAITING_APPROVAL),RunStatus.RUNNING);}
    public void setMode(String runId,String mode){synchronized(conn){try(PreparedStatement ps=conn.prepareStatement("UPDATE task_runs SET mode=?,updated_at=? WHERE id=?")){ps.setString(1,mode);ps.setString(2,Instant.now().toString());ps.setString(3,runId);ps.executeUpdate();}catch(Exception e){log.warn("更新 TaskRun mode 失败: {}",e.getMessage());}}}

    public boolean finish(TaskRunContext ctx,ExecutionResult result,TaskOutcome outcome,String json,Long assistantId){
        synchronized(conn){try(PreparedStatement ps=conn.prepareStatement("""
                UPDATE task_runs SET assistant_message_id=?,trace_id=?,mode=?,status=?,finished_at=?,
                wall_duration_ms=?,first_token_ms=?,approval_wait_ms=?,llm_calls=?,tool_calls=?,
                prompt_tokens=?,completion_tokens=?,estimated_calls=?,cost_usd=?,outcome_json=?,
                error_code=?,error_summary=?,updated_at=?
                WHERE id=? AND status IN ('CREATED','RUNNING','WAITING_APPROVAL')
                """)){bindFinish(ps,ctx,result,outcome,json,assistantId);return ps.executeUpdate()==1;
        }catch(Exception e){log.warn("TaskRun 收尾写入失败: {}",e.getMessage());return false;}}}

    /** Assistant 消息与 TaskRun 终态必须同事务提交；条件更新保证跨线程/重试幂等。 */
    public Long finalizeWithAssistant(TaskRunContext ctx,ExecutionResult result,TaskOutcome outcome,
                                      String outcomeJson,String eventsJson){
        if(ctx.conversationId()==null||ctx.conversationId().isBlank()){
            finish(ctx,result,outcome,outcomeJson,null);
            return null;
        }
        synchronized(conn){boolean previousAutoCommit=true;try{
            previousAutoCommit=conn.getAutoCommit();conn.setAutoCommit(false);Long assistantId;
            try(PreparedStatement insert=conn.prepareStatement("""
                    INSERT INTO messages(conversation_id,role,content,events,trace_id,task_run_id,created_at)
                    VALUES(?,?,?,?,?,?,?)
                    """,Statement.RETURN_GENERATED_KEYS)){
                insert.setString(1,ctx.conversationId());insert.setString(2,"assistant");insert.setString(3,result.conclusion());
                insert.setString(4,eventsJson);setLong(insert,5,result.traceId());insert.setString(6,ctx.runId());
                insert.setString(7,LocalDateTime.now().toString());insert.executeUpdate();
                try(ResultSet keys=insert.getGeneratedKeys()){assistantId=keys.next()?keys.getLong(1):null;}
            }
            try(PreparedStatement update=conn.prepareStatement("""
                    UPDATE task_runs SET assistant_message_id=?,trace_id=?,mode=?,status=?,finished_at=?,
                    wall_duration_ms=?,first_token_ms=?,approval_wait_ms=?,llm_calls=?,tool_calls=?,
                    prompt_tokens=?,completion_tokens=?,estimated_calls=?,cost_usd=?,outcome_json=?,
                    error_code=?,error_summary=?,updated_at=?
                    WHERE id=? AND status IN ('CREATED','RUNNING','WAITING_APPROVAL')
                    """)){
                bindFinish(update,ctx,result,outcome,outcomeJson,assistantId);
                if(update.executeUpdate()!=1){conn.rollback();return null;}
            }
            try(PreparedStatement touch=conn.prepareStatement("UPDATE conversations SET updated_at=? WHERE id=?")){
                touch.setString(1,LocalDateTime.now().toString());touch.setString(2,ctx.conversationId());touch.executeUpdate();
            }
            conn.commit();return assistantId;
        }catch(Exception e){try{conn.rollback();}catch(Exception ignored){}throw new IllegalStateException("TaskRun 原子收尾失败: "+e.getMessage(),e);
        }finally{try{conn.setAutoCommit(previousAutoCommit);}catch(Exception ignored){}}}}

    public List<Map<String,Object>> interruptOrphans(String outcomeJson){List<Map<String,Object>> rows=new ArrayList<>();
        synchronized(conn){try(PreparedStatement q=conn.prepareStatement("""
                SELECT id,conversation_id,user_message_id,mode,started_at,assistant_message_id FROM task_runs
                WHERE status IN ('CREATED','RUNNING','WAITING_APPROVAL')
                """)){try(ResultSet rs=q.executeQuery()){while(rs.next()){Map<String,Object> m=new LinkedHashMap<>();
                    m.put("id",rs.getString(1));m.put("conversationId",rs.getString(2));m.put("userMessageId",nullableLong(rs,3));
                    m.put("mode",rs.getString(4));m.put("startedAt",rs.getString(5));m.put("assistantMessageId",nullableLong(rs,6));rows.add(m);}}
            try(PreparedStatement ps=conn.prepareStatement("""
                    UPDATE task_runs SET status='INTERRUPTED',finished_at=?,outcome_json=?,updated_at=?
                    WHERE status IN ('CREATED','RUNNING','WAITING_APPROVAL')
                    """)){String now=Instant.now().toString();ps.setString(1,now);ps.setString(2,outcomeJson);ps.setString(3,now);ps.executeUpdate();}
        }catch(Exception e){log.warn("恢复中断 TaskRun 失败: {}",e.getMessage());}}return rows;}

    public Optional<Map<String,Object>> get(String id){synchronized(conn){try(PreparedStatement ps=conn.prepareStatement("SELECT * FROM task_runs WHERE id=?")){ps.setString(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?Optional.of(row(rs)):Optional.empty();}}catch(Exception e){log.warn("读取 TaskRun 失败: {}",e.getMessage());return Optional.empty();}}}
    public List<Map<String,Object>> listConversation(String conversationId,Long messageId){List<Map<String,Object>> out=new ArrayList<>();
        String sql="SELECT * FROM task_runs WHERE conversation_id=?"+(messageId==null?"":" AND (user_message_id=? OR assistant_message_id=?)")+" ORDER BY created_at DESC LIMIT 100";
        synchronized(conn){try(PreparedStatement ps=conn.prepareStatement(sql)){ps.setString(1,conversationId);if(messageId!=null){ps.setLong(2,messageId);ps.setLong(3,messageId);}try(ResultSet rs=ps.executeQuery()){while(rs.next())out.add(row(rs));}}catch(Exception e){log.warn("读取会话 TaskRun 失败: {}",e.getMessage());}}return out;}

    private Map<String,Object> row(ResultSet rs)throws Exception{Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",rs.getString("id"));m.put("conversationId",rs.getString("conversation_id"));m.put("userMessageId",nullableLong(rs,"user_message_id"));
        m.put("assistantMessageId",nullableLong(rs,"assistant_message_id"));m.put("traceId",nullableLong(rs,"trace_id"));m.put("projectId",rs.getString("project_id"));
        m.put("mode",rs.getString("mode"));m.put("status",rs.getString("status"));m.put("modelRequested",rs.getString("model_requested"));
        m.put("startedAt",rs.getString("started_at"));m.put("finishedAt",rs.getString("finished_at"));m.put("wallDurationMs",rs.getLong("wall_duration_ms"));
        m.put("firstTokenMs",nullableLong(rs,"first_token_ms"));m.put("approvalWaitMs",rs.getLong("approval_wait_ms"));m.put("llmCalls",rs.getInt("llm_calls"));
        m.put("toolCalls",rs.getInt("tool_calls"));m.put("promptTokens",rs.getLong("prompt_tokens"));m.put("completionTokens",rs.getLong("completion_tokens"));
        m.put("estimatedCalls",rs.getInt("estimated_calls"));m.put("costUsd",nullableDouble(rs,"cost_usd"));m.put("outcomeJson",rs.getString("outcome_json"));
        m.put("errorCode",rs.getString("error_code"));m.put("errorSummary",rs.getString("error_summary"));return m;}

    private void transition(String id,List<RunStatus> from,RunStatus to){String marks=String.join(",",java.util.Collections.nCopies(from.size(),"?"));
        synchronized(conn){try(PreparedStatement ps=conn.prepareStatement("UPDATE task_runs SET status=?,updated_at=? WHERE id=? AND status IN ("+marks+")")){ps.setString(1,to.name());ps.setString(2,Instant.now().toString());ps.setString(3,id);for(int i=0;i<from.size();i++)ps.setString(4+i,from.get(i).name());ps.executeUpdate();}catch(Exception e){log.warn("TaskRun 状态迁移失败: {}",e.getMessage());}}}
    private void updateSimple(String sql,String id,Long value){synchronized(conn){try(PreparedStatement ps=conn.prepareStatement(sql)){setLong(ps,1,value);ps.setString(2,Instant.now().toString());ps.setString(3,id);ps.executeUpdate();}catch(Exception e){log.warn("更新 TaskRun 失败: {}",e.getMessage());}}}
    private static void bindFinish(PreparedStatement ps,TaskRunContext ctx,ExecutionResult result,TaskOutcome outcome,String json,Long assistantId)throws Exception{
        var m=outcome.metrics();setLong(ps,1,assistantId);setLong(ps,2,result.traceId());ps.setString(3,outcome.mode());
        ps.setString(4,outcome.status());String now=Instant.now().toString();ps.setString(5,now);
        ps.setLong(6,m.wallDurationMs());setLong(ps,7,m.firstTokenMs());ps.setLong(8,m.approvalWaitMs());
        ps.setInt(9,m.llmCalls());ps.setInt(10,m.toolCalls());ps.setLong(11,m.promptTokens());
        ps.setLong(12,m.completionTokens());ps.setInt(13,m.estimatedCalls());
        if(m.costUsd()==null)ps.setNull(14,Types.REAL);else ps.setDouble(14,m.costUsd());
        ps.setString(15,json);ps.setString(16,result.failureCode());ps.setString(17,result.failureSummary());
        ps.setString(18,now);ps.setString(19,ctx.runId());
    }
    private static void setLong(PreparedStatement ps,int i,Long v)throws Exception{if(v==null)ps.setNull(i,Types.INTEGER);else ps.setLong(i,v);}
    private static Long nullableLong(ResultSet rs,int i)throws Exception{long v=rs.getLong(i);return rs.wasNull()?null:v;}
    private static Long nullableLong(ResultSet rs,String n)throws Exception{long v=rs.getLong(n);return rs.wasNull()?null:v;}
    private static Double nullableDouble(ResultSet rs,String n)throws Exception{double v=rs.getDouble(n);return rs.wasNull()?null:v;}
}
