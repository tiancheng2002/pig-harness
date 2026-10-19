package com.example.harness.web;

import com.example.harness.config.HarnessProperties;
import com.example.harness.config.RootResolver;
import com.example.harness.core.TaskDispatcher;
import com.example.harness.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 任务入口（F2）：POST /api/task，SSE 流式返回
 * plan / tool_call / reflect / file / done 事件（见 M1 开发文档 §4.1）。
 */
@RestController
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    public record TaskRequest(String prompt, String root, String modelId, Boolean deepThink) {
    }

    private final TaskDispatcher taskDispatcher;
    private final RootResolver rootResolver;
    private final HarnessProperties props;
    private final boolean llmMockMode;

    public TaskController(TaskDispatcher taskDispatcher,
                          RootResolver rootResolver,
                          HarnessProperties props,
                          boolean llmMockMode) {
        this.taskDispatcher = taskDispatcher;
        this.rootResolver = rootResolver;
        this.props = props;
        this.llmMockMode = llmMockMode;
    }

    @PostMapping(value = "/api/task", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createTask(@RequestBody TaskRequest req) {
        if (req.prompt() == null || req.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        SseEmitter emitter = new com.example.harness.core.stream.SafeSseEmitter(600_000L);
        Task task = new Task(
                UUID.randomUUID().toString(),
                req.prompt().trim(),
                req.root(),
                null,
                LocalDateTime.now(),
                req.modelId(),
                Boolean.TRUE.equals(req.deepThink()));
        log.info("[{}] 新任务: {}（root 覆盖: {}）", task.id(), task.prompt(),
                task.root() == null || task.root().isBlank() ? "无" : task.root());
        Thread.ofVirtual().name("agent-" + task.id()).start(() -> taskDispatcher.run(task, emitter));
        return emitter;
    }

    /** 前端状态栏：沙箱根 / LLM 配置 / 运行模式 / 视觉模型状态。 */
    @GetMapping("/api/config")
    public Map<String, Object> config() {
        String visionModel = props.getVision().getModel();
        boolean visionEnabled = visionModel != null && !visionModel.isBlank();
        String visionBase = props.getVision().getBaseUrl();
        return Map.of(
                "root", rootResolver.defaultRoot().toString(),
                "model", props.getLlm().getModel(),
                "baseUrl", props.getLlm().getBaseUrl(),
                "mockMode", llmMockMode,
                "maxSteps", props.getMaxSteps(),
                "vision", Map.of(
                        "enabled", visionEnabled,
                        "model", visionEnabled ? visionModel : "",
                        "baseUrl", visionBase == null || visionBase.isBlank()
                                ? props.getLlm().getBaseUrl() : visionBase));
    }
}
