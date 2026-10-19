package com.example.harness.web;

import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.task.LlmCallStore;
import com.example.harness.core.task.TaskRunStore;
import com.example.harness.core.task.ToolCallStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class TaskRunController {
    private final TaskRunStore runs;private final LlmCallStore llm;private final ToolCallStore tools;private final ConversationStore conversations;private final ObjectMapper om;
    public TaskRunController(TaskRunStore runs,LlmCallStore llm,ToolCallStore tools,ConversationStore conversations,ObjectMapper om){this.runs=runs;this.llm=llm;this.tools=tools;this.conversations=conversations;this.om=om;}

    @GetMapping("/api/task-runs/{runId}")
    public Map<String,Object> get(@PathVariable String runId){return clean(runs.get(runId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"TaskRun 不存在")));}

    @GetMapping("/api/task-runs/{runId}/metrics")
    public Map<String,Object> metrics(@PathVariable String runId,@RequestParam(defaultValue="200") int limit){Map<String,Object> run=runs.get(runId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"TaskRun 不存在"));int cap=Math.max(1,Math.min(500,limit));
        List<Map<String,Object>> lc=llm.list(runId,cap),tc=tools.list(runId,cap).stream().map(this::cleanTool).toList();Map<String,Object> out=new LinkedHashMap<>();out.put("run",clean(run));out.put("byModel",llm.group(runId,"model"));out.put("byPhase",llm.group(runId,"phase"));out.put("byAgent",llm.group(runId,"agent_id"));out.put("llmCalls",lc);out.put("toolCalls",tc);out.put("llmCallsTruncated",llm.count(runId)>lc.size());out.put("toolCallsTruncated",tools.count(runId)>tc.size());return out;}

    @GetMapping("/api/conversations/{conversationId}/runs")
    public List<Map<String,Object>> conversationRuns(@PathVariable String conversationId,@RequestParam(required=false) Long messageId){if(!conversations.exists(conversationId))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在");return runs.listConversation(conversationId,messageId).stream().map(this::clean).toList();}

    private Map<String,Object> clean(Map<String,Object> row){Map<String,Object> out=new LinkedHashMap<>(row);Object raw=out.remove("outcomeJson");if(raw instanceof String s&&!s.isBlank()){try{out.put("outcome",sanitizeOutcome(om.readTree(s)));}catch(Exception ignore){out.put("outcome",null);}}else out.put("outcome",null);return out;}
    private com.fasterxml.jackson.databind.JsonNode sanitizeOutcome(com.fasterxml.jackson.databind.JsonNode node){if(!node.isObject())return node;com.fasterxml.jackson.databind.node.ObjectNode copy=(com.fasterxml.jackson.databind.node.ObjectNode)node.deepCopy();com.fasterxml.jackson.databind.JsonNode files=copy.get("changedFiles");if(files!=null&&files.isArray())for(com.fasterxml.jackson.databind.JsonNode file:files)if(file.isObject()){com.fasterxml.jackson.databind.node.ObjectNode f=(com.fasterxml.jackson.databind.node.ObjectNode)file;String p=f.path("path").asText();if(p.matches("^[A-Za-z]:[\\\\/].*")||p.startsWith("/")||p.startsWith("\\\\\\\\")){String normalized=p.replace("\\", "/");int slash=normalized.lastIndexOf('/');f.put("path","<absolute>/"+(slash>=0?normalized.substring(slash+1):normalized));}}return copy;}
    private Map<String,Object> cleanTool(Map<String,Object> row){Map<String,Object> out=new LinkedHashMap<>(row);Object value=out.get("target");if(value instanceof String s&&isAbsolute(s)){try{out.put("target","<absolute>/"+java.nio.file.Path.of(s).getFileName());}catch(Exception ignored){out.put("target","<absolute path>");}}return out;}
    private boolean isAbsolute(String s){return s.matches("^[A-Za-z]:[\\\\/].*")||s.startsWith("/")||s.startsWith("\\\\\\\\");}
}
