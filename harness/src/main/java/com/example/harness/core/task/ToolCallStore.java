package com.example.harness.core.task;

import com.example.harness.core.memory.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolCallStore {
    private static final Logger log=LoggerFactory.getLogger(ToolCallStore.class);
    private final Connection conn;
    public ToolCallStore(Db db){this.conn=db.connection();}

    public void insert(String runId,String callId,String agentId,String name,String target,
                       boolean mutating,Instant startedAt,long durationMs,String status,String error){
        synchronized(conn){try(PreparedStatement ps=conn.prepareStatement("""
                INSERT INTO tool_calls(task_run_id,call_id,agent_id,name,target,mutating,
                started_at,duration_ms,status,error_summary) VALUES(?,?,?,?,?,?,?,?,?,?)
                """)){
            ps.setString(1,runId);ps.setString(2,callId);ps.setString(3,agentId);ps.setString(4,name);
            ps.setString(5,truncate(target,300));ps.setInt(6,mutating?1:0);ps.setString(7,startedAt.toString());
            ps.setLong(8,durationMs);ps.setString(9,status);ps.setString(10,truncate(error,500));ps.executeUpdate();
        }catch(Exception e){log.warn("写入 tool_calls 失败: {}",e.getMessage());}}
    }

    public List<Map<String,Object>> list(String runId,int limit){List<Map<String,Object>> out=new ArrayList<>();
        synchronized(conn){try(PreparedStatement ps=conn.prepareStatement("""
                SELECT id,call_id,agent_id,name,target,mutating,started_at,duration_ms,status,error_summary
                FROM tool_calls WHERE task_run_id=? ORDER BY id LIMIT ?
                """)){ps.setString(1,runId);ps.setInt(2,limit);try(ResultSet rs=ps.executeQuery()){while(rs.next()){
            Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getLong(1));m.put("callId",rs.getString(2));
            m.put("agentId",rs.getString(3));m.put("name",rs.getString(4));m.put("target",rs.getString(5));
            m.put("mutating",rs.getInt(6)==1);m.put("startedAt",rs.getString(7));m.put("durationMs",rs.getLong(8));
            m.put("status",rs.getString(9));m.put("errorSummary",rs.getString(10));out.add(m);
        }}}catch(Exception e){log.warn("读取 tool_calls 失败: {}",e.getMessage());}}return out;}

    public int count(String runId){synchronized(conn){try(PreparedStatement ps=conn.prepareStatement("SELECT COUNT(*) FROM tool_calls WHERE task_run_id=?")){ps.setString(1,runId);try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getInt(1):0;}}catch(Exception e){return 0;}}}
    public void deleteBefore(String iso){synchronized(conn){try(PreparedStatement ps=conn.prepareStatement("DELETE FROM tool_calls WHERE started_at < ?")){ps.setString(1,iso);ps.executeUpdate();}catch(Exception e){log.warn("清理 tool_calls 失败: {}",e.getMessage());}}}
    private static String truncate(String s,int n){return s==null?null:s.length()<=n?s:s.substring(0,n);}
}
