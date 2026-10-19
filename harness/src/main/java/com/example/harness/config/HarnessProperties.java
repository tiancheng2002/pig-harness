package com.example.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Harness 全局配置（application.yml 中 harness.* 前缀）。
 */
@ConfigurationProperties(prefix = "harness")
public class HarnessProperties {

    /** 沙箱项目根；空 = 启动目录/workspace */
    private String root = "";

    /** SQLite 轨迹库路径（相对启动目录） */
    private String dbPath = "db/harness.db";

    /** 技能库目录；空 = 派生规则（root 已配置 → <root>/skills，否则 <启动目录>/skills）；Docker 部署显式指向 /data/skills */
    private String skillsDir = "";

    /** 主 Agent 长任务反思检查点间隔；不再作为终止上限 */
    private int maxSteps = 30;

    /** 会话模式路由（CHAT 直答 / TASK 走规划执行）；false 时全部输入走 AgentLoop */
    private boolean chatRouter = true;

    /** 续聊时携带的历史消息条数上限（防止上下文无限膨胀） */
    private int historyLimit = 20;

    /** 内置模型档案（除 harness.llm 默认档案外的系统预配模型） */
    private java.util.List<ModelDef> models = new java.util.ArrayList<>();

    private final Knowledge knowledge = new Knowledge();
    private final Llm llm = new Llm();
    private final Embedding embedding = new Embedding();
    private final Routing routing = new Routing();
    private final Mcp mcp = new Mcp();
    private final Http http = new Http();
    private final MultiAgent multiAgent = new MultiAgent();
    private final Shell shell = new Shell();
    private final Vision vision = new Vision();
    private final Context context = new Context();
    private final Evolution evolution = new Evolution();
    private final ModelRouting modelRouting = new ModelRouting();
    private final Hooks hooks = new Hooks();
    private final AgentPolicy agentPolicy = new AgentPolicy();

    /** 规划器开关：auto 时有界任务跳过独立规划调用 */
    private final Planner planner = new Planner();
    private final TaskRuntime taskRuntime = new TaskRuntime();
    /** 计价表（$/1M tokens）：key = 模型 API 名（ChatClient.modelName）；缺价 0 → 只统计 token。 */
    private final java.util.Map<String, Price> pricing = new java.util.LinkedHashMap<>();

    public static class ModelDef {
        private String id;
        private String name;
        private String baseUrl;
        private String apiKey;
        private String model;
        private String thinkingModel;
        private Integer contextLength;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getThinkingModel() { return thinkingModel; }
        public void setThinkingModel(String thinkingModel) { this.thinkingModel = thinkingModel; }
        public Integer getContextLength() { return contextLength; }
        public void setContextLength(Integer contextLength) { this.contextLength = contextLength; }
    }

    public static class Knowledge {
        /** 任务/对话结束后自动提取长期记忆（真实 LLM 模式生效） */
        private boolean autoExtract = true;

        public boolean isAutoExtract() { return autoExtract; }
        public void setAutoExtract(boolean autoExtract) { this.autoExtract = autoExtract; }
    }

    public static class Llm {
        private String baseUrl = "https://api.deepseek.com/v1";
        private String apiKey = "";
        private String model = "deepseek-chat";
        /** 模型显示名（前端选择器展示；API 模型 id 仍用 model） */
        private String name = "DeepSeek V4 Flash";
        /** 深度思考时切换到的推理模型（如 deepseek-reasoner）；空则该档案不支持深度思考 */
        private String thinkingModel = "";
        /** 内置默认/Pro 档案的上下文长度（token）；用户档案可单独配置 */
        private int contextLength = 131_072;
        /** 单次模型调用的最大输出 token；0 表示不向兼容端点发送上限。 */
        private int maxCompletionTokens = 8192;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getThinkingModel() { return thinkingModel; }
        public void setThinkingModel(String thinkingModel) { this.thinkingModel = thinkingModel; }
        public int getContextLength() { return contextLength; }
        public void setContextLength(int contextLength) { this.contextLength = contextLength; }
        public int getMaxCompletionTokens() { return maxCompletionTokens; }
        public void setMaxCompletionTokens(int maxCompletionTokens) { this.maxCompletionTokens = maxCompletionTokens; }
    }

