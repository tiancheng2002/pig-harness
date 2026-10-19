package com.example.harness.core.agents;

import com.example.harness.core.ToolRegistry;
import com.example.harness.core.memory.AgentProfileStore;
import com.example.harness.model.AgentProfile;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 子智能体库：启动时播种内置档案（缺则插入，用户删除后重启自动恢复；禁用状态存 DB 不重置），
 * 并把 spawnAgent 工具 spec 注册进 ToolRegistry（真正的派发逻辑在 AgentLoop 特判）。
 */
@Component
public class AgentLibrary {

    private static final Logger log = LoggerFactory.getLogger(AgentLibrary.class);

    public static final String EXPLORER_NAME = "代码探索";
    public static final String EXPLORER_ID = "builtin-explore";

    private final AgentProfileStore store;
    private final ToolRegistry toolRegistry;

    public AgentLibrary(AgentProfileStore store, ToolRegistry toolRegistry) {
        this.store = store;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void init() {
        seedBuiltin();
        upgradeExplorerWithCodeSearch();
        toolRegistry.register(new SpawnAgentTool());
        log.info("子智能体库就绪：共 {} 个档案（spawnAgent 工具已注册）", store.list().size());
    }

    /** P1 一次性迁移：既有「代码探索」档案白名单补上 codeSearch（幂等；用户手动改过也仅在缺项时追加）。 */
    private void upgradeExplorerWithCodeSearch() {
        var existing = store.findByName(EXPLORER_NAME);
        if (existing.isEmpty()) return;
        AgentProfile p = existing.get();
        if (p.tools() != null && p.tools().contains("codeSearch")) return;
        java.util.List<String> updated = new java.util.ArrayList<>(p.tools() == null ? java.util.List.of() : p.tools());
        updated.add("codeSearch");
        store.upsert(new AgentProfile(p.id(), p.name(), p.description(), p.prompt(),
                updated, p.maxSteps(), p.enabled(), p.origin(), null, null));
        log.info("「{}」白名单已追加 codeSearch", EXPLORER_NAME);
    }

    /** 内置「代码探索」：只读调研 Agent（grep/listFiles/fileRead 白名单），主体职责见 prompt。 */
    private void seedBuiltin() {
        if (store.findByName(EXPLORER_NAME).isPresent()) {
            return;
        }
        store.upsert(new AgentProfile(
                EXPLORER_ID,
                EXPLORER_NAME,
                "派我进入项目只读检索与阅读代码，输出调研报告（关键文件、入口与调用关系、"
                        + "建议改动点），绝不修改任何文件。动手改代码前对项目不熟悉时先派我。",
                """
                        [子智能体:代码探索] 你是只读代码调研专家。职责：在当前项目中定位、阅读并理解与任务相关的代码，输出调研报告；绝不修改任何文件。
                        工作方式：
                        1. 先用 listFiles 浏览目录结构、用 grep 按任务关键词/标识符搜索，定位相关文件；
                        2. 用 fileRead 精读关键文件（入口、配置、调用链）；
                        3. 输出调研报告：相关文件清单（路径 + 一句话职责）、关键类/函数及其关系、针对任务的建议实现路径、风险点。简体中文，尽量精炼。
                        限制：只允许使用 fileRead / listFiles / grep 等只读工具，不得写入或删除任何文件；不要向用户提问。""",
                List.of("fileRead", "listFiles", "grep"),
                10,
                true,
                AgentProfile.ORIGIN_BUILTIN,
                null,
                null));
        log.info("播种内置子智能体: {}", EXPLORER_NAME);
    }
}
