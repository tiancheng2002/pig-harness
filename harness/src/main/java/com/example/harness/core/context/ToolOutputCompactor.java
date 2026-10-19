package com.example.harness.core.context;

import com.example.harness.llm.LlmMessage;

import java.util.List;

/**
 * 任务内工具输出压缩（机械占位替换）：
 * 把较早轮次的 role=tool 消息 content 原位替换为「[已压缩] 前 200 字符 + 原长度」占位符，
 * 只保留最近 keepRounds 轮的完整输出。
 * 刻意不增删消息——OpenAI 协议要求 assistant.tool_calls 与 tool 消息按 id 配对，
 * 原位替换零协议风险；无需 LLM 参与，毫秒级完成。
 */
public final class ToolOutputCompactor {

    /** 占位符保留的原文前缀长度。 */
    private static final int HEAD_CHARS = 200;
    /** 低于该长度的工具输出不值得替换（占位符自身有开销，短输出替换后反而变长），原样保留。 */
    private static final int MIN_CHARS_TO_COMPACT = 300;

    private ToolOutputCompactor() {
    }

    /**
     * 原位压缩旧工具输出。
     * 「轮」= 一条携带 tool_calls 的 assistant 消息及其后续所有 tool 消息；从最新往旧数，
     * 保留最近 keepRounds 轮完整，其余轮的 tool 输出替换为占位符。
     *
     * @return 被替换掉的字符总数（0 表示无需压缩）
     */
    public static int compact(List<LlmMessage> msgs, int keepRounds) {
        if (msgs == null || msgs.isEmpty() || keepRounds < 0) return 0;
        // 从后向前找 tool 消息的轮次序号：assistant(toolCalls) 开启新一轮，tool 消息归属当前轮
        int round = -1;
        int replaced = 0;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            LlmMessage m = msgs.get(i);
            if ("tool".equals(m.role())) {
                if (round < 0) round = 0; // 顶部孤立的 tool 消息（理论不出现）按第 0 轮处理
                if (round >= keepRounds && m.content() != null
                        && m.content().length() >= MIN_CHARS_TO_COMPACT
                        && !m.content().startsWith("[已压缩]")) {
                    String origin = m.content();
                    String head = origin.length() > HEAD_CHARS
                            ? origin.substring(0, HEAD_CHARS) : origin;
                    msgs.set(i, LlmMessage.tool(m.toolCallId(),
                            "[已压缩] " + head.replace('\n', ' ')
                                    + "…（原 " + origin.length() + " 字符已省略，如需详情请重新读取）"));
                    replaced += origin.length();
                }
            } else if ("assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                round++;
            }
        }
        return replaced;
    }

    /**
     * 紧急兜底：压缩后仍超限时，丢弃 system 之后、最后一条 user 提示之前的最早历史消息
     * （至少保留最后 6 条），防止对话级历史把任务内上下文顶爆。返回是否发生了裁剪。
     */
    public static boolean hardTrimHistory(List<LlmMessage> msgs) {
        if (msgs == null || msgs.size() <= 7) return false;
        int lastUser = -1;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("user".equals(msgs.get(i).role())) {
                lastUser = i;
                break;
            }
        }
        // 只裁 system(0) 与任务 user 提示之间的历史，任务提示本体绝不动
        if (lastUser <= 1) return false;
        int keepFrom = Math.max(1, msgs.size() - 6);
        if (keepFrom >= lastUser) keepFrom = lastUser; // 至少把旧历史裁到只剩任务提示前无内容
        if (keepFrom <= 1) return false;
        msgs.subList(1, keepFrom).clear();
        return true;
    }
}
