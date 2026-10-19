package com.example.harness.llm;

import java.util.List;

/**
 * 启发式 Token 估算器（无第三方依赖）。
 * 各家模型分词器不同（DeepSeek/通义均非 OpenAI BPE），精确计数不可得；
 * 这里按字符类别估算：CJK ≈ 0.6 token/字符（DeepSeek 官方口径），其余 ≈ 0.3 token/字符
 * （DeepSeek 官方「1 英文字符 ≈ 0.3 token」，代码/JSON 实际更高，取保守值），
 * 用于「发请求前」的预检与前端展示。真实 prompt_tokens 以 API usage 回传为准（调用方闭环校正）。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** 估算一段文本的 token 数。 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        return Math.max(1, Math.round(cjk * 0.6f + other * 0.3f));
    }

    /** 估算一次请求的消息列表总 token（每条消息 +8 结构开销：role/tool_call_id/JSON 包装；工具调用参数计入）。 */
    public static int estimateMessages(List<LlmMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return 0;
        int total = 0;
        for (LlmMessage m : msgs) {
            total += 8;
            total += estimate(m.content());
            if (m.toolCalls() != null) {
                for (LlmToolCall c : m.toolCalls()) {
                    total += 8 + estimate(c.name()) + estimate(c.arguments());
                }
            }
        }
        return total;
    }

    /** 粗粒度中日韩/全角字符判定（覆盖 CJK 统一表意、部首扩展、假名、注音、全角形式与 CJK 标点）。 */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF)   // 扩展 A
                || (c >= 0x3040 && c <= 0x30FF)   // 平假名/片假名
                || (c >= 0x3100 && c <= 0x312F)   // 注音符号
                || (c >= 0x3000 && c <= 0x303F)   // CJK 标点
                || (c >= 0xFF00 && c <= 0xFFEF);  // 全角形式
    }
}
