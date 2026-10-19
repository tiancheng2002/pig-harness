package com.example.harness.core;

import com.example.harness.config.HarnessProperties;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 会话模式路由：区分「闲聊/问答（CHAT）」与「需要执行工具的任务（TASK）」。
 * - 快路径：寒暄/自我介绍类短句正则直判 CHAT，零 LLM 成本
 * - 慢路径：一次轻量 LLM 分类，失败降级 TASK（安全默认）
 */
@Component
public class ChatModeRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatModeRouter.class);

    public enum Mode { CHAT, TASK }

    /** 寒暄/身份类短句：整体即一句话、无任务语义，直接 CHAT。 */
    private static final Pattern GREETING = Pattern.compile(
            "^(你好|您好|hi|hello|hey|嗨|哈喽|halo|在吗|在么|在不在|早上好|上午好|中午好|下午好|晚上好|晚安|"
                    + "谢谢|多谢|感谢|辛苦了|麻烦了|再见|拜拜|晚安安|你是谁|你是|你叫什么|你能做什么|你能干什么|"
                    + "你会什么|介绍下自己|介绍一下你自己|test|测试)[!！?？。.~～\\s。,,，呀啊呐咯喽哦噢喔吖]*$",
            Pattern.CASE_INSENSITIVE);

    /** 短句开头的寒暄/礼貌引导词（你好，/请问/问一下…），剥离后剩余部分再做身份问句匹配。 */
    private static final Pattern CHAT_LEADIN = Pattern.compile(
            "^(你好|您好|hi|hello|hey|嗨|哈喽|halo|请问|请问下|请问一下|问下|问一下|想问下|想问一下)"
                    + "[!！?？。.~～\\s,,，]*",
            Pattern.CASE_INSENSITIVE);

    /**
     * 身份/模型类问句（你是什么模型 / 你用的什么大模型 / 你是ai吗 等）：
     * 问的是助手自身，无任何任务语义，直接 CHAT；首字符限定 你/您 防止误伤含「模型」等词的任务句。
     */
    private static final Pattern IDENTITY_Q = Pattern.compile(
            "^[你您](是|用|用的|使用|用的是|跑的|运行|加载)?(的)?(什么|啥|哪个|哪一个)?"
                    + "(模型|大模型|ai|人工智能|机器人|llm|gpt|chatgpt|deepseek|claude|gemini|通义|文心|kimi|豆包|版本|名字|名称)"
                    + "(吗|么|呢)?[!！?？。.~～\\s呀啊呐咯喽哦噢喔吖的]*$",
            Pattern.CASE_INSENSITIVE);

    private final ChatClient chatClient;
    private final HarnessProperties props;

    public ChatModeRouter(ChatClient chatClient, HarnessProperties props) {
        this.chatClient = chatClient;
        this.props = props;
    }

    public Mode route(String prompt) {
        return route(prompt, java.util.List.of());
    }

    /** 带最近对话上下文的路由：短指令（如「继续」）依赖上文才能正确判型。 */
    public Mode route(String prompt, List<LlmMessage> history) {
        return route(prompt, history, chatClient);
    }

    /** P4：允许调用方传入带 phase/run 归因的客户端。 */
    public Mode route(String prompt, List<LlmMessage> history, ChatClient routedClient) {
        if (!props.isChatRouter()) return Mode.TASK;
        if (prompt == null || prompt.isBlank()) return Mode.CHAT;
        String trimmed = prompt.trim();
        if (trimmed.length() <= 24) {
            if (GREETING.matcher(trimmed).matches()) {
                log.info("模式路由（快路径）: CHAT");
                return Mode.CHAT;
            }
            // 「你好，请问你是什么模型」这类寒暄开头的身份问句：循环剥掉引导词后命中同样直判 CHAT，
            // 不进 LLM 分类；IDENTITY_Q 全锚定匹配兜底，剥多了也只会回落 LLM 分类，不会误伤任务句
            String body = trimmed;
            for (int i = 0; i < 3 && !body.isEmpty(); i++) {
                String stripped = CHAT_LEADIN.matcher(body).replaceFirst("").trim();
                if (stripped.equals(body)) break;
                body = stripped;
            }
            if (!body.isEmpty() && IDENTITY_Q.matcher(body).matches()) {
                log.info("模式路由（快路径）: CHAT（身份/模型问句）");
                return Mode.CHAT;
            }
        }
        return classify(prompt, history, routedClient);
    }

    private Mode classify(String prompt, List<LlmMessage> history, ChatClient routedClient) {
        StringBuilder context = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            context.append("[最近对话]（当前输入很可能指代上文，务必结合判断）\n");
            int from = Math.max(0, history.size() - 6);
            for (LlmMessage m : history.subList(from, history.size())) {
                String c = m.content() == null ? "" : m.content().replace('\n', ' ');
                context.append(m.role().equals("user") ? "用户: " : "助手: ")
                        .append(c, 0, Math.min(c.length(), 150)).append('\n');
            }
        }
        String sys = """
                [ROLE=CLASSIFIER] 你是意图分类器。判断用户的当前输入属于哪一类：
                - TASK：需要创建/修改/读取/删除文件、生成文档、产出代码文件等会操作本机沙箱的任务
                - CHAT：闲聊寒暄、知识问答、概念解释、纯文本咨询，不需要操作任何文件
                注意：询问助手自身的身份、模型、能力（你是谁、你是什么模型、你能做什么）一律属于 CHAT。
                注意：如果「最近对话」是任务执行（或被中断的任务），当前输入是「继续」「好的继续」「接着做」
                这类承接上文继续执行的短语，应判为 TASK。
                仅输出 CHAT 或 TASK 两个词之一，不要输出其他内容。
                示例：
                用户: 帮我创建一个登录页面 → TASK
                用户: Java 里 HashMap 和 Hashtable 有什么区别？ → CHAT
                用户: 生成一份 Word 周报 → TASK
                用户: 看看 workspace 里有什么文件 → TASK
                用户: 把 readme.md 的标题改成中文 → TASK
                用户: 你好呀 → CHAT
                用户: 你好，你是什么模型 → CHAT
                用户: 你用的什么大模型 → CHAT
                用户: 用一句话解释什么是闭包 → CHAT
                用户: （上文：正在生成 HTML 页面）继续 → TASK
                """;
        List<LlmMessage> msgs = new ArrayList<>();
        msgs.add(LlmMessage.system(sys));
        if (context.length() > 0) {
            msgs.add(LlmMessage.user(context.toString().strip()));
        }
        msgs.add(LlmMessage.user("[当前输入]\n" + prompt));
        // 瞬时失败重试一次：分类偶发抖动就把闲聊降级成任务规划，用户会看到莫名其妙的执行计划
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Mode m = parseMode(routedClient.chat(msgs, List.of()).content());
                if (m != null) return m;
                break; // 输出无法解析，重试同样输入意义不大，直接降级
            } catch (Exception e) {
                if (attempt == 1) log.warn("模式分类 LLM 调用失败，降级 TASK: {}", e.getMessage());
            }
        }
        log.warn("模式分类无法解析，降级 TASK");
        return Mode.TASK;
    }

    /** 解析分类输出：取 CHAT/TASK 中先出现者，避免「CHAT……不是TASK」这类长输出被误判。 */
    private Mode parseMode(String raw) {
        String out = (raw == null ? "" : raw).trim().toUpperCase();
        int t = out.indexOf("TASK");
        int c = out.indexOf("CHAT");
        if (t < 0 && c < 0) return null;
        return t >= 0 && (c < 0 || t < c) ? Mode.TASK : Mode.CHAT;
    }

    /** 供 ChatExecutor 组装上下文用的 system 提示（含 Mock 识别标记）。 */
    public static List<LlmMessage> chatSystemPrompt() {
        List<LlmMessage> msgs = new ArrayList<>();
        msgs.add(LlmMessage.system("""
                [MODE=CHAT] 你是运行在本机的 Harness 助手（当前为对话模式）。回答要求：
                1. 用简体中文、Markdown 格式，简洁清晰、直击要点。
                2. 本模式没有工具可用，不要声称要创建/修改文件。
                3. 如果用户的需求需要操作文件（创建页面、生成文档、改代码等），
                   简要说明后请用户直接下达任务式指令，系统会进入任务执行模式。
                """));
        return msgs;
    }
}