    /**
     * 上下文窗口管理与压缩（对话级 LLM 摘要 + 任务级机械替换）。
     */
    public static class Context {
        /** 总开关；false 时既不显示压缩也不自动压缩（容量徽标仍可显示估算） */
        private boolean enabled = true;
        /** 软阈值（占上下文长度比例）：超过即在下一次调用前自动压缩 */
        private double softThreshold = 0.8;
        /** 对话级摘要：逐字保留的最近消息条数（其余进摘要） */
        private int keepRecentTurns = 6;
        /** 任务级压缩：保留完整工具输出的最近轮数（更早的替换为占位符） */
        private int keepRecentRounds = 2;
        /** 请求体携带 stream_options.include_usage 采集真实用量（个别兼容端点不认时可关） */
        private boolean requestUsage = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getSoftThreshold() { return softThreshold; }
        public void setSoftThreshold(double softThreshold) { this.softThreshold = softThreshold; }
        public int getKeepRecentTurns() { return keepRecentTurns; }
        public void setKeepRecentTurns(int keepRecentTurns) { this.keepRecentTurns = keepRecentTurns; }
        public int getKeepRecentRounds() { return keepRecentRounds; }
        public void setKeepRecentRounds(int keepRecentRounds) { this.keepRecentRounds = keepRecentRounds; }
        public boolean isRequestUsage() { return requestUsage; }
        public void setRequestUsage(boolean requestUsage) { this.requestUsage = requestUsage; }
    }

    public static class Embedding {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "text-embedding-3-small";
        private int dimension = 1536;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
    }

    public static class Routing {
        private double threshold = 0.82;
        private int topK = 5;

        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }

    /** M5 外部能力：MCP 客户端全局策略（服务器清单存 DB，不在此处）。 */
    public static class Mcp {
        /** mcp__ 工具是否始终需用户审批（auto 模式也弹卡）；false 时随权限三档流转 */
        private boolean requireApproval = true;
        /** initialize 握手超时（HTTP 传输） */
        private int connectTimeoutMs = 10_000;
        /** tools/call 超时（受 SSE 总超时 10 分钟约束） */
        private int callTimeoutMs = 60_000;
        /** initialize 握手超时（stdio 传输；npx 等冷启动需下载依赖，放宽到 60s） */
        private long stdioInitTimeoutMs = 60_000;

        public boolean isRequireApproval() { return requireApproval; }
        public void setRequireApproval(boolean requireApproval) { this.requireApproval = requireApproval; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getCallTimeoutMs() { return callTimeoutMs; }
        public void setCallTimeoutMs(int callTimeoutMs) { this.callTimeoutMs = callTimeoutMs; }
        public long getStdioInitTimeoutMs() { return stdioInitTimeoutMs; }
        public void setStdioInitTimeoutMs(long stdioInitTimeoutMs) { this.stdioInitTimeoutMs = stdioInitTimeoutMs; }
    }

    /** M5 外部能力：httpFetch 的 SSRF 防护开关。 */
    public static class Http {
        /** 是否允许 httpFetch 访问环回/私网地址（默认拒绝） */
        private boolean allowPrivate = false;

        public boolean isAllowPrivate() { return allowPrivate; }
        public void setAllowPrivate(boolean allowPrivate) { this.allowPrivate = allowPrivate; }
    }

