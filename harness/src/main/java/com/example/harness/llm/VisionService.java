package com.example.harness.llm;

import com.example.harness.config.HarnessProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 视觉模型服务（图片理解）：把用户随消息粘贴的图片（data URL）交给 OpenAI 兼容视觉模型转述为文字，
 * 描述随 prompt 注入后续链路（CHAT/TASK/技能），使纯文本主模型也能"看懂"图片。
 * 仅在 application.yml 的 harness.vision 配置（单模型，切换=改配置重启）：
 * model 留空 = 未启用（Mock 主模型时返回演示文案，真实模式返回配置提示）；调用失败返回错误说明，绝不抛出。
 */
@Component
public class VisionService {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);
    /** data URL 最大长度（约 4.5MB 图片），超长直接拒绝，防请求体爆炸 */
    private static final int MAX_DATA_URL = 6_000_000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String SYSTEM_HINT = """
            请详细描述这张图片的内容，供 AI 助手在看不到原图的情况下回答用户问题。要求：
            1. 界面/网页截图：描述页面结构、关键元素与可见文字，报错信息和代码原样转述；
            2. 代码/文档/终端截图：完整转录其中的文字、代码与命令输出；
            3. 图表/照片：描述数据、趋势与要点。
            用中文输出，控制在 600 字以内，不要添加图片中不存在的信息。""";

    private final HarnessProperties props;
    private final com.example.harness.core.task.LlmCallTracker callTracker;
    private final ObjectMapper om = new ObjectMapper();

    public VisionService(HarnessProperties props,
                         com.example.harness.core.task.LlmCallTracker callTracker) {
        this.props = props;
        this.callTracker = callTracker;
    }

    /** 是否已配置视觉模型（/api/config 与设置页展示用）。 */
    public boolean enabled() {
        String model = props.getVision().getModel();
        return model != null && !model.isBlank();
    }

    /** 实际生效的端点（vision.base-url 留空时继承 llm.base-url）。 */
    public String effectiveBaseUrl() {
        String b = props.getVision().getBaseUrl();
        return b == null || b.isBlank() ? props.getLlm().getBaseUrl() : b.trim();
    }

    /** 实际生效的 Key（vision.api-key 留空时继承 llm.api-key）。 */
    private String effectiveApiKey() {
        String k = props.getVision().getApiKey();
        return k == null || k.isBlank() ? props.getLlm().getApiKey() : k.trim();
    }

    /**
     * 识别单张图片，永远返回非空文本（描述 / 未配置提示 / 失败原因），不抛异常。
     *
     * @param dataUrl    图片 data URL（data:image/png;base64,...）
     * @param userPrompt 用户随图输入的文字（作为识别上下文，可为空）
     */
    public String describe(String dataUrl, String userPrompt) {
        return describe(dataUrl, userPrompt, null);
    }

    public String describe(String dataUrl, String userPrompt,
                           com.example.harness.core.task.TaskRunContext runContext) {
        HarnessProperties.Vision cfg = props.getVision();
        String model = cfg.getModel();
        if (model == null || model.isBlank()) {
            // 未配置视觉模型：Mock 主模型给演示文案（本地链路可跑通），真实模式给配置指引
            if (props.getLlm().getApiKey() == null || props.getLlm().getApiKey().isBlank()) {
                return "（Mock 视觉描述）图片为一张用户界面截图：顶部有标题栏，中部为内容区域与若干按钮，"
                        + "底部有输入框。配置 harness.vision.model 后此处将输出真实识图结果。";
            }
            return "（未配置视觉模型，无法读取图片内容。请告知用户：在 application.yml 的 "
                    + "harness.vision.model 配置 OpenAI 兼容视觉模型并重启服务后，即可支持图片理解。）";
        }
        if (dataUrl == null || !dataUrl.startsWith("data:image/")) {
            return "（图片格式不支持：仅支持 data:image/* 格式）";
        }
        if (dataUrl.length() > MAX_DATA_URL) {
            return "（图片过大：单张图片需小于约 4MB，请压缩后重试）";
        }
        java.time.Instant at = java.time.Instant.now();
        long start = System.nanoTime();
        try {
            VisionResult result = callVision(model, dataUrl, userPrompt);
            boolean estimated = result.promptTokens() == null || result.completionTokens() == null;
            int promptTokens = estimated ? TokenEstimator.estimate(SYSTEM_HINT + "\n" + userPrompt) : result.promptTokens();
            int completionTokens = result.completionTokens() == null
                    ? TokenEstimator.estimate(result.text()) : result.completionTokens();
            callTracker.recordExternal(runContext, "vision", null, model, promptTokens,
                    completionTokens, estimated, at, elapsed(start), null, "success", null);
            return result.text();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callTracker.recordExternal(runContext, "vision", null, model, null, null,
                    false, at, elapsed(start), null, "error", e.getMessage());
            return "（视觉模型调用被中断）";
        } catch (Exception e) {
            log.warn("视觉模型调用失败: {}", e.getMessage());
            callTracker.recordExternal(runContext, "vision", null, model, null, null,
                    false, at, elapsed(start), null, "error", e.getMessage());
            return "（视觉模型调用失败：" + truncate(String.valueOf(e.getMessage()), 200) + "）";
        }
    }

    private VisionResult callVision(String model, String dataUrl, String userPrompt) throws Exception {
        String base = effectiveBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String endpoint = base + "/chat/completions";

        ObjectNode body = om.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 1024);
        ObjectNode msg = body.putArray("messages").addObject();
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", SYSTEM_HINT
                + (userPrompt == null || userPrompt.isBlank()
                        ? "" : "\n\n用户针对这张图片的问题/任务：" + truncate(userPrompt, 500)));
        ObjectNode imgPart = content.addObject();
        imgPart.put("type", "image_url");
        imgPart.putObject("image_url").put("url", dataUrl);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(Math.max(10, props.getVision().getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        om.writeValueAsString(body), StandardCharsets.UTF_8));
        String key = effectiveApiKey();
        if (key != null && !key.isBlank()) {
            builder.header("Authorization", "Bearer " + key);
        }

        HttpResponse<String> resp = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode()
                    + ": " + truncate(resp.body(), 300));
        }
        JsonNode root = om.readTree(resp.body());
        JsonNode contentNode = root
                .path("choices").path(0).path("message").path("content");
        String text = contentNode.isTextual() ? contentNode.asText() : "";
        if (text.isBlank()) {
            throw new IllegalStateException("视觉模型返回空内容");
        }
        JsonNode usage = root.path("usage");
        Integer prompt = usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asInt() : null;
        Integer completion = usage.path("completion_tokens").isNumber() ? usage.path("completion_tokens").asInt() : null;
        return new VisionResult(truncate(text.trim(), 4000), prompt, completion);
    }

    private record VisionResult(String text, Integer promptTokens, Integer completionTokens) {}
    private static long elapsed(long start) { return Math.max(0, (System.nanoTime() - start) / 1_000_000L); }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
