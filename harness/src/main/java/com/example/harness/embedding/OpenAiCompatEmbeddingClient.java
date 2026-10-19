package com.example.harness.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容 Embedding API 实现。
 * POST {base-url}/embeddings，从 data[0].embedding 读取 float[]。
 */
public class OpenAiCompatEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatEmbeddingClient.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper om = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiCompatEmbeddingClient(String baseUrl, String apiKey, String model) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://api.openai.com/v1" : baseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.endpoint = base + "/embeddings";
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null || model.isBlank() ? "text-embedding-3-small" : model;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("embedding 文本不能为空");
        }
        try {
            ObjectNode body = om.createObjectNode();
            body.put("model", model);
            body.put("input", text);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            om.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("Embedding HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body(), 500));
            }

            JsonNode data = om.readTree(resp.body()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("Embedding 返回空 data");
            }
            JsonNode embedding = data.get(0).path("embedding");
            if (!embedding.isArray()) {
                throw new IllegalStateException("Embedding 返回格式异常");
            }
            List<Float> values = new ArrayList<>();
            for (JsonNode v : embedding) {
                values.add(v.floatValue());
            }
            float[] out = new float[values.size()];
            for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 调用失败: " + e.getMessage(), e);
        }
    }

    /** P1：批量向量化——input 数组单次请求（上限 32/批），按 data[].index 回填保证顺序。 */
    @Override
    public java.util.List<float[]> embedBatch(java.util.List<String> texts) {
        java.util.List<float[]> out = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += 32) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + 32));
            try {
                ObjectNode body = om.createObjectNode();
                body.put("model", model);
                ArrayNode arr = body.putArray("input");
                batch.forEach(arr::add);

                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(120))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                om.writeValueAsString(body), StandardCharsets.UTF_8));
                if (!apiKey.isBlank()) {
                    builder.header("Authorization", "Bearer " + apiKey);
                }
                HttpResponse<String> resp = HTTP.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Embedding HTTP " + resp.statusCode()
                            + ": " + truncate(resp.body(), 300));
                }
                JsonNode data = om.readTree(resp.body()).path("data");
                if (!data.isArray() || data.size() != batch.size()) {
                    throw new IllegalStateException("Embedding 批量返回条数不符: "
                            + (data.isArray() ? data.size() : -1) + " != " + batch.size());
                }
                float[][] byIndex = new float[batch.size()][];
                for (JsonNode d : data) {
                    int idx = d.path("index").asInt(-1);
                    JsonNode embedding = d.path("embedding");
                    if (idx < 0 || idx >= batch.size() || !embedding.isArray()) {
                        throw new IllegalStateException("Embedding 批量返回格式异常");
                    }
                    float[] v = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) v[i] = embedding.get(i).floatValue();
                    byIndex[idx] = v;
                }
                for (float[] v : byIndex) {
                    if (v == null) throw new IllegalStateException("Embedding 批量返回缺项");
                    out.add(v);
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("Embedding 批量调用失败: " + e.getMessage(), e);
            }
        }
        return out;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