    /** M4 多 Agent：虚拟线程扇出的成本与安全护栏。 */
    public static class MultiAgent {
        /** 多 Agent 编排总开关；false 时全部输入走单 Agent 现状路径 */
        private boolean enabled = true;
        /** 自动拆分开关（体验升级七）：false 时跳过 decompose 直接单 Agent；spawnAgent 按需派子智能体不受影响 */
        private boolean autoDecompose = true;
        /** 单次拆分的子任务上限（截断后仍 <2 则降级单 Agent） */
        private int maxFanout = 3;
        /** 同时活跃子 Agent 上限，超出排队 */
        private int maxConcurrency = 4;
        /** 子 Agent 局部步数上限；主 Agent 的 harness.max-steps 仅为反思检查点 */
        private int subAgentMaxSteps = 6;
        /** 扇出阶段整体 deadline（秒）；超时子任务取消并按失败隔离处理 */
        private int subAgentTimeoutSeconds = 180;
        /** 单子 Agent 失败不影响其余聚合；false 时任一失败即整体 failed */
        private boolean failIsolation = true;
        /** P1：多 Agent 子任务用 git worktree 物理隔离（项目为 git 仓库时生效）；false 时维持黑板占坑软约束 */
        private boolean worktreeIsolation = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAutoDecompose() { return autoDecompose; }
        public void setAutoDecompose(boolean autoDecompose) { this.autoDecompose = autoDecompose; }
        public int getMaxFanout() { return maxFanout; }
        public void setMaxFanout(int maxFanout) { this.maxFanout = maxFanout; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
        public int getSubAgentMaxSteps() { return subAgentMaxSteps; }
        public void setSubAgentMaxSteps(int subAgentMaxSteps) { this.subAgentMaxSteps = subAgentMaxSteps; }
        public int getSubAgentTimeoutSeconds() { return subAgentTimeoutSeconds; }
        public void setSubAgentTimeoutSeconds(int subAgentTimeoutSeconds) { this.subAgentTimeoutSeconds = subAgentTimeoutSeconds; }
        public boolean isFailIsolation() { return failIsolation; }
        public void setFailIsolation(boolean failIsolation) { this.failIsolation = failIsolation; }
        public boolean isWorktreeIsolation() { return worktreeIsolation; }
        public void setWorktreeIsolation(boolean worktreeIsolation) { this.worktreeIsolation = worktreeIsolation; }
    }

    /** bash 命令执行工具（ShellTool）。 */
    public static class Shell {
        /** false 时 bash 工具返回禁用错误（不注销，保持工具清单稳定） */
        private boolean enabled = true;
        /** 单条命令默认超时（秒） */
        private int defaultTimeoutSeconds = 60;
        /** 单条命令超时上限（秒），模型传更大值时被收敛 */
        private int maxTimeoutSeconds = 300;
        /** P1 执行隔离：off（默认，直跑本机）| docker（容器内执行；Docker 不可用时明确报错不降级） */
        private String sandbox = "off";
        /** auto | bash | powershell | cmd；auto 会探测当前主机可用方言。 */
        private String preferred = "auto";
        /** P1 命令黑名单（正则，追加项；内置危险清单始终生效），命中即拒绝执行 */
        private java.util.List<String> denyPatterns = new java.util.ArrayList<>();
        private final Docker docker = new Docker();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
        public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) { this.defaultTimeoutSeconds = defaultTimeoutSeconds; }
        public int getMaxTimeoutSeconds() { return maxTimeoutSeconds; }
        public void setMaxTimeoutSeconds(int maxTimeoutSeconds) { this.maxTimeoutSeconds = maxTimeoutSeconds; }
        public String getSandbox() { return sandbox; }
        public void setSandbox(String sandbox) { this.sandbox = sandbox; }
        public String getPreferred() { return preferred; }
        public void setPreferred(String preferred) { this.preferred = preferred; }
        public java.util.List<String> getDenyPatterns() { return denyPatterns; }
        public void setDenyPatterns(java.util.List<String> denyPatterns) { this.denyPatterns = denyPatterns; }
        public Docker getDocker() { return docker; }
    }

    /** P1：docker 隔离执行参数（harness.shell.sandbox=docker 时生效）。 */
    public static class Docker {
        private String image = "eclipse-temurin:21-jre";
        /** none = 容器内无网络（更安全；需要联网构建时改为 bridge） */
        private String network = "none";
        private String memory = "2g";
        private String cpus = "2";

        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
        public String getNetwork() { return network; }
        public void setNetwork(String network) { this.network = network; }
        public String getMemory() { return memory; }
        public void setMemory(String memory) { this.memory = memory; }
        public String getCpus() { return cpus; }
        public void setCpus(String cpus) { this.cpus = cpus; }
    }

    /** P1：轻模型路由——未显式选模型时，CHAT 直答与技能执行用更便宜的模型。 */
    public static class ModelRouting {
        /** 空 = 关闭；配置模型档案 id（如 pro/default 或用户档案 id）后生效 */
        private String lightModelId = "";

        public String getLightModelId() { return lightModelId; }
        public void setLightModelId(String lightModelId) { this.lightModelId = lightModelId; }
    }

    /** P1：验证钩子（.harness/hooks.json）行为参数。 */
    public static class Hooks {
        /** 验证失败回灌模型自动修复的最大重试轮数；耗尽后结论强制附带失败详情 */
        private int maxRetries = 2;

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    /** P1：单模型计价（$/1M tokens）。 */
    public static class Price {
        private double input = 0;
        private double output = 0;

