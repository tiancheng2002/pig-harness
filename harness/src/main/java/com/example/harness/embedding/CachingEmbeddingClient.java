package com.example.harness.embedding;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EmbeddingClient 内存缓存装饰器：相同文本+模型只请求一次 API。
 */
public class CachingEmbeddingClient implements EmbeddingClient {

    private final EmbeddingClient delegate;
    private final ConcurrentHashMap<String, float[]> cache = new ConcurrentHashMap<>();

    public CachingEmbeddingClient(EmbeddingClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public float[] embed(String text) {
        String key = text + "|" + System.identityHashCode(delegate);
        float[] cached = cache.get(key);
        if (cached != null) {
            return Arrays.copyOf(cached, cached.length);
        }
        float[] vector = delegate.embed(text);
        cache.put(key, Arrays.copyOf(vector, vector.length));
        return vector;
    }

    public void clear() {
        cache.clear();
    }
}
