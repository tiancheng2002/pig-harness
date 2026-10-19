package com.example.harness.config;

import com.example.harness.core.memory.EpisodicStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;

/**
 * 启动日志（F1 验收）：打印绑定的项目根路径与 LLM 配置状态。
 */
@Component
public class StartupReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReporter.class);

    private final RootResolver rootResolver;
    private final HarnessProperties props;
    private final boolean llmMockMode;

    public StartupReporter(RootResolver rootResolver, HarnessProperties props, boolean llmMockMode) {
        this.rootResolver = rootResolver;
        this.props = props;
        this.llmMockMode = llmMockMode;
    }

    @Override
    public void run(ApplicationArguments args) {
        String apiRoot = rootResolver.defaultRoot().toString();
        log.info("=====================================================");
        log.info("  Harness M1 启动完成");
        log.info("  Web UI        : http://localhost:{}", 8080);
        log.info("  沙箱项目根    : {}", apiRoot);
        log.info("  轨迹数据库    : {}",
                Paths.get(props.getDbPath()).toAbsolutePath().normalize());
        if (llmMockMode) {
            log.warn("  LLM 状态      : 未配置 API Key（HARNESS_LLM_API_KEY），当前为 Mock 演示模式");
        } else {
            log.info("  LLM 状态      : 已配置 {} | model={}",
                    props.getLlm().getBaseUrl(), props.getLlm().getModel());
        }
        log.info("  可通过 --harness.root=<路径> 切换沙箱根目录");
        log.info("=====================================================");
    }
}