        public double getInput() { return input; }
        public void setInput(double input) { this.input = input; }
        public double getOutput() { return output; }
        public void setOutput(double output) { this.output = output; }
    }

    /** 视觉模型（图片理解）：OpenAI 兼容端点，仅在 application.yml 配置（不进设置页）。 */
    public static class Vision {
        /** 空 = 继承 harness.llm.base-url */
        private String baseUrl = "";
        /** 空 = 继承 harness.llm.api-key */
        private String apiKey = "";
        /** 视觉模型名（如 qwen-vl-plus / glm-4v）；留空 = 未启用图片理解 */
        private String model = "";
        /** 单条消息最多处理的图片数 */
        private int maxImages = 4;
        /** 单张图片识别超时（秒） */
        private int timeoutSeconds = 60;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxImages() { return maxImages; }
        public void setMaxImages(int maxImages) { this.maxImages = maxImages; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    /** P0 自进化闭环：任务成功轨迹 → 聚类计数 → 自动合成技能草稿 → 沙箱校验 → 入库复用。 */
    public static class Evolution {
        /** 总开关；false 时不写 task_vectors、不自动合成（手动「存为技能」不受影响） */
        private boolean enabled = true;
        /** 同簇成功任务数达到该阈值时触发合成 */
        private int recurrenceThreshold = 3;
        /** 聚类归并相似度阈值（余弦 / 无 Key 时为字符 bigram Jaccard） */
        private double clusterSimilarity = 0.90;
        /** true = 校验通过即自动入库；false = 停在 validated 待人工确认 */
        private boolean autoCommit = false;
        /** 校验失败后带报告重新合成的重试次数 */
        private int validatorRetries = 1;
        /** 聚类比较窗口：只与最近 N 条成功任务向量比较 */
        private int scanRecent = 50;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRecurrenceThreshold() { return recurrenceThreshold; }
        public void setRecurrenceThreshold(int recurrenceThreshold) { this.recurrenceThreshold = recurrenceThreshold; }
        public double getClusterSimilarity() { return clusterSimilarity; }
        public void setClusterSimilarity(double clusterSimilarity) { this.clusterSimilarity = clusterSimilarity; }
        public boolean isAutoCommit() { return autoCommit; }
        public void setAutoCommit(boolean autoCommit) { this.autoCommit = autoCommit; }
        public int getValidatorRetries() { return validatorRetries; }
        public void setValidatorRetries(int validatorRetries) { this.validatorRetries = validatorRetries; }
        public int getScanRecent() { return scanRecent; }
        public void setScanRecent(int scanRecent) { this.scanRecent = scanRecent; }
    }

    /** Coding Agent 运行时可靠性策略。 */
    /** 独立规划调用（Planner）的触发策略：auto / always / off。 */
    public static class Planner {
        private String mode = "auto";
        /** auto 模式下，超过该字符数的任务才值得单独规划一次。 */
        private int autoPromptChars = 180;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public int getAutoPromptChars() { return autoPromptChars; }
        public void setAutoPromptChars(int value) { this.autoPromptChars = value; }
    }

    public static class AgentPolicy {
        private int explorationSoftLimit = 12;
        private int maxConsecutiveFailures = 3;
        private int maxRepeatedFailures = 2;
        private int completionRetries = 2;
        private int taskMaxQuestions = 1;
        private int sameCallLoopLimit = 1;
        private int stagnationRoundThreshold = 3;
        /** 软预算：达到该工具调用数时提醒收敛（不限制工具）；按任务规模自动缩放。 */
        private int toolCallSoftBudget = 25;
        /** 安全上限：单次任务的工具调用绝对上限，仅作兜底，正常任务不应触及。 */
        private int toolCallCeiling = 200;
        /** 连续多少次调用没有新进展即判定空转并硬停（长任务的真正成本来源）。 */
        private int unproductiveCallLimit = 10;
        /** 硬停的时间维度（秒）；只有在同时缺乏进展时才生效，仍在推进的任务不会被时间切断。 */
        private int wallClockBudgetSeconds = 1800;

        public int getExplorationSoftLimit() { return explorationSoftLimit; }
        public void setExplorationSoftLimit(int value) { this.explorationSoftLimit = value; }
        public int getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
        public void setMaxConsecutiveFailures(int value) { this.maxConsecutiveFailures = value; }
        public int getMaxRepeatedFailures() { return maxRepeatedFailures; }
        public void setMaxRepeatedFailures(int value) { this.maxRepeatedFailures = value; }
        public int getCompletionRetries() { return completionRetries; }
        public void setCompletionRetries(int value) { this.completionRetries = value; }
        public int getTaskMaxQuestions() { return taskMaxQuestions; }
        public void setTaskMaxQuestions(int value) { this.taskMaxQuestions = value; }
        public int getSameCallLoopLimit() { return sameCallLoopLimit; }
        public void setSameCallLoopLimit(int value) { this.sameCallLoopLimit = value; }
        public int getStagnationRoundThreshold() { return stagnationRoundThreshold; }
        public void setStagnationRoundThreshold(int value) { this.stagnationRoundThreshold = value; }
        public int getToolCallSoftBudget() { return toolCallSoftBudget; }
        public void setToolCallSoftBudget(int value) { this.toolCallSoftBudget = value; }
        public int getToolCallCeiling() { return toolCallCeiling; }
        public void setToolCallCeiling(int value) { this.toolCallCeiling = value; }
        public int getUnproductiveCallLimit() { return unproductiveCallLimit; }
        public void setUnproductiveCallLimit(int value) { this.unproductiveCallLimit = value; }
        public int getWallClockBudgetSeconds() { return wallClockBudgetSeconds; }
        public void setWallClockBudgetSeconds(int value) { this.wallClockBudgetSeconds = value; }
    }

    /** P4：统一任务生命周期、指标明细与结构化结论。 */
    public static class TaskRuntime {
        private boolean enabled = true;
        private final Metrics metrics = new Metrics();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Metrics getMetrics() { return metrics; }

        public static class Metrics {
            private boolean persistLlmCalls = true;
            private boolean persistToolCalls = true;
            private int detailRetentionDays = 90;

            public boolean isPersistLlmCalls() { return persistLlmCalls; }
            public void setPersistLlmCalls(boolean persistLlmCalls) { this.persistLlmCalls = persistLlmCalls; }
            public boolean isPersistToolCalls() { return persistToolCalls; }
            public void setPersistToolCalls(boolean persistToolCalls) { this.persistToolCalls = persistToolCalls; }
            public int getDetailRetentionDays() { return detailRetentionDays; }
            public void setDetailRetentionDays(int detailRetentionDays) { this.detailRetentionDays = detailRetentionDays; }
        }
    }

    public String getRoot() { return root; }
    public void setRoot(String root) { this.root = root; }
    public String getDbPath() { return dbPath; }
    public void setDbPath(String dbPath) { this.dbPath = dbPath; }
    public String getSkillsDir() { return skillsDir; }
    public void setSkillsDir(String skillsDir) { this.skillsDir = skillsDir; }
    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
    public boolean isChatRouter() { return chatRouter; }
    public void setChatRouter(boolean chatRouter) { this.chatRouter = chatRouter; }
    public int getHistoryLimit() { return historyLimit; }
    public void setHistoryLimit(int historyLimit) { this.historyLimit = historyLimit; }
    public java.util.List<ModelDef> getModels() { return models; }
    public void setModels(java.util.List<ModelDef> models) { this.models = models; }
    public Knowledge getKnowledge() { return knowledge; }
    public Llm getLlm() { return llm; }
    public Embedding getEmbedding() { return embedding; }
    public Routing getRouting() { return routing; }
    public Mcp getMcp() { return mcp; }
    public Http getHttp() { return http; }
    public MultiAgent getMultiAgent() { return multiAgent; }
    public Shell getShell() { return shell; }
    public Vision getVision() { return vision; }
    public Context getContext() { return context; }
    public Evolution getEvolution() { return evolution; }
    public ModelRouting getModelRouting() { return modelRouting; }
    public Hooks getHooks() { return hooks; }
    public AgentPolicy getAgentPolicy() { return agentPolicy; }

    public Planner getPlanner() { return planner; }
    public TaskRuntime getTaskRuntime() { return taskRuntime; }
    public java.util.Map<String, Price> getPricing() { return pricing; }
}
