package com.example.harness.core.knowledge;

import com.example.harness.config.HarnessProperties;
import com.example.harness.core.ModelRegistry;
import com.example.harness.core.memory.ConversationStore;
import com.example.harness.core.memory.KnowledgeStore;
import com.example.harness.llm.ChatClient;
import com.example.harness.llm.ChatResult;
import com.example.harness.llm.LlmMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 只从最新用户输入提出有原文证据的长期知识候选；候选需经审核才会进入提示词。
 */
@Component
public class KnowledgeExtractor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtractor.class);

    private static final Set<String> CATEGORIES = Set.of("用户偏好", "项目事实", "工作习惯");
    private static final int MAX_MSG_CHARS = 1500;

    private final ChatClient chatClient; // 默认客户端（非思考）
    private final ConversationStore conversationStore;
    private final KnowledgeStore knowledgeStore;
    private final HarnessProperties props;
    private final com.example.harness.core.task.LlmCallTracker llmCallTracker;
    private final ObjectMapper om = new ObjectMapper();

    public KnowledgeExtractor(ChatClient chatClient,
                              ConversationStore conversationStore,
                              KnowledgeStore knowledgeStore,
                              HarnessProperties props,
                              com.example.harness.core.task.LlmCallTracker llmCallTracker) {
        this.chatClient = chatClient;
        this.conversationStore = conversationStore;
        this.knowledgeStore = knowledgeStore;
        this.props = props;
        this.llmCallTracker = llmCallTracker;
    }

    /** 会话结束后异步提取（fire-and-forget，失败只记日志）。 */
    public void extractAsync(String conversationId) {
        if (!props.getKnowledge().isAutoExtract()) return;
        Thread.ofVirtual().name("knowledge-extract").start(() -> {
            try {
                int added = extractNow(conversationId);
                if (added > 0) {
                    log.info("[knowledge] 会话 {} 提出 {} 条待审核知识", conversationId, added);
                }
            } catch (Exception e) {
                log.warn("[knowledge] 记忆提取失败（忽略）: {}", e.getMessage());
            }
        });
    }

    /** 同步提取，返回新增待审核候选数。Mock 模式或无对话时返回 0。 */
    public int extractNow(String conversationId) {
        if (props.getLlm().getApiKey() == null || props.getLlm().getApiKey().isBlank()) {
            return 0; // Mock 模式不产生记忆
        }
        var userMessages = conversationStore.messages(conversationId).stream()
                .filter(m -> "user".equals(m.role()) && m.content() != null && !m.content().isBlank())
                .toList();
        if (userMessages.isEmpty()) return 0;
        String latest = userMessages.get(userMessages.size() - 1).content();
        if (latest.length() > MAX_MSG_CHARS) latest = latest.substring(0, MAX_MSG_CHARS);
        if (latest.trim().length() < 4) return 0;
        StringBuilder context = new StringBuilder();
        for (int i = Math.max(0, userMessages.size() - 5); i < userMessages.size() - 1; i++) {
            String text = userMessages.get(i).content();
            if (text.length() > 300) text = text.substring(0, 300);
            context.append("- ").append(text.replace('\n', ' ')).append('\n');
        }
        String projectId = conversationStore.get(conversationId).map(c -> c.projectId()).orElse(null);

        String sys = """
                你负责提出长期记忆候选，只能从“本轮用户输入”提取用户亲自表达的稳定偏好、工作习惯或长期项目约定。
                近期输入仅帮助理解语境，不能作为本轮候选的证据；不要从助手回复、任务结果或推测中归纳用户偏好。
                一次性任务、代码现状、文件路径、接口位置、临时计划、敏感信息和密钥都不应保存。
                项目事实只限长期约定，不要记录可从项目文件重新读到的细节。
                最多 2 条；不确定或没有合格信息就输出 []。
                仅输出 JSON 数组，每项格式：
                {"category":"用户偏好|项目事实|工作习惯","key":"简短主题","content":"一句话、可用于将来任务的事实","evidence":"本轮用户输入中逐字摘录的原文"}
                """;
        ChatResult r = llmCallTracker.tracked(chatClient, null, "knowledge_extractor", null).chat(
                List.of(LlmMessage.system(sys), LlmMessage.user("近期用户输入：\n" + context + "\n本轮用户输入：\n" + latest)), List.of());
        return parseAndSave(r.content(), latest, projectId, conversationId);
    }

    int parseAndSave(String content, String latestUserText, String projectId, String conversationId) {
        if (content == null || content.isBlank()) return 0;
        int saved = 0;
        try {
            String s = content.replace("```json", "").replace("```", "").trim();
            int start = s.indexOf('[');
            int end = s.lastIndexOf(']');
            if (start < 0 || end <= start) return 0;
            JsonNode arr = om.readTree(s.substring(start, end + 1));
            if (!arr.isArray()) return 0;
            for (JsonNode n : arr) {
                if (saved >= 2) break;
                String category = n.path("category").asText("").trim();
                if (!CATEGORIES.contains(category) || ("项目事实".equals(category) && projectId == null)) continue;
                String key = n.path("key").asText("").trim();
                String fact = n.path("content").asText("").trim();
                String evidence = n.path("evidence").asText("").trim();
                if (key.isEmpty() || fact.isEmpty() || evidence.length() < 4 || !latestUserText.contains(evidence)) continue;
                if ((evidence + fact).matches("(?is).*(密码|密钥|api.?key|secret|bearer|sk-[a-z0-9]+).*")) continue;
                if (key.length() > 40) key = key.substring(0, 40);
                if (fact.length() > 240) fact = fact.substring(0, 240);
                if (evidence.length() > 200) evidence = evidence.substring(0, 200);
                if (knowledgeStore.propose(category, key, fact,
                        "项目事实".equals(category) ? projectId : null, evidence, conversationId)) saved++;
            }
        } catch (Exception e) {
            log.warn("[knowledge] 解析提取结果失败: {}", e.getMessage());
        }
        return saved;
    }
}
