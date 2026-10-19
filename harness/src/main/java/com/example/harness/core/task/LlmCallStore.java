package com.example.harness.core.task;

import com.example.harness.core.memory.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LlmCallStore {
    private static final Logger log = LoggerFactory.getLogger(LlmCallStore.class);
    private final Connection conn;

    public LlmCallStore(Db db) { this.conn = db.connection(); }

    public void insert(String runId, String phase, String agentId, String model,
                       Integer prompt, Integer completion, boolean estimated,
                       Instant startedAt, long durationMs, Long firstTokenMs,
                       Double costUsd, String status, String error) {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO llm_calls(task_run_id,phase,agent_id,model,prompt_tokens,
                    completion_tokens,cached_tokens,estimated,started_at,duration_ms,first_token_ms,
                    cost_usd,status,error_summary) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                ps.setString(1, runId); ps.setString(2, phase); ps.setString(3, agentId);
                ps.setString(4, model == null || model.isBlank() ? "unknown" : model);
                setInt(ps, 5, prompt); setInt(ps, 6, completion); ps.setNull(7, Types.INTEGER);
                ps.setInt(8, estimated ? 1 : 0); ps.setString(9, startedAt.toString());
                ps.setLong(10, durationMs); setLong(ps, 11, firstTokenMs);
                if (costUsd == null) ps.setNull(12, Types.REAL); else ps.setDouble(12, costUsd);
                ps.setString(13, status); ps.setString(14, truncate(error, 500));
                ps.executeUpdate();
            } catch (Exception e) { log.warn("写入 llm_calls 失败: {}", e.getMessage()); }
        }
    }

    public List<Map<String, Object>> list(String runId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id,phase,agent_id,model,prompt_tokens,completion_tokens,estimated,
                    started_at,duration_ms,first_token_ms,cost_usd,status,error_summary
                    FROM llm_calls WHERE task_run_id=? ORDER BY id LIMIT ?
                    """)) {
                ps.setString(1, runId); ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String,Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong(1)); m.put("phase", rs.getString(2));
                        m.put("agentId", rs.getString(3)); m.put("model", rs.getString(4));
                        m.put("promptTokens", nullableLong(rs,5));
                        m.put("completionTokens", nullableLong(rs,6));
                        m.put("estimated", rs.getInt(7)==1); m.put("startedAt",rs.getString(8));
                        m.put("durationMs",rs.getLong(9)); m.put("firstTokenMs",nullableLong(rs,10));
                        m.put("costUsd",nullableDouble(rs,11)); m.put("status",rs.getString(12));
                        m.put("errorSummary",rs.getString(13)); out.add(m);
                    }
                }
            } catch (Exception e) { log.warn("读取 llm_calls 失败: {}", e.getMessage()); }
        }
        return out;
    }

    public int count(String runId) { return countTable(runId); }

    public List<Map<String,Object>> group(String runId, String column) {
        if (!List.of("model","phase","agent_id").contains(column)) return List.of();
        List<Map<String,Object>> out = new ArrayList<>();
        String sql = "SELECT COALESCE(" + column + ",'main'),COUNT(*),COALESCE(SUM(prompt_tokens),0),"
                + "COALESCE(SUM(completion_tokens),0),COALESCE(SUM(estimated),0),COALESCE(SUM(duration_ms),0),"
                + "SUM(cost_usd),SUM(CASE WHEN cost_usd IS NULL THEN 1 ELSE 0 END) "
                + "FROM llm_calls WHERE task_run_id=? GROUP BY COALESCE(" + column + ",'main') ORDER BY COUNT(*) DESC";
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, runId);
                try (ResultSet rs=ps.executeQuery()) { while(rs.next()) {
                    Map<String,Object> m=new LinkedHashMap<>(); m.put("key",rs.getString(1));
                    m.put("calls",rs.getInt(2)); m.put("promptTokens",rs.getLong(3));
                    m.put("completionTokens",rs.getLong(4)); m.put("estimatedCalls",rs.getInt(5));
                    m.put("durationMs",rs.getLong(6));
                    m.put("costUsd",rs.getInt(8)>0?null:nullableDouble(rs,7)); out.add(m);
                }}
            } catch(Exception e){ log.warn("聚合 llm_calls 失败: {}",e.getMessage()); }
        }
        return out;
    }

    public Map<String,Object> aggregateConversation(String conversationId){Map<String,Object> out=new LinkedHashMap<>();List<Map<String,Object>> byModel=new ArrayList<>();
        long prompt=0,completion=0;int calls=0,estimated=0;double knownCost=0;boolean unpriced=false;
        synchronized(conn){try(PreparedStatement ps=conn.prepareStatement("""
                SELECT l.model,COUNT(*),COALESCE(SUM(l.prompt_tokens),0),COALESCE(SUM(l.completion_tokens),0),
                COALESCE(SUM(l.estimated),0),SUM(l.cost_usd),SUM(CASE WHEN l.cost_usd IS NULL THEN 1 ELSE 0 END)
                FROM llm_calls l JOIN task_runs r ON r.id=l.task_run_id WHERE r.conversation_id=? GROUP BY l.model
                """)){ps.setString(1,conversationId);try(ResultSet rs=ps.executeQuery()){while(rs.next()){Map<String,Object> m=new LinkedHashMap<>();
            String model=rs.getString(1);int n=rs.getInt(2),est=rs.getInt(5),missing=rs.getInt(7);long p=rs.getLong(3),c=rs.getLong(4);Double cost=nullableDouble(rs,6);
            m.put("model",model);m.put("rounds",n);m.put("promptTokens",p);m.put("completionTokens",c);m.put("estimatedRounds",est);m.put("cost",missing>0?null:cost);byModel.add(m);
            calls+=n;prompt+=p;completion+=c;estimated+=est;if(cost!=null)knownCost+=cost;if(missing>0)unpriced=true;
        }}}catch(Exception e){log.warn("聚合会话 llm_calls 失败: {}",e.getMessage());}}
        out.put("rounds",calls);out.put("promptTokens",prompt);out.put("completionTokens",completion);out.put("estimatedCalls",estimated);out.put("cost",unpriced?null:knownCost);out.put("priced",!unpriced&&calls>0);out.put("byModel",byModel);return out;}

    public List<Map<String,Object>> groupConversation(String conversationId,String column){if(!List.of("phase","agent_id").contains(column))return List.of();List<Map<String,Object>> out=new ArrayList<>();
        String sql="SELECT COALESCE(l."+column+",'main'),COUNT(*),COALESCE(SUM(l.prompt_tokens),0),COALESCE(SUM(l.completion_tokens),0),COALESCE(SUM(l.estimated),0) FROM llm_calls l JOIN task_runs r ON r.id=l.task_run_id WHERE r.conversation_id=? GROUP BY COALESCE(l."+column+",'main') ORDER BY COUNT(*) DESC";
        synchronized(conn){try(PreparedStatement ps=conn.prepareStatement(sql)){ps.setString(1,conversationId);try(ResultSet rs=ps.executeQuery()){while(rs.next()){Map<String,Object> m=new LinkedHashMap<>();m.put("key",rs.getString(1));m.put("calls",rs.getInt(2));m.put("promptTokens",rs.getLong(3));m.put("completionTokens",rs.getLong(4));m.put("estimatedCalls",rs.getInt(5));out.add(m);}}}catch(Exception e){log.warn("聚合会话维度失败: {}",e.getMessage());}}return out;}

    public void deleteBefore(String iso) {
        synchronized (conn) { try (PreparedStatement ps=conn.prepareStatement("DELETE FROM llm_calls WHERE started_at < ?")) {
            ps.setString(1,iso); ps.executeUpdate();
        } catch(Exception e){ log.warn("清理 llm_calls 失败: {}",e.getMessage()); }}
    }

    private int countTable(String runId) {
        synchronized (conn) { try (PreparedStatement ps=conn.prepareStatement("SELECT COUNT(*) FROM llm_calls WHERE task_run_id=?")) {
            ps.setString(1,runId); try(ResultSet rs=ps.executeQuery()){ return rs.next()?rs.getInt(1):0; }
        } catch(Exception e){ return 0; }}
    }
    private static void setInt(PreparedStatement ps,int i,Integer v)throws Exception{if(v==null)ps.setNull(i,Types.INTEGER);else ps.setInt(i,v);}
    private static void setLong(PreparedStatement ps,int i,Long v)throws Exception{if(v==null)ps.setNull(i,Types.INTEGER);else ps.setLong(i,v);}
    private static Long nullableLong(ResultSet rs,int i)throws Exception{long v=rs.getLong(i);return rs.wasNull()?null:v;}
    private static Double nullableDouble(ResultSet rs,int i)throws Exception{double v=rs.getDouble(i);return rs.wasNull()?null:v;}
    private static String truncate(String s,int n){return s==null?null:s.length()<=n?s:s.substring(0,n);}
}
