package com.example.harness.core.memory;

import com.example.harness.model.PlanStep;
import com.example.harness.model.Task;
import com.example.harness.model.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作记忆（F9）：单次任务上下文（目标/计划/已执行步骤/变量/产出文件），
 * Loop 各步共享同一实例，支持步骤间变量传递（如 fileWrite 返回的 path）。
 */
public class WorkingMemory {

    private final Task task;
    private volatile List<PlanStep> plan = List.of();
    private final List<ToolCall> steps = new CopyOnWriteArrayList<>();
    private final Map<String, String> variables = new ConcurrentHashMap<>();
    private final List<String> files = new CopyOnWriteArrayList<>();

    public WorkingMemory(Task task) {
        this.task = task;
    }

    public Task task() {
        return task;
    }

    public void setPlan(List<PlanStep> plan) {
        this.plan = List.copyOf(plan);
    }

    public List<PlanStep> plan() {
        return plan;
    }

    public void addStep(ToolCall call) {
        steps.add(call);
    }

    public List<ToolCall> steps() {
        return new ArrayList<>(steps);
    }

    public void putVariable(String key, String value) {
        if (key != null && value != null) variables.put(key, value);
    }

    public String getVariable(String key) {
        return variables.get(key);
    }

    public Map<String, String> variables() {
        return Map.copyOf(variables);
    }

    public void addFile(String file) {
        if (file != null) files.add(file);
    }

    public List<String> files() {
        return new ArrayList<>(files);
    }

    /** 将字符串中的 {{var}} 占位符替换为已记录变量。 */
    public String substitute(String text) {
        if (text == null) return null;
        String out = text;
        for (Map.Entry<String, String> e : variables.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out;
    }
}
