package com.example.harness.core.multi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享黑板 v2（M4-F4）：线程安全的产物登记与路径占坑，仅供观察与冲突兜底。
 * 不承担运行时数据传输、无任何阻塞 get——数据依赖在计划期（拆分 DAG）解决。
 * 每次多 Agent 执行新建一个实例，不跨任务复用。
 */
public final class SharedBlackboard {

    /** 单条登记值的截断上限。 */
    private static final int VALUE_CAP = 2000;

    public record Entry(String key, String value, String owner) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** 登记产物索引（值截断 ≤2000 字符）。 */
    public void record(String key, String value, String owner) {
        entries.put(key, new Entry(key, truncate(value), owner));
    }

    /**
     * 原子占坑：成功返回 null；已被占则返回现 owner（不覆盖）。
     * owner 相同视为已占坑成功（幂等），返回 null。
     */
    public String claim(String key, String owner) {
        Entry fresh = new Entry(key, "", owner);
        Entry prev = entries.putIfAbsent(key, fresh);
        if (prev == null || owner != null && owner.equals(prev.owner())) {
            return null;
        }
        return prev.owner();
    }

    /** 供聚合与 UI 展示。 */
    public List<Entry> snapshot() {
        List<Entry> list = new ArrayList<>(entries.values());
        list.sort((a, b) -> a.key().compareTo(b.key()));
        return list;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= VALUE_CAP ? s : s.substring(0, VALUE_CAP) + "…（已截断）";
    }
}
