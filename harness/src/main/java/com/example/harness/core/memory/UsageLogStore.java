package com.example.harness.core.memory;

import com.example.harness.config.HarnessProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1 成本观测：会话用量流水（usage_log 表）。
 * 每轮主泳道 LLM 调用一行（AgentLoop.emitUsage / ChatExecutor），子 Agent 泳道不计入
 * （与 conversations.last_prompt_tokens 同口径，防止子 Agent 小上下文带跳会话徽标）。
 * 聚合时按 harness.pricing 计价（缺价 0 → 只统计 token）。
 */
@Component
public class UsageLogStore {

    private static final Logger log = LoggerFactory.getLogger(UsageLogStore.class);

    private final Connection conn;
    private final HarnessProperties props;
    private final com.example.harness.core.task.LlmCallStore llmCallStore;

    public UsageLogStore(Db db, HarnessProperties props,
                         com.example.harness.core.task.LlmCallStore llmCallStore) {
        this.conn = db.connection();
        this.props = props;
        this.llmCallStore = llmCallStore;
    }

    public synchronized void insert(String conversationId, String model,
                                    Integer promptTokens, Integer completionTokens, boolean estimated) {
        if (conversationId == null || conversationId.isBlank()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO usage_log (conversation_id, model, prompt_tokens, completion_tokens, estimated, created_at) "
                        + "VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, conversationId);
            ps.setString(2, model == null || model.isBlank() ? "unknown" : model);
            ps.setInt(3, promptTokens == null ? 0 : promptTokens);
            ps.setInt(4, completionTokens == null ? 0 : completionTokens);
            ps.setInt(5, estimated ? 1 : 0);
            ps.setString(6, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("写入 usage_log 失败: {}", e.getMessage());
        }
    }

    /** 原始流水（按写入顺序，id 升序）：执行报告按轮次时间窗归属用量用。行：{model,promptTokens,completionTokens,estimated,createdAt}。 */
    public synchronized List<Map<String, Object>> listRows(String conversationId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT model, prompt_tokens, completion_tokens, estimated, created_at FROM usage_log "
                        + "WHERE conversation_id = ? ORDER BY id")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("model", rs.getString(1));
                    row.put("promptTokens", rs.getLong(2));
                    row.put("completionTokens", rs.getLong(3));
                    row.put("estimated", rs.getInt(4) == 1);
                    row.put("createdAt", rs.getString(5));
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            log.warn("读取 usage_log 失败: {}", e.getMessage());
        }
        return out;
    }

    public synchronized Map<String, Object> aggregate(String conversationId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> byModel = new ArrayList<>();
        long prompt = 0;
        long completion = 0;
        int rounds = 0;
        int estimatedCalls = 0;
        double cost = 0;
        boolean legacyUnpriced = false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT model, COUNT(*), COALESCE(SUM(prompt_tokens),0), COALESCE(SUM(completion_tokens),0), "
                        + "COALESCE(SUM(estimated),0) FROM usage_log WHERE conversation_id = ? GROUP BY model "
                        + "ORDER BY SUM(prompt_tokens) DESC")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String model = rs.getString(1);
                    int modelRounds = rs.getInt(2);
                    long p = rs.getLong(3);
                    long c = rs.getLong(4);
                    rounds += modelRounds;
                    prompt += p;
                    completion += c;
                    double modelCost = price(model, p, c);
                    if (!props.getPricing().containsKey(model)) legacyUnpriced = true;
                    cost += modelCost;
                    estimatedCalls += rs.getInt(5);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("model", model);
                    row.put("rounds", modelRounds);
                    row.put("promptTokens", p);
                    row.put("completionTokens", c);
                    row.put("estimatedRounds", rs.getInt(5));
                    row.put("cost", round4(modelCost));
                    byModel.add(row);
                }
            }
        } catch (SQLException e) {
            log.warn("聚合 usage_log 失败: {}", e.getMessage());
        }
        Map<String,Object> modern = llmCallStore.aggregateConversation(conversationId);
        rounds += ((Number) modern.getOrDefault("rounds",0)).intValue();
        prompt += ((Number) modern.getOrDefault("promptTokens",0)).longValue();
        completion += ((Number) modern.getOrDefault("completionTokens",0)).longValue();
        estimatedCalls += ((Number) modern.getOrDefault("estimatedCalls",0)).intValue();
        Object modernCost = modern.get("cost");
        if (modernCost instanceof Number n) cost += n.doubleValue();
        @SuppressWarnings("unchecked") List<Map<String,Object>> modernModels =
                (List<Map<String,Object>>) modern.getOrDefault("byModel", List.of());
        mergeModels(byModel, modernModels);
        out.put("rounds", rounds);
        out.put("promptTokens", prompt);
        out.put("completionTokens", completion);
        out.put("cost", round4(cost));
        out.put("estimatedCalls", estimatedCalls);
        boolean modernHasCalls = ((Number)modern.getOrDefault("rounds",0)).intValue() > 0;
        boolean modernPriced = !modernHasCalls || Boolean.TRUE.equals(modern.get("priced"));
        out.put("priced", rounds > 0 && !legacyUnpriced && modernPriced);
        out.put("byModel", byModel);
        out.put("byPhase", llmCallStore.groupConversation(conversationId, "phase"));
        out.put("byAgent", llmCallStore.groupConversation(conversationId, "agent_id"));
        return out;
    }

    private void mergeModels(List<Map<String,Object>> base,List<Map<String,Object>> extra){
        for(Map<String,Object> add:extra){String model=String.valueOf(add.get("model"));Map<String,Object> row=base.stream().filter(x->model.equals(String.valueOf(x.get("model")))).findFirst().orElse(null);
            if(row==null){base.add(new LinkedHashMap<>(add));continue;}
            for(String k:List.of("rounds","promptTokens","completionTokens","estimatedRounds"))row.put(k,((Number)row.getOrDefault(k,0)).longValue()+((Number)add.getOrDefault(k,0)).longValue());
            Object a=row.get("cost"),b=add.get("cost");row.put("cost",a instanceof Number x&&b instanceof Number y?round4(x.doubleValue()+y.doubleValue()):null);
        }
    }

    /** 按计价表算钱（$/1M）；缺价按 0 计。 */
    private double price(String model, long promptTokens, long completionTokens) {
        HarnessProperties.Price p = props.getPricing().get(model);
        if (p == null) return 0;
        return promptTokens / 1_000_000.0 * p.getInput()
                + completionTokens / 1_000_000.0 * p.getOutput();
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
