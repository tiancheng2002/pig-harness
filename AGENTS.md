# 自进化 Agent Harness — 项目说明文档

> **文档用途**：本文件是项目的「总纲 + 上手指南」。新接手项目的 AI/开发者阅读本文即可快速建立全局认知；M1 / M2 等里程碑的详细验收标准见各自开发文档（见文末「参考文档」）。
> **最后更新**：2026-09-18 ｜ 覆盖进度：M1（F1–F14）✅、M2 路由与技能层 ✅、体验升级一至九 ✅、M4 多 Agent ✅、M5 外部能力 ✅、P0 自进化闭环 ✅、P1 编码硬实力 ✅、P2 采用门槛 ✅、**P4 Task Runtime v2（持久化 TaskRun + 统一指标 + 结构化 TaskOutcome）✅**。

---

## 0. 30 秒速览（给 AI / 新接手者）

- **这是什么**：一个**本机运行**的自进化 Agent 执行框架（Spring Boot 后端 + 网页 UI）。用户用自然语言下任务，框架规划并执行，把执行轨迹落库，并自动从对话中**沉淀长期记忆（知识）**——越用越懂用户；同类任务可沉淀为技能复用。
- **现在能做什么**：浏览器里对话 → **模式路由分流**（闲聊直答 / 任务规划执行）→ **多模型选择 + 🧠深度思考**（内置模型 + 用户自配 API Key，深度思考切换推理模型，显示名如 `DeepSeek V4 Flash`）→ **直接 Ctrl+V 粘贴图片**（视觉模型转述图片内容后结合输入作答）→ LLM 逐 token 流式输出 + 思考折叠面板（Codex/ZCode 风格）→ 沙箱内文件操作 + **bash 命令执行**（编译/测试/git/进程查看）→ 产出文件**点击弹出右侧挤压式抽屉预览**（推开对话而非遮盖）→ 工具执行过程聚合为一条折叠汇总 + 「正在执行 X」实时指示 → 项目任务结束显示「N 个文件已更改 +X −Y」聚合条（点开即看行级 diff）→ **知识页**管理长期记忆 → 明暗双主题。技能库 + Embedding 路由已落地；可并行任务自动**多 Agent 扇出**（拆分子任务 DAG → 虚拟线程并行执行 → 流式聚合，界面按子 Agent 泳道展示）。
- **项目模式**：在侧栏「项目」分组（悬停标题点 **＋**）通过**文件夹选择器**绑定本地文件夹后，该项目下的所有对话都以它为工作根读写文件；右侧 **Git 悬浮面板**实时显示当前分支 / 增删行数 / 变更文件清单（可展开彩色 diff），支持直接**提交 / 推送**；每轮对话结束自动刷新。侧栏下部分为「项目 / 对话」两个可折叠分组；在项目会话中点「新对话」自动归属当前项目。
- **现在能自动进化**：任务成功后异步聚类计数（task_vectors），同类任务成功 ≥3 次自动合成技能草稿 → 隔离沙箱校验（5 项检查，失败带报告重试）→ validated 待人工确认（或 auto-commit 自动入库）→ 启用后参与意图路由复用；技能页「进化」Tab 可视化全流程（草稿管理 + 复用统计 + 知识周增长 + 节省估算）。手动入口「存为技能」保留。
- **怎么跑**：`java -jar harness/target/harness.jar`（前端已内嵌）；默认 `http://localhost:8080`。**未配置 LLM Key 时自动进入 Mock 演示模式**，全链路（含流式打字机与思考演示）可本地跑通。
- **与计划文档的差异**：原设计写「Spring AI 1.x」，实际落地为**自研 OpenAI 兼容流式 ChatClient（无 Spring AI 依赖）**；`Tool` 接口带 `ToolContext` 参数；SSE 事件协议已升级为 v2（新增 delta/思考/进度事件）。详见 §9「已知设计偏差」。

---

## 1. 项目定位

**本地优先的自进化编码 Agent：规划并执行任务，把成功经验沉淀为可复用技能，代码与数据永不出本机。**（与 README / 仓库简介三处一致的定位一句话，改名或宣传时同步）

一个运行在开发者**本机**的、能在使用过程中自动沉淀「技能（Skill）」的 Agent 执行框架。用户用自然语言下达任务（如"做个登录页""生成一份周报"），Harness 规划并执行；当同类任务反复出现，它自动把成功做法抽象成可复用技能，下次直接复用，实现"越用越聪明"。

- **目标用户**：程序员及重度本地文件操作人群
- **交付形态**：本地运行的 Spring Boot 服务 + 网页 UI（浏览器访问 `localhost`）
- **核心特征**：自进化（self-evolving）、本地优先（local-first）、技能可版本化

---

## 2. 必须具备的核心能力（路线图）

| 能力 | 说明 | 当前状态 |
|------|------|----------|
| 文件操作 | 项目目录内创建 / 读取 / 修改文件（html、md、源码、docx 等） | ✅ 已落地（沙箱内） |
| 任务规划（plan） | 将复杂任务拆解为可执行的步骤树 | ✅ 已落地 |
| 主循环（loop） | 规划 → 执行 → 观察 → 反思 的迭代执行 | ✅ 已落地 |
| 记忆管理（memory） | 工作记忆 + 情景记忆（历史轨迹）+ 语义/长期（技能库） | ✅ 工作记忆 + 情景轨迹 + 多会话 + 技能库（skills 表 + Embedding 检索）+ 知识（长期记忆）均已落地 |
| 项目模式 | 绑定本地文件夹作为工作根 + Git 变更/提交/推送 | ✅ 已落地 |
| 多 Agent（M4） | 主 Agent 派发子 Agent 并行处理 | ✅ 已落地（计划期 DAG 拆分 + 虚拟线程扇出，默认开启，不满足触发门槛时降级单 Agent） |

---

## 3. 已实现功能清单（已完成 ✅）

> 状态判定基准：对应代码 / 表 / 接口已落地并通过 E2E（见 `e2e_check.py`）。

### M1 主体（最小可运行骨架，F1–F11）
| 编号 | 功能 | 状态 | 落地位置 |
|------|------|------|----------|
| F1 | 项目脚手架与一键启动 | ✅ | `harness/pom.xml`、`HarnessApplication`、`StartupReporter` |
| F2 | Web UI（对话 + 步骤 / 工具调用 / 文件展示，SSE 流式） | ✅ | `frontend/`（React 19 + Vite 6），产物内嵌 `static/` |
| F3 | AgentLoop 主循环（plan→act→observe→reflect，max 10 步） | ✅ | `core/AgentLoop.java` |
| F4 | Planner 任务规划（LLM 拆分） | ✅ | `core/Planner.java` |
| F5 | Tool 机制（ToolRegistry + Tool 接口 + function calling） | ✅ | `core/ToolRegistry`、`tools/Tool` |
| F6 | FileTools（fileWrite / fileRead + 沙箱越界拦截） | ✅ | `tools/FileWriteTool`、`FileReadTool`、`sandbox/Sandbox` |
| F7 | DocxTools（结构化内容 → 真实 .docx） | ✅ | `tools/DocxWriteTool`（Apache POI） |
| F8 | EpisodicStore（SQLite 轨迹持久化） | ✅ | `core/memory/EpisodicStore`、`Db`（`traces` 表） |
| F9 | WorkingMemory（单任务上下文 + 步骤间变量传递） | ✅ | `core/memory/WorkingMemory` |
| F10 | 本地项目根绑定 + 沙箱校验 | ✅ | `config/RootResolver`、`sandbox/Sandbox` |
| F11 | SPA 回退 + 静态资源服务 | ✅ | `web/SpaFallback` |

### M1 补充（会话管理 + 导航栏，F12–F14）
| 编号 | 功能 | 状态 | 落地位置 |
|------|------|------|----------|
| F12 | 多会话模型与持久化（conversations/messages）+ 切换续聊 | ✅ | `core/memory/ConversationStore`、`web/ConversationController`、`Db` |
| F13 | 左侧导航栏布局（上：新对话 / 技能；下：历史会话列表） | ✅ | `frontend/` 布局组件 |
| F14 | 会话操作（重命名 / 删除）+ 导航栏扩展按钮 | ✅ | `ConversationController` PATCH/DELETE + 前端 |

### M2 路由与技能层（已落地）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 技能库（SKILL.md 解析 + `skills` 表 + `/api/skills` 管理页） | ✅ | `skill/SkillLibrary`、`SkillStore`、`SkillParser`、`web/SkillController` |
| Embedding 检索（OpenAI 兼容向量化 + Java 内余弦 + 关键词降级） | ✅ | `embedding/*`、`router/IntentRouter` |
| 技能执行器（LLM 填参 → 顺序调用工具） | ✅ | `core/SkillExecutor`、`skill/ParameterFiller` |
| 任务完成后「存为技能」草稿 | ✅ | `SkillController /from-trace` + 前端 |

### 体验升级改造（2026-08-29，v2 事件协议）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 会话模式路由（CHAT 闲聊直答 / TASK 任务执行，快路径正则 + 轻量 LLM 分类） | ✅ | `core/ChatModeRouter`、`core/ChatExecutor`、`TaskDispatcher` |
| LLM 流式输出（`stream:true`，delta.content / reasoning_content / tool_calls 增量） | ✅ | `llm/ChatClient(StreamListener)`、`OpenAiCompatChatClient`、`MockChatClient`（分片模拟） |
| 思考过程展示（思考链 + 每轮步骤说明折叠面板，流式自动展开） | ✅ | `AgentLoop` step_commentary/thinking 事件 + 前端 `TaskCard/ThinkingPanel` |
| 文件工具 v2：fileEdit 精确修改 / listFiles 列目录 / fileDelete / 覆盖写 / fileRead 行分页 | ✅ | `tools/FileEditTool`、`ListFilesTool`、`FileDeleteTool`、`FileWriteTool`、`FileReadTool` |
| 前端流式渲染（打字机 + Markdown + 计划进度 ✓/▶/○ + 文件大小 + 停止按钮 + 贴底自动滚动） | ✅ | `frontend/src/pages/Chat.jsx`、`components/TaskCard.jsx`（react-markdown + remark-gfm） |
| 安全修复：`/api/file` 不再接受客户端指定 root | ✅ | `web/FileController` |
| 执行取消（客户端断开/停止按钮 → SSE 发送失败置 cancelled 中止循环） | ✅ | `AgentLoop.Emitter`、`api.js` AbortSignal |

### 体验升级二（2026-08-29，多模型 + 深度思考 + 知识记忆 + UI 重构）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 多模型选择（内置 yml 预配 + 用户自配 API Key，存 SQLite；API 不回传 Key） | ✅ | `core/ModelRegistry`、`core/memory/ModelStore`、`model/ModelProfile`、`web/ModelController` |
| 🧠 深度思考（按请求切换推理模型，如 deepseek-reasoner；`thinking-model` 配置） | ✅ | `ModelRegistry.resolve(modelId, deepThink)`、`Task.modelId/deepThink`、composer 开关 |
| 知识（长期记忆）：仅从最新用户输入提取有原文证据的候选 → 待审核 → 确认后按任务相关性和项目范围少量注入；旧自动知识迁为待审核 | ✅ | `core/knowledge/KnowledgeExtractor`、`core/memory/KnowledgeStore`、`web/KnowledgeController`、`knowledge` 表 |
| 知识页（替代运行轨迹页）：已采用 / 待审核 / 已归档分区；待审核内集中手动添加、会话提取、候选确认与批量忽略（忽略直接删除） | ✅ | `frontend/src/pages/Knowledge.jsx`（/api/knowledge） |
| Codex 风格对话 UI：去头像、思考折叠块、工具单行（动词+目标+可展开）、文件 chip、纯 Markdown 回答 | ✅ | `components/TaskCard.jsx` |
| 文件预览抽屉：点击文件 chip 右侧滑出（HTML 预览/源码双 tab、图片、Markdown、代码、docx 下载） | ✅ | `components/FileDrawer.jsx` |
| 全新设计系统：明暗双主题（`data-theme` + localStorage）、重构布局/配色/组件 | ✅ | `styles.css`、Sidebar 主题切换 |

### 项目模式（2026-08-29，本地工程绑定 + Git 集成）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 项目绑定：本地文件夹校验、同路径去重、删除仅解绑会话（文件不动） | ✅ | `core/memory/ProjectStore`（`projects` 表）、`web/ProjectController`、侧栏项目分组 + 文件夹选择器 |
| 项目会话：会话挂接 `project_id`，发送消息时沙箱根切换为项目根（root 来自 DB，客户端不可指定）；侧栏项目徽标 + 会话页项目 chip | ✅ | `ConversationStore`、`ConversationController`、`pages/Chat.jsx` |
| Git 状态：分支 / ahead-behind / 变更文件清单（porcelain 状态位 + 每文件增删行数，未跟踪按行统计） | ✅ | `core/git/GitService`（本机 git CLI，无 JGit 依赖） |
| 单文件 diff（未跟踪合成新增 diff，600 行截断）；提交（身份兜底）；推送（无 upstream 自动 `-u origin`） | ✅ | `GitService.diff/commit/push` |
| 右侧 Git 悬浮面板：悬浮按钮（分支+增删概览）→ 面板（文件状态徽标着色 / 彩色 diff / 提交 / 推送 / 刷新），每轮对话结束自动刷新并折叠旧 diff | ✅ | `components/GitPanel.jsx` |
| 项目模式提示词：AgentLoop system prompt 增加「真实项目最小改动」约束 | ✅ | `AgentLoop.systemPrompt(projectMode)` |
| 模型显示名：`harness.llm.name`（默认 `DeepSeek V4 Flash`），前端展示友好名称 | ✅ | `HarnessProperties.Llm.name`、`ModelRegistry.builtinDefault` |

### 体验升级三（2026-08-29，侧栏分组 + 文件夹选择器 + 双内置模型）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 侧栏「项目 / 对话」折叠分组（替代独立项目页）：项目按文件夹归组会话，折叠状态持久化 localStorage | ✅ | `frontend/src/Sidebar.jsx`（`harness.sec.*`） |
| 悬停快捷操作：「项目」标题悬停显 **＋**（添加项目）；项目行悬停显 **＋/✕**（新建项目会话 / 解绑）；项目行点击打开最近会话 | ✅ | `Sidebar.jsx`、`components/FolderPickerModal.jsx` |
| 文件夹选择器：逐级浏览本地目录选项目根（不再手输路径）；名称留空默认取文件夹名 | ✅ | `web/FsController`（`GET /api/fs/list`）、`FolderPickerModal.jsx` |
| 「新对话」跟随上下文：项目会话中 → 新会话归属当前项目；普通会话中 → 不挂项目 | ✅ | `Sidebar.newChat()`（`createConversation(projectId)`） |
| 双内置模型：`DeepSeek V4 Flash`（deepseek-chat，默认）+ `DeepSeek V4 Pro`（deepseek-reasoner，即 thinking-model 本身） | ✅ | `ModelRegistry.builtinDefault/builtinPro`（`DEFAULT_ID`/`PRO_ID`） |
| 修复：未跟踪文件行数统计与 `wc -l` 口径对齐（末尾换行符不另计一行） | ✅ | `GitService.countUntrackedLines` |

### 体验修复一（2026-08-29，权限三档 + 流式稳定性 + 渲染修复）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 文件操作权限三档（输入框选择，随消息下发）：`auto` 替我审批（默认，自动执行限工作根内）/ `ask` 请求审批（写操作先弹卡确认）/ `full` 完全访问（自动执行且允许沙箱外绝对路径） | ✅ | `Task.approval`、`ToolContext(allowOutside)`、composer `.perm-select` |
| 审批闸门：写操作（fileWrite/fileEdit/fileDelete/docxWrite）挂起等用户决定，拒绝以失败结果回灌模型；5 分钟未确认视为拒绝 | ✅ | `core/ApprovalGate`、`AgentLoop` MUTATING_TOOLS 分支、`POST /api/conversations/{id}/approval` |
| 审批卡片 UI（批准/拒绝按钮，按 callId 与后续 tool_call 关联判定已决，历史回放只读） | ✅ | `TaskCard.ApprovalCard`（`approval_request` 事件，落库可回放） |
| 模式路由带上下文：分类器携带最近 6 条对话，「继续/接着做」承接上文不再误判 CHAT | ✅ | `ChatModeRouter.route(prompt, history)` |
| LLM 流式断流看门狗：120 秒无输出强制断开报错；轮次失败保留已输出片段并自动重试一次 | ✅ | `OpenAiCompatChatClient`（STALL_TIMEOUT_MS）、`AgentLoop.chatRound` 重试分支 |
| 用户气泡防溢出：长 URL / 长分隔线等不可断串改为任意断行，不再撑破气泡 | ✅ | `styles.css` `.user-bubble` overflow-wrap |
| Markdown 代码块复制按钮（悬停显示，点击复制全文，含剪贴板 API 兜底） | ✅ | `components/Md.jsx` CopyPre |
| Codex 式中性黑白主题（去暖棕色相，明暗双主题均为黑白灰单色系）+ 模型切换自绘下拉（圆角面板 + 选中档案右侧勾勾 + 底部「管理模型」直达设置弹窗） | ✅ | `frontend/src/styles.css`（主题 token）、`pages/Chat.jsx`（ModelMenu）、`App.jsx`（open-settings 事件） |

### 技能体验一（2026-08-30，/ 技能提及 + 技能库分区 + 合成质量）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 输入 `/` 弹出技能选择列表（仅已启用，名称/触发词模糊匹配，↑↓/Enter/点击选择），选中后输入框显示淡蓝色技能 chip（可 ✕ 移除），随消息携带 `skillId` 显式执行 | ✅ | `pages/Chat.jsx`（mention/chip 状态机）、`MessageRequest.skillId`、`TaskDispatcher` 显式技能分支 |
| `skill` SSE 事件（落库可回放）：执行开始时标识所用技能，历史轮次渲染蓝色 chip | ✅ | `SkillExecutor.execute`、`TaskCard.EventView` |
| 技能库分区：系统（内置 4 个：frontend-design / docx-writer / code-review / weekly-report，蓝色徽标，可禁用、不可删，文件删除后重启自动播种恢复）+ 个人（新建/合成/导入） | ✅ | `skill/SkillLibrary.seedBuiltinSkills`（classpath `skills/builtin/*.md` → `skills/system/`）、`skills.origin` 列、`pages/Skills.jsx` |
| 存为技能弹窗重构（替代 window.prompt）：两步式——起名（可留空自动生成）→ 展示合成的名称/描述/触发词/何时使用，直达技能库启用 | ✅ | `components/SaveSkillModal.jsx` |
| 合成质量：`SkillSynthesizer` 结合本轮任务 + 最近 8 条对话上下文 + 实际工具步骤，LLM 生成英文名/一句话描述/3~6 个自然触发词/when_to_use（参考 Anthropic skill-creator 实践）；Mock/失败时按任务文本确定性兜底，不再出现「从轨迹 #N 自动生成」 | ✅ | `skill/SkillSynthesizer`、`ConversationStore.findConversationIdByTrace`、`SkillController /from-trace` |
| 导入 .md：技能页一键导入 SKILL.md 文件（名称取文件名，frontmatter 照常解析） | ✅ | `pages/Skills.jsx`（隐藏 file input → `POST /api/skills`） |
| 修复：历史回放轮次缺失 traceId 导致「存为技能」按钮不显示 | ✅ | `Chat.jsx buildTurns` |
| 修复：SkillParser 键值对正则按字符递归回溯，超长单行 content（如整页 HTML）触发 StackOverflowError → 改为手写线性扫描器 | ✅ | `SkillParser.parseKvPairs` |

**小结**：截至本文，M1（F1–F11）与 M1 补充（F12–F14）全部完成，主链路「对话 → 规划 → 沙箱执行 → 流式展示 → 轨迹落库 → 多会话续聊」端到端可用。

### 外部能力扩展一（2026-08-30，M5：提示词技能 + MCP Host + httpFetch）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 技能双形态（M5-F1）：frontmatter `mode: steps/prompt`，缺省 auto（有步骤行=steps，否则 prompt）；技能页「步骤/提示词」徽标；`skills.mode` 列 | ✅ | `SkillParser`（mode 判定 + 正文占位符提取 + BOM 兼容 + `stripFrontmatter`）、`Skill.mode`、`pages/Skills.jsx` |
| 提示词技能执行（M5-F2/F3）：散文 SKILL.md 填参（未填占位符保留 `{{name}}` 由模型按语境决策）后作为最高优先级指令注入 AgentLoop——**跳过 Planner**，流式/审批/轨迹落库全复用；`AgentLoop.execute` 新增 instruction 重载 | ✅ | `SkillExecutor.executeGuided/applyParams`、`AgentLoop.execute(..., instruction)`、`LlmParameterFiller`（散文正文进填参上下文） |
| 误判降级：声明 steps 但引用未注册工具 → 自动按 prompt 执行（社区技能正文常含 `1. tool(...)` 样例行） | ✅ | `SkillExecutor.execute`（`toolRegistry.isRegistered` 校验） |
| MCP 服务器管理（M5-F6）：`mcp_servers` 表 + CRUD/refresh/tools API + 设置弹窗「MCP 服务器」分区（状态徽标/启停/刷新/删除，自定义请求头） | ✅ | `mcp/McpServerStore`、`web/McpController`、`SettingsModal.jsx`、`api.js` |
| MCP 连接（M5-F7）：手写 JSON-RPC 2.0 Streamable HTTP 客户端（JSON/SSE 双帧解析、Mcp-Session-Id 会话）；启动后台连接不阻塞启动；调用失败 lazy 重连一次 | ✅ | `mcp/McpHttpClient`、`McpConnectionManager`（接口层隔离，可替换官方 SDK） |
| MCP 工具接入（M5-F8）：远端工具 → `mcp__{server}__{tool}` 适配 ToolRegistry；ToolRegistry 改 ConcurrentHashMap + 运行期 register/unregister | ✅ | `mcp/McpTool`、`core/ToolRegistry` |
| 外部工具审批（M5-F9）：`harness.mcp.require-approval=true`（默认）时 `mcp__*` 在 auto 模式也走审批闸门（复用审批卡/拒绝回灌） | ✅ | `AgentLoop.needsApproval`、`SkillExecutor.needsApproval` |
| systemPrompt 工具清单动态化：基础文件工具固定文案 + 扩展工具（httpFetch/mcp__*）由 `ToolRegistry.extras()` 动态追加 | ✅ | `AgentLoop.systemPrompt`、`ToolRegistry.extras` |
| httpFetch 工具（M5-F11）：方法/头/超时/截断；SSRF 防护（默认拒环回/私网/ULA/CGNAT，重定向逐跳重验，DNS 重绑定为已知限制）；只读不进审批白名单 | ✅ | `tools/HttpFetchTool`、`harness.http.allow-private` |
| E2E：`e2e_prose_skill_check.py`（23 项）/ `e2e_mcp_check.py`（17 项，含夹具 `fixtures/mcp_echo_server.py`）；回归 e2e_skill_check 24/24、e2e_v2_check 全过 | ✅ | 仓库根 + `fixtures/` |

### MCP stdio 传输扩展（2026-09-26，本地子进程形态 + 审批闸门竞态修复）
> 接入实例见 `docs/接入GitHub-MCP.md`（GitHub 官方远程/本地服务器两种形态）。E2E：`e2e_stdio_check.py` 16/16（Mock+真实 LLM：注册自动连接/分词归一/缺 command 400/审批秒批/Windows .cmd 包装含空格路径/禁用注销）；回归 e2e_mcp_check 17/17 全过。
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| McpClient 传输抽象：CallResult 上移接口，4 方法（initialize/listTools/callTool/close）成为契约；McpHttpClient 实现之，新增传输实现接口即可接入 | ✅ | `mcp/McpClient`（新）、`McpHttpClient implements McpClient`、`McpConnectionManager`（只依赖接口） |
| stdio 客户端：ProcessBuilder 启动本地 MCP 子进程，JSON-RPC 2.0 走 stdin/stdout 行分隔 JSON；常驻读线程按 id 分发响应（通知帧/非 JSON 行宽容忽略）；进程退出（onExit/EOF）让挂起调用明确失败走 lazy 重连；close 先关 stdin 优雅退出兜底 destroyForcibly；stderr 持续排空进 slf4j 日志（`[mcp-stdio:服务器名]` 前缀）；管道强制 UTF-8 | ✅ | `mcp/McpStdioClient`（新，~300 行） |
| Windows 命令解析：裸命令名优先在 PATH 找 `.cmd`/`.bat`（npx/npm shim 是批处理，须先于 .exe 命中）并经 `cmd /c` 启动（Java 引号注入，含空格路径已 E2E 验证）；显式路径无扩展名按 .cmd/.bat/.exe 探测补全 | ✅ | `McpStdioClient.resolveExecutable` |
| 存储与迁移：`mcp_servers` 加 transport/command/args_json/env_json 列（CREATE TABLE 含新列 + 旧库 addColumnIfMissing，transport 默认 'http' 与既有数据语义一致）；record 扩展 + isStdio()；旧库原样迁移 | ✅ | `core/memory/Db`、`mcp/McpServerStore` |
| 连接管理分流：connect() 按 transport 构建 HTTP/stdio 客户端；替换连接前必须 close 旧客户端（stdio 持有子进程，置空丢弃会泄漏进程）；失败时关闭半启动候选；`@PreDestroy` 全量排水（应用关闭终止子进程） | ✅ | `McpConnectionManager`（buildClient/connect/shutdown） |
| API 扩展：Create/Patch 请求体加 transport/command/args/env；transport 归一（显式 > 按 command 推断 > http）、stdio 校验非空 command、http 校验 url 仅 http/https；args 接受字符串（空白/双引号 shell 式分词）或数组，落库统一数组；patch 的 command/args/env/transport 变更同样触发重连；列表/详情回传新字段 | ✅ | `web/McpController` |
| 前端：MCP 表单加传输方式下拉（HTTP：URL+请求头 / stdio：命令+参数+环境变量 JSON）；列表行显示 transport 徽标 + stdio 显示完整启动命令；保存按钮按传输方式校验必填 | ✅ | `SettingsModal.jsx`（submitMcp + MCP 分区）、`styles.css`（.model-form select） |
| 配置：`harness.mcp.stdio-init-timeout-ms`（默认 60000，npx 等冷启动拉依赖放宽，与 HTTP connect-timeout-ms 分离） | ✅ | `config/HarnessProperties.Mcp`、`application.yml` |
| 审批/提问闸门竞态修复（顺带修既有缺陷）：AgentLoop/SkillExecutor 原先「先广播 approval_request/question_request 再 await 注册」，自动化秒批/极快点击的 POST 在注册前落空（409）→ 任务挂满 5 分钟被拒；现 ApprovalGate/QuestionGate 新增 register()（computeIfAbsent 预登记）在广播前调用，且 resolve 改为只 complete 不移除（resolve 落在 register 与 await 之间时移除会让 await 另建无人完成的 future 挂死）——两处竞态一并消除 | ✅ | `core/ApprovalGate`、`core/question/QuestionGate`（register/resolve）、`core/AgentLoop`（审批+askUser 两处先注册后广播）、`core/SkillExecutor`（技能步骤审批） |

### M4 多 Agent（2026-08-30，计划期 DAG + 虚拟线程扇出）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| Agent 抽象（M4-F1）：SubTask / DecomposePlan / AgentResult / SubAgentConfig / SharedBlackboard；子 Agent 独立 WorkingMemory，无共享可变状态 | ✅ | `core/multi/` 五个类型 |
| 计划期拆分（M4-F2）：`Planner.decompose` 输出子任务 DAG JSON，Schema 校验 + 多重降级（解析失败/子任务<2/成环/输出路径重叠/纯依赖链）→ 单 Agent 现状路径，绝不报错；拆分计划走既有 `plan` 事件展示 | ✅ | `core/Planner.java`（`[ROLE=DECOMPOSER]`） |
| 虚拟线程派发器（M4-F3）：Kahn 波次调度，同波 `newVirtualThreadPerTaskExecutor` 并行；整体 deadline（180s）超时 `future.cancel(true)`；`max-concurrency` Semaphore 限流；失败隔离 | ✅ | `core/multi/Orchestrator.java` |
| 共享黑板 v2（M4-F4）：`record`（截断 2000）/`claim`（putIfAbsent 原子占坑）/`snapshot`，无任何阻塞 get；`blackboard` 事件对外可见 | ✅ | `core/multi/SharedBlackboard.java` |
| 子 Agent 执行（M4-F5）：复用 `AgentLoop.runLoop`（`executeSubAgent` 不 emit done/不落 trace/不 persist/不 complete）；Emitter 注入 `agent` 字段（含 delta）+ `sharedCancel` 取消传播；callId 加 `agent::` 前缀防审批串扰；步数上限 6 | ✅ | `core/AgentLoop.java`（Emitter 并发化 + runLoop 抽取） |
| 结果聚合（M4-F6）：`[ROLE=AGGREGATOR]` 一次流式 LLM 聚合（text_delta 主泳道）；失败降级模板拼接；父 traces 一条（各子 Agent ToolCall 合并带 agent 标注），done 契约不变 | ✅ | `Orchestrator.aggregate/mergedSteps` |
| 冲突预防与占坑兜底（M4-F7）：拆分期输出路径重叠即降级；运行期 fileWrite/docxWrite/fileDelete 前置 claim 校验，被占且非本任务分配路径 → 错误回灌改名 | ✅ | `Planner.validate`、`SubAgentConfig.claimConflict` |
| 权限与安全继承（M4-F8）：子 Agent 继承沙箱根/权限三档/审批闸门（ask 弹卡并发排队）/模型（modelId+deepThink）；MCP 审批规则一致 | ✅ | `Orchestrator.runOne`（子 Task 继承字段） |
| Web 泳道可视化（M4-F9）：TaskCard 按 `agent` 字段分组泳道（spawn 标题 + 状态点 + 工具/文件行 + 泳道流式文本 + done 摘要）；blackboard 渲染为可折叠「共享登记」；历史回放同路径还原 | ✅ | `components/TaskCard.jsx`（AgentLane）、`pages/Chat.jsx`（laneText 路由）、`styles.css` |
| 配置化 + Mock（M4-F10）：`harness.multi-agent.*` 全套配置（默认开启）；MockChatClient 增 DECOMPOSER/AGGREGATOR 分支（拆分触发门槛 = ≥2 类交付动词 + ≥2 个文件候选，修改/查询类一律 single） | ✅ | `config/HarnessProperties.MultiAgent`、`application.yml`、`llm/MockChatClient.java` |
| E2E：`e2e_multiagent_check.py`（21 项，Mock/真实双模式：全链路/降级一致/审批继承+callId 前缀/黑板/回放/依赖 DAG 波次）；回归 e2e_v2_check、e2e_check 全过 | ✅ | 仓库根 |

---

### 体验升级四（2026-08-30，bash 命令执行 + 工具行聚合 + 计划进度收尾 + 挤压式抽屉）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| bash 命令执行工具：工作根内执行 shell 命令（编译构建/运行测试/git/进程端口查看），合并 stdout/stderr + 退出码回灌；输出超长截断（保留头尾），超时强制终止并回传已捕获输出 | ✅ | `tools/ShellTool.java`（`@Component` 自动注册） |
| Shell 选择与兼容：优先本机 bash（PATH → Git for Windows 常见安装位置，探测一次缓存）；无 bash 的 Windows 回退 `cmd /c`；输出 UTF-8 解码、出现替换符回退 GBK | ✅ | `ShellTool.resolveBash/decode` |
| bash 纳入审批与权限体系：加入 `MUTATING_TOOLS`（请求审批模式每条命令先弹卡，卡片展示完整命令）；`harness.shell.enabled` 可禁用（返回禁用错误不注销）；`harness.shell.default/max-timeout-seconds` 超时护栏 | ✅ | `core/ToolRegistry`、`core/AgentLoop`（approval_request 增 `command` 字段）、`config/HarnessProperties.Shell`、`application.yml` |
| 工具执行实时指示：新增 `tool_start` 事件（仅推流不落库，带 callId/name/target），前端显示「正在执行 X」滚动行，对应 `tool_call`（按 callId 匹配）到达后熄灭 | ✅ | `AgentLoop`（sendLive）、`pages/Chat.jsx`（runningTool 状态）、`TaskCard.ToolLive` |
| 工具行聚合（Codex 式）：完成的 `tool_call` 收进一条折叠汇总「已执行 N 项工具 · M 项失败」，展开看原样单行明细；主泳道与 M4 子 Agent 泳道均生效；复杂任务不再被工具行刷屏 | ✅ | `TaskCard.ToolGroup`、`styles.css`（.tool-group/.tool-live） |
| 计划进度收尾修复：进度游标原只按成功工具次数推进，提前出结论时计划停在 0/N——现在循环结束时一次性补齐（正常结论补 `done`、达最大步数补 `failed`〔前端 ✕〕、用户停止保持原状）；M4 多 Agent 同步修复（`agent_done` 时按子任务序号发 `plan_step`，成功/部分成功=done、失败/超时=failed、取消保持原状） | ✅ | `AgentLoop.runLoop`（收尾补齐）、`Orchestrator.emitPlanStep`、`TaskCard.PlanView`（failed 状态） |
| 文件抽屉改挤压式：从全屏遮罩模态改为工作区右侧弹性列（`--drawer-w: min(560px,46vw)`，宽度动画展开），打开时压缩对话内容与输入框（composer 改 sticky 吸底随列收缩，按钮换行不竖排）；Git 悬浮面板经 CSS 变量 `--drawer-offset` 随之左移不被遮挡；Esc/✕ 关闭保留 | ✅ | `components/FileDrawer.jsx`、`pages/Chat.jsx`（chat-flex 布局重构）、`styles.css` |
| E2E/回归：e2e_v2_check、e2e_check（回放比较排除清单加 `tool_start`）、e2e_multiagent_check 全过（真实 LLM）；bash 真实执行（`echo && java -version \| head`、非零退出码 7 → reflect 回灌）与浏览器实测（live 行捕捉 / 计划 3/3→N/N / 抽屉挤压）通过 | ✅ | 仓库根 |

---

### 体验升级五（2026-08-30，图片理解 + 设置导航分区 + 任务变更聚合条/diff 抽屉）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 设置弹窗左侧导航：模态内分「服务 / 模型管理 / MCP 服务器 / 视觉模型」四分区（弹窗加宽到 820px，左侧导航右侧内容，内容区独立滚动），分区记忆 localStorage；composer「管理模型」直达模型管理 | ✅ | `frontend/src/SettingsModal.jsx`（SECTIONS + settings-layout）、`styles.css` |
| 图片随消息下发：输入框 Ctrl+V 粘贴 / 拖拽 / 📷 按钮选图（≤4 张/条，data URL 校验）；>1.5MB 前端 canvas 压到最长边 2000px JPEG；后端二次校验（前缀 data:image/*、单张 ≤6e6 字符）；仅图无文字时空 prompt 自动补位 | ✅ | `pages/Chat.jsx`（addImages/readImageDataUrl/onPaste/onDrop）、`ConversationController.validateImages`、`MessageRequest.images` |
| 视觉模型服务：OpenAI 兼容 `chat/completions` 多模态 content（text + image_url data URL）把图片转述为文字；`harness.vision` 仅后端配置（model 留空=未启用；base-url/api-key 留空继承主模型端点与 Key）；未配置时 Mock 主模型给演示文案、真实模式给配置指引；调用失败返回错误说明——均不中断消息流 | ✅ | `llm/VisionService.java`、`config/HarnessProperties.Vision`、`application.yml` |
| 图片理解注入链路：TaskDispatcher 在路由前**先把原图留存到默认工作区**（`attachments/{会话}/`，`image` 事件落库只存相对路径），再转述图片（`vision` 事件落库可回放），描述追加到 prompt 供 CHAT/TASK/技能全链路使用；模式路由仍用原始 prompt 防描述文本干扰分类；用户消息持久化保持原文 | ✅ | `core/TaskDispatcher`（步骤 3.5 + saveAttachment）、`model/Task`（images + withPrompt） |
| 图片理解 UI：用户气泡上方缩略图——实时轮次用内存 data URL、**回放轮次从 `image` 事件路径取图**（文件被清理显示空白虚线占位，点击经抽屉放大，`noConv` 强制按默认根解析）；Agent 侧「📷 图片 N · 已理解」可折叠块展示转述全文；设置页「视觉模型」分区只读展示状态/模型/端点与配置方法 | ✅ | `TaskCard`（vision/image 事件 + user-images + TurnImages）、`FileDrawer`（noConv）、`SettingsModal`（vision 分区）、`web/TaskController`（/api/config 增 vision 对象） |
| 任务变更聚合条（Codex 式，仅项目会话）：任务结束后从事件流提取本轮触碰文件（file 事件 + fileWrite/fileEdit/fileDelete/docxWrite 的 path，含子 Agent 泳道），与项目 `git status` 清单求交集 → 「N 个文件已更改 +X −Y」，展开逐文件行（状态徽标/路径/±行数），点击行打开右侧抽屉 | ✅ | `TaskCard.TurnDiffBar`（前端聚合，零后端新增） |
| 抽屉「变更」tab：懒加载 `GET /api/projects/{id}/git/diff`，统一 diff 解析为双侧行号 + 新增绿/删除红/hunk 头弱化（color-mix 底色），与 预览/源码 tab 并存（html 三 tab） | ✅ | `FileDrawer.DiffView/diffRows`、`styles.css`（.diff-view/.turn-diff） |
| 体验修复三：fileEdit 行尾自适应——fileRead 展示的内容不含 `\r`，Windows（autocrlf）CRLF 文件按 `\n` 构造的 old_string 匹配失败死循环重试；现精确匹配未命中且文件为 CRLF 时把 old/new 换行归一为 `\r\n` 重试，新写入行遵循文件行尾（成功文案标注「已自动适配 CRLF 行尾」）；LF 文件与精确匹配路径零变化 | ✅ | `tools/FileEditTool`（确定性单测验证：CRLF 匹配/精确路径/未找到报错/LF 不受影响） |
| E2E/验证：新增 `e2e_vision_check.py`（config 暴露/vision 事件/原文落库/空 prompt 补位/超 4 张与非图片 400/回放，Mock 与真实双模式）；回归 e2e_v2_check、e2e_check、e2e_multiagent_check（Mock）全过；浏览器实测：合成粘贴出 chip → vision 块 + 气泡缩略图、Mock 任务 → 聚合条「+39 −5」→ 抽屉红绿 diff + 行号 + GitPanel 让位、刷新回放复现、设置四分区导航 | ✅ | 仓库根 + `e2e_vision_check.py` |

---

### 体验修复三 + 体验升级六（2026-09-03，事件顺序渲染 + 模式路由加固 + 上下文容量显示与两级压缩）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 体验修复三（事件顺序）：执行计划卡片从 agent 消息顶部硬编码改为插在 `plan` 事件的 chronological 位置——图片理解等先行事件不再被计划卡压到下面，「是什么顺序就是什么顺序」；历史回放同路径还原 | ✅ | `components/TaskCard.jsx`（PlanView 移入 preTools/postTools 渲染位） |
| 体验修复三（模式路由）：「你好，你是什么模型」这类身份/模型问句被 LLM 分类误判 TASK——新增快路径：剥掉开头寒暄/礼貌引导词（你好/请问/问一下…，可循环剥 3 层）后命中 IDENTITY_Q 全锚定正则（你是什么模型/你用的什么大模型/你是ai吗 等）直判 CHAT；分类解析改为取 CHAT/TASK 先出现者（修复「CHAT…不是TASK」长输出含 TASK 被误判）；LLM 调用失败重试一次仍降级 TASK | ✅ | `core/ChatModeRouter`（CHAT_LEADIN + IDENTITY_Q + parseMode） |
| 上下文容量徽标：composer 栏「⚡ 已用/窗口」三色状态（<75% ok / 75–90% warn / >90% full 呼吸），点击弹出明细面板（窗口/当前估算/摘要状态）+「立即压缩历史」按钮；数据源 = 切会话/切模型/轮次结束拉 `GET /api/conversations/{id}/context`，`context_usage` 事件实时校正；徽标取 **max(上一轮真实 usage, 基线估算)**——任务后停留在真实用量，不再随轮次结束回落到窄口径估算（2026-09-05 修订） | ✅ | `pages/Chat.jsx`（ContextMeter + ctx 状态）、`api.js`、`styles.css`（.ctx-meter/.ctx-pop，--ok/--warn/--err 主题 token） |
| Token 计数闭环：请求体加 `stream_options.include_usage` 采集真实 prompt_tokens（`harness.context.request-usage` 可关），`ChatResult.Usage` 贯通；无 usage 时以启发式估算兜底（CJK≈0.6/字符、其余≈0.3/字符〔DeepSeek 官方口径〕，每条消息 +8 结构开销，`llm/TokenEstimator`）；Mock 填充估算 usage 保证演示链路可跑 | ✅ | `llm/ChatResult`、`llm/OpenAiCompatChatClient`（parseStream 采 usage chunk）、`llm/MockChatClient`（chatInner 包装）、`llm/TokenEstimator` |
| 模型上下文长度：`ModelProfile.contextLength` + `model_profiles.context_length` 列（addColumnIfMissing 迁移）+ `harness.llm.context-length`（默认 131072）与 `harness.models[].context-length`；设置页模型表单可填、列表 pill 展示；`ModelRegistry.effectiveContextLength/contextLengthOf` 统一兜底解析 | ✅ | `model/ModelProfile`、`core/memory/Db`、`core/memory/ModelStore`、`core/ModelRegistry`、`web/ModelController`、`SettingsModal.jsx`、`application.yml` |
| 对话级压缩（软阈值自动 + 手动）：发送前估算基础上下文（系统提示+知识+摘要+逐字窗口）超窗口×soft（0.8）→ LLM 把水位线后旧消息压成结构化摘要（`[ROLE=COMPACTOR]`，与既有摘要合并），存 `conversations.ctx_summary` + 水位线 `ctx_summary_until_id`；历史构建改为「摘要 user 消息 + 水位线后逐字消息（仍受 history-limit 保护）」，CHAT/TASK/技能全链路统一生效；压缩成功后回写 `last_prompt_tokens` 为压缩后估算（否则旧真实用量会让预检反复触发压缩）；压缩失败绝不阻断消息流 | ✅ | `core/context/ConversationCompactor`、`core/context/ContextService`（buildHistory/maybeCompact/compactIfOver）、`core/memory/ConversationStore`（ContextSummary）、`core/TaskDispatcher`（步骤 3.6，当前消息延后到压缩后落库且保持原文） |
| 任务级压缩（机械占位替换）：AgentLoop 每轮调用前估算 msgs+预留(4096) 超软阈值 → 旧轮 `role=tool` 输出**原位替换**为「[已压缩] 前200字符+原长度」占位符（保留最近 `keep-recent-rounds` 轮完整；<300 字符的小输出不替换防负压缩）；不增删消息、tool 消息配对结构零风险、零 LLM 成本 | ✅ | `core/context/ToolOutputCompactor`、`core/AgentLoop`（compactIfNeeded） |
| 400 超限兜底：chatRound 异常命中上下文超限签名（context_length_exceeded/maximum context length 等）→ 紧急压缩（保留 1 轮 + hardTrimHistory 裁剪最旧逐字历史，任务提示绝不动）后走统一重试 | ✅ | `core/AgentLoop`（compactAggressive/hardTrimHistory/isContextOverflow） |
| 新 SSE 事件：`context_compact {before, after, scope: conversation\|task}`（落库可回放，TaskCard 渲染「🗜 上下文已压缩」行）；`context_usage {promptTokens, estimated}`（仅推流不落库，前端实时校正徽标） | ✅ | `AgentLoop`（emitUsage/compact 事件）、`ChatExecutor`（CHAT 轮 usage）、`TaskCard.EventView`、`Chat.jsx` onEvent |
| 新 API：`GET /api/conversations/{id}/context?modelId=`（容量状态）；`POST /api/conversations/{id}/compact`（手动压缩，返回 before/after） | ✅ | `web/ConversationController` |
| E2E/验证：新增 `e2e_context_check.py`（容量状态/context_usage/小窗口下自动压缩+摘要承接/手动压缩/压缩事件落库回放，Mock 与真实双模式；压缩触发项需 `HARNESS_LLM_CONTEXT_LENGTH=3000` 小窗口，默认窗口自动跳过）；Mock 实测：6 条长消息触发自动压缩 2704→1677、手动压缩 2328→851、压缩后续聊正常；浏览器实测：徽标渲染/弹出面板/压缩行回放；ToolOutputCompactor 独立确定性验证（替换/护栏/幂等/结构不变/紧急裁剪） | ✅ | 仓库根 + `e2e_context_check.py` |
| 徽标修复（2026-09-05，真实 usage 持久化 + 估算口径对齐）：修复「任务后徽标回落到几百 token」——① 真实 prompt_tokens 持久化到 `conversations.last_prompt_tokens`（主泳道每轮 LLM 调用后写入，子 Agent 泳道不写防带跳；手动/自动压缩成功后回写为压缩后估算），`/api/conversations/{id}/context` 返回 max(真实, 估算)；② 基线估算从「CHAT 系统提示+纯文本历史」对齐为真实请求口径：TASK = AgentLoop 系统提示（static `baseSystemPrompt` 复用同一模板）+ **工具定义 JSON（约 2.5k token，此前完全漏算）**+ 知识 + 历史，CHAT/TASK 取较大者保守估计；③ ASCII 系数 0.25→0.3、每条消息结构开销 +4→+8。真实 LLM 实测：任务轮 usage 2303→9104 逐轮递增，任务后徽标停在 9104 不回落；新会话徽标即显示 ~2.1k 基线 | ✅ | `core/memory/Db`（last_prompt_tokens 列）、`core/memory/ConversationStore`（updateLastPromptTokens/lastPromptTokens）、`core/AgentLoop`（emitUsage 主泳道写入 + baseSystemPrompt）、`core/ChatExecutor`、`core/context/ContextService`（effectiveUsed/estimateBaseline）、`core/ToolRegistry`（specsJson）、`llm/TokenEstimator` |

---

### 体验修复四（2026-09-05，工具分组可读性 + 文件 chip 云 + 长任务反思检查点）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 工具分组折叠态可读：头部新增按工具类型的计数分解（「写入×10 · 读取×8 · 执行命令×4」，取计数前 5 类），不展开即可知道执行了哪些工具；展开列表相邻同类调用去重为「×N」徽标、目标列补全 bash 命令/url/检索词（与后端 toolTarget 同口径）、行高压缩、内部滚动（max-height 300px）——几十次调用的长任务不再撑爆页面 | ✅ | `components/TaskCard.jsx`（ToolGroup brief/dedupeTools/toolTargetOf/ToolRow ×N）、`styles.css`（.tool-group-brief/.tool-repeat/.tool-group-body） |
| 文件产物 chip 云：连续 `file` 事件归组为一个 flex-wrap 容器（`EventFlow` 渲染器同时服务主泳道与子 Agent 泳道，plan 事件仍插在原位渲染计划卡），文件多时横向换行不再一条一行刷屏；任务结束后超过 12 个收敛为「+N 个文件」chip，点击展开全部 | ✅ | `components/TaskCard.jsx`（EventFlow/FileChips/FileChip）、`styles.css`（.file-chips/.file-chip.more） |
| 主 Agent 长任务不再因总轮数、工具总数或读取次数停止；`max-steps` 改为每 N 轮注入一次非终止反思检查点。死循环由局部调用熔断处理：相同工具+参数在无新进展时只允许一次重试，之后跳过该次调用并要求换策略，主任务继续。子 Agent 保留局部步数上限，耗尽后结果交回主 Agent | ✅ | `core/AgentLoop`、`core/policy/ToolLoopGuard`、`application.yml`/`HarnessProperties` |
| 定向验证覆盖：主任务越过 `max-steps` 仍继续、探索超过旧 12 次阈值仍可读取、相同无进展调用被局部跳过后可执行替代方案、连续失败只提醒不终止、Bug 修复/需求分析/短视频文案三类任务 | ✅ | `AgentLoopPolicyTest`、`ToolLoopGuardTest` |

> 当前规则覆盖下文历史说明中“主 Agent 受 max-steps/工具次数预算终止”的旧表述；这些数值现在只用于反思或评测，不是主任务停止条件。

### 体验升级七（2026-09-05，子智能体体系 + 会话实时续看 + 扇出收紧 + grep）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 子智能体体系：主 Agent 经 `spawnAgent(agent, task)` 按需派出专职子智能体（内置「代码探索」只读调研 / 用户自建法律助手等），档案 prompt 作为最高优先级指令注入受限循环（guided 模式跳过 Planner），工具按白名单过滤且子智能体永不可再 spawnAgent（防递归）；agent_spawn/agent_done 手工发出，泳道事件经 Emitter 自动带 agent 字段进父 transcript（落库回放、前端泳道零改动） | ✅ | `core/agents/SpawnAgentTool`（spec 工具，execute 不被调用）、`core/agents/AgentLibrary`（@PostConstruct 播种内置档案 + 注册 spec）、`core/AgentLoop`（runLoop 特判 spawnAgent + `spawnSubAgent` 方法 + systemPrompt 子智能体清单段）、`core/multi/SubAgentConfig`（+allowedTools/canUse）、`core/ToolRegistry.specsFor`（白名单过滤且排除 spawnAgent） |
| agent_profiles 表 + CRUD + 设置分区：id/name(UNIQUE)/description/prompt/tools_json/max_steps/enabled/origin(builtin\|user)；内置档案可禁用不可删、删除后重启自动播种恢复（禁用状态存 DB 不重置） | ✅ | `core/memory/Db`（agent_profiles 建表）、`model/AgentProfile`、`core/memory/AgentProfileStore`、`web/AgentController`（/api/agents，同名 400/内置删 400）、`SettingsModal.jsx`「子智能体」分区（列表/启停/新增表单） |
| grep 工具：沙箱内正则/字面量搜索（相对路径:行号:内容，≤50 条），跳过 node_modules/.git/target 与二进制、文件名 glob 过滤；只读免审批；供主 Agent 与代码探索子智能体定位代码 | ✅ | `tools/GrepTool`（@Component 自动注册）、`ToolRegistry.BASE_TOOLS`（+grep） |
| 会话实时续看（StreamHub）：每个运行中会话一个 Hub（事件缓冲含 delta + 订阅者广播 + cancelFlag）；挂接点在各发送处（Emitter.send/sendLive、ChatExecutor/SkillExecutor/TaskDispatcher 裸发送点）；`GET /api/conversations/{id}/live` 重放缓冲+续流（空闲发 hub_idle）；会话列表带 `running` 标志；侧栏转圈 + 3s 轮询；运行中再发消息 409 | ✅ | `core/stream/StreamHub`、`web/ConversationController`（send 接 hub + /live + /stop + running + 409）、`pages/Chat.jsx`（applyEvent 提取共用 + maybeAttachLive）、`Sidebar.jsx`（.conv-spin + 轮询）、`api.js`（streamConversationLive/stopConversation） |
| 取消语义显式化：SSE 发送失败不再取消会话任务（刷新/断开后任务后台继续跑完并落库），停止只能经 `POST /api/conversations/{id}/stop` 置 hub.cancelFlag（Emitter 的 shared 通道 + Orchestrator sharedCancel 复用同一标志）；无 Hub 的 /api/task 旧入口保持「发送失败即取消」旧语义 | ✅ | `core/AgentLoop.Emitter`（doSend 仅 shared==null 时置 cancelled）、`ChatExecutor`/`SkillExecutor`（hub 参数 + cancelFlag 检查）、`Orchestrator`（hub.cancelFlag 作为根 sharedCancel） |
| 扇出门槛收紧：「生成 a-demo.md 和 b-demo.md」这类简单小文件任务不再派子 Agent——真实 LLM 判据改为「每个子任务各自需要多步加工的实质性工作才 parallel」（DECOMPOSER prompt 加正反例）；Mock 门槛从「≥2 类动词 + ≥2 文件」收紧为「≥2 类动词 + **≥3 文件**」；`harness.multi-agent.auto-decompose`（默认 true）可整体关掉计划期自动拆分（spawnAgent 不受影响） | ✅ | `core/Planner.decompose`（prompt 判据）、`llm/MockChatClient.decomposeJson`、`config/HarnessProperties.MultiAgent`（autoDecompose）、`application.yml` |
| 工具分组展开压扁修复：`.tool-group-body`（flex column + max-height + overflow）子项被 flex-shrink 压缩——加 `> * { flex-shrink: 0 }` 改为滚动，max-height 300→460px | ✅ | `frontend/src/styles.css` |
| E2E：新增 `e2e_subagent_check.py`（21 项：CRUD/内置保护/spawnAgent 泳道与回放/两文件不扇出）与 `e2e_live_check.py`（12 项：hub_idle/running//live 重放/409/跨连接审批/复位/stop 兜底）；e2e_multiagent 用例 [3] 改为实质性三交付物任务（三个简单文件在新判据下走单 Agent），`e2e_v2_check.py` 加 workspace 残留清理；Mock+真实双模式全过 | ✅ | 仓库根 |

### 体验修复五（2026-09-05，侧栏转圈左置 + 任务过程折叠块 + 模型编辑）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 侧栏转圈移到会话行最左：运行中会话的转圈从标题尾部改为 `.conv-item` 行首 flex 子项（转圈 → 标题 → 元信息），项目会话与普通会话共用 `renderConvItem` 一处生效——扫一眼就知道哪个会话在跑任务 | ✅ | `Sidebar.jsx`（renderConvItem）、`styles.css`（.conv-spin 去 margin-left/vertical-align，颜色 text-2） |
| 任务过程折叠块（zcode 式）：TASK 轮的计划/思考/步骤说明/工具/文件/审批卡/子 Agent 泳道/黑板全部收进一个「✳ 思考与执行中…/已思考与执行」可展开容器——运行中自动展开、done 后自动收起，头部带执行统计（N 项工具 · M 个文件）；只有最终结论 + 变更聚合条 + 存为技能常显在容器外。过程内部按真实时间序交织渲染（`ChronoFlow`）：思考段渲染为带 100 字摘要预览的折叠行（每步操作的依据随时可查，展开看全文）、step_commentary 正文常显、连续 tool_call 仍归组折叠、连续 file 仍归组 chip 云、审批卡在发生原位（运行中容器强制展开不会错过）；CHAT 直答轮（无过程事件）保持「思考面板 + 直答」原形态 | ✅ | `components/TaskCard.jsx`（ProcessBlock/ChronoFlow/ThinkRow，替换 preTools/mainTools/postTools 三段式）、`styles.css`（.process-block/.process-head/.process-body/.think-row*） |
| 模型可编辑：用户模型行新增 ✎ 编辑按钮，与新增共用表单（编辑态 API Key 标注「留空保留原 Key」、思考模型留空即清除、上下文长度留空保持不变）；`ModelProfile.View` 增加 `baseUrl` 字段供编辑表单预填端点（apiKey 仍不回传）；删除正在编辑的模型时自动收起表单 | ✅ | `SettingsModal.jsx`（openEdit/closeForm/submit 分支/编辑按钮）、`model/ModelProfile.View`（+baseUrl）、`web/ModelController.toView` |
| 浏览器实测：运行中侧栏转圈最左显示（审批挂起长任务截屏确认）；过程块运行中展开（计划/思考预览/说明/工具时间序交织）→ done 自动收起为「已思考与执行 N 项工具 · M 个文件」+ 结论常显；刷新回放同构（收起态 + 可再展开）；深度思考轮思考行预览与展开正常；模型编辑全链路（预填含 baseUrl/改名/加思考模型/清思考模型/删除）通过 | ✅ | 仓库根 |

### 体验修复六（2026-09-05，结论自成一体 + 交付前验证 + 项目对话折叠）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 最终结论自成一体：系统提示新增规则 8——结论必须面向用户、不依赖中间过程：① 两三句总结完成了什么；② 产出/修改文件清单（相对路径 + 一句话说明）；③ 验证方式与结果、使用方式；④ 遗留事项/建议。禁止延续中间步骤的编号或以「然后/接着」开头（此前模型会把最终答复写成工具轮说明的「续篇」，用户看到的是从第 3 条列表中间开始的半截话） | ✅ | `core/AgentLoop.baseSystemPrompt`（规则 6 拆分扩写） |
| 交付前验证：系统提示新增规则 7——任务涉及代码工程（改动范围内有 pom.xml/build.gradle/package.json/go.mod 或已有测试）时，给结论前必须用 bash 实际验证：Java 跑 `mvn -q clean compile` 这类编译、前端跑 `npm run build`/lint、有测试只跑相关用例；失败要修复重验直到通过；纯静态页面/文档用 fileRead 回查即可不强跑构建。真实 LLM 实测：改一行 Java 后主动编译 → 编译失败（旧版 maven-compiler-plugin 不识别 release 17）→ 自行升级插件修复 pom → 重编译通过 → 运行程序确认输出，结论中说明验证过程 | ✅ | `core/AgentLoop.baseSystemPrompt`；CHAT 模式走 `ChatModeRouter.chatSystemPrompt` 不受影响 |
| 项目对话列表折叠/展开：项目行新增 ▶ 折叠箭头（点击只切换展开态，不触发行点击的「打开最近会话」），状态按项目 id 持久化 localStorage（`harness.proj.open`，undefined=展开）；当前会话所在项目**强制展开**（避免正看着的会话被藏起来）；点击折叠的项目行仍打开最近会话并自动展开 | ✅ | `Sidebar.jsx`（projOpen 状态 + toggleProj + expanded 判定）、`styles.css`（.proj-chev，旋转 90°） |
| 回归：e2e_v2_check、e2e_check（HARNESS_BASE 指向新构建实例，真实 LLM + 新提示词）全过；浏览器实测项目折叠/持久化/自动展开、结论结构化渲染（总结/文件清单/验证结果分节）通过 | ✅ | 仓库根 |

### 体验升级八（2026-09-05，计划模式 + askUser 澄清 + 按计划实现 + 停止排水）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 权限第四档「计划模式」（approval=plan）：输入框权限选择框新增选项；计划模式 = **只读调研 + askUser 澄清 + 计划文档结论**，跳过模式路由（泛泛提问也强制任务路径）/显式技能（技能含写步骤，计划模式优先级更高）/意图命中/多 Agent 拆分/Planner（执行型计划卡与只读承诺冲突），强制单 Agent；`normalizeApproval` 白名单 + plan；`TaskDispatcher` 发持久化 `plan_mode` 事件标记后直接 `agentLoop.execute` | ✅ | `web/ConversationController`（normalizeApproval/stop 排水/question 端点）、`core/TaskDispatcher`（4.5 计划分支）、`pages/Chat.jsx`（Select 第 4 项） |
| 只读工具白名单：计划模式 specs = `planModeSpecs()` = `specsFor(PLAN_TOOLS)`（fileRead/listFiles/grep/httpFetch/askUser）+ spawnAgent spec（specsFor 恒排除需手工并回）；**bash 禁用**（可写文件无法安全只读）、mcp__* 不提供（外部工具语义不可知）；子智能体白名单收敛为「档案白名单 ∩ PLAN_TOOLS」（spawnSubAgent），子 Task 继承 approval=plan 自动带计划规则 | ✅ | `core/AgentLoop`（PLAN_TOOLS/planModeSpecs/spawnSubAgent 收敛） |
| askUser 澄清提问（zcode AskUserQuestion 式）：新 `core/question/` 包——`AskUserTool`（spec-only，参数 question/options(2~4)/recommended 下标）+ `QuestionGate`（仿 ApprovalGate，回传 String；@PostConstruct 注册 spec）；AgentLoop 特判（planMode 主泳道）：发持久化 `question_request {callId, question, options[], recommended}` → 挂起等待（300s，超时=null → 错误回灌「请自行决策继续」）→ 答复作 `success("用户答复：…")` 回灌 + `tool_call`（同 callId，前端据此判已答）；`POST /api/conversations/{id}/question {callId, answer}` 应答端点（无挂起 → 409） | ✅ | `core/question/AskUserTool`、`core/question/QuestionGate`、`core/AgentLoop`（askUser 特判）、`web/ConversationController`（answer 端点） |
| 问题卡片 UI（时间序原位渲染）：`QuestionCard`——问题文本 + 选项按钮（推荐项带「推荐」徽标置顶）+ 自由输入行（Enter/按钮提交）；已答态由同 callId tool_call 判定并展示答复文本（回放同构）；运行中可作答、结束后只读 | ✅ | `components/TaskCard.jsx`（QuestionCard/ChronoFlow question case/toolCallById 映射）、`styles.css`（.question-card/.q-opt/.q-rec/.q-free） |
| 按计划实现：计划轮次（含 plan_mode 事件）结论区显示「🚀 按计划实现」按钮（与存为技能并排）；点击 → 权限选择框自动切「完全访问」→ 计划全文作为新任务发送（`sendText` 从 send() 抽取共用），走标准 TASK 流水线由 Planner 拆步骤实施；计划文档不落盘（对话历史即存档） | ✅ | `pages/Chat.jsx`（implementPlan/sendPayload 抽取）、`components/TaskCard.jsx`（isPlanTurn/turn-actions） |
| 系统提示计划分支：[MODE=PLAN] 标记（Mock 检测锚点）+ 规则 10~12——只读承诺、askUser 使用规范（≤3 问/选项+推荐/超时自行决策）、计划文档六段结构（目标/现状调研结论（文件+行号）/实施方案/分步实施清单（含文件）/风险与验证/待确认事项） | ✅ | `core/AgentLoop.baseSystemPrompt`（approval 参数已有，签名零变化，ContextService 估算自动跟随） |
| 停止排水（顺带修既有缺口）：`POST /stop` 原本只置 cancelFlag——挂起中的审批要等满 5 分钟超时；现 ApprovalGate/QuestionGate 各加 `cancelAll(conversationId)`，stop 时统一排水（审批按拒绝、提问按未答复收尾）；同时修复 `Emitter.doSend` 在 cancelled 时跳过发送导致「（已停止）」结论/收尾事件到不了仍在连接的客户端——现在取消后照常尝试推送（断开时发送异常仍被吞掉） | ✅ | `core/ApprovalGate.cancelAll`、`core/question/QuestionGate.cancelAll`、`web/ConversationController.stop`、`core/AgentLoop.Emitter.doSend` |
| E2E/验证：新增 `e2e_plan_check.py`（16 项，Mock 确定性 + 真实自适应）：plan_mode 事件/泛泛提问不走 CHAT/问题选项与推荐/跨连接应答回灌/结论计划结构/**全程工具 ⊆ 只读集**/回放还原/停止排水（<30s 收尾且无真实答复）；Mock 16/16、真实 LLM 15/15（模型主动提问 2 次全链路）；回归 e2e_v2/check/multiagent/live/subagent/context 全过；浏览器实测：计划模式选项 → 过程块（思考预览+只读工具行）→ 问题卡（推荐徽标）→ 点推荐项 → 结构化计划文档 + 按计划实现按钮 → 点击后权限自动切完全访问并完成实施（index.html/style.css 深色模式落盘与计划一致） | ✅ | 仓库根 + `e2e_plan_check.py` |

### 体验修复七（2026-09-06，计划模式只读加固 + 计划确认双选项 + 计划卡片/抽屉）
> 真实 LLM 验证暴露三问题：① 系统提示词固定文案描述了 fileEdit/bash 等写类工具，DeepSeek 照提示词文本发起 fileEdit 调用（tools 数组没有也会凭描述构造），主循环对模型返回的工具名无白名单校验，9 次写操作全部执行；② 最终结论把历史轮次的旧结论原样复述成「计划」（历史污染）；③ 「按计划实现」把计划全文回显到输入气泡，且轮内按钮交互笨重。逐项修复 + 交互重构如下。
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 只读硬闸门（runLoop 主泳道防御性校验，与子智能体白名单检查并排）：planMode 下模型返回的工具名 ∉ PLAN_TOOLS 且非 spawnAgent → **不执行**，错误回灌「计划模式只读不允许…请输出计划文档」+ reflect 行；这是「不改任何文件」承诺的最终保障（无论 specs/提示词怎么漏） | ✅ | `core/AgentLoop`（runLoop 计划闸门分支） |
| 系统提示计划分支重构（消除写工具诱导源）：计划模式不再输出规则 2 的 fileWrite/fileEdit/bash 工具文案与扩展工具清单，改列只读白名单 + 明确「写类工具均不可用，调用会被拒绝」；规则重排 1~7（含新反复述规则 6：计划必须针对本轮请求，严禁复述历史轮次结论）；常规分支规则 1~9 原样保留；[MODE=PLAN] 标记保留（Mock 锚点）；子智能体清单两种模式都输出；PLAN_TOOLS 移除不存在的 grep | ✅ | `core/AgentLoop.baseSystemPrompt`、`PLAN_TOOLS` |
| 计划确认双选项决策条（替代输入框，仿 zcode/codex）：最后一个轮次为已完成的计划轮（plan_mode + 结论）且未补充/放弃时，composer 整体替换为决策卡——「🚀 执行此计划」「✏️ 补充细节信息」+ 弱化的「先不执行，手动输入」；补充细节 = 恢复输入框（顶部提示条 + 占位文案，✕ 可返回决策），发送后退出补充模式；切会话重置三态 | ✅ | `pages/Chat.jsx`（planPending 推导/plan-decision 渲染/send 复位 planRefine）、`styles.css`（.plan-decision/.plan-dec） |
| 执行此计划（planText 程序内下发，不回显计划全文）：点击决策条 → 权限自动切「完全访问」→ 气泡只显示「执行此计划」，计划全文经 `MessageRequest.planText` 下发；TaskDispatcher 注入执行提示词（落库用户消息保持原文，走任务路径并跳过模式路由/技能/多 Agent 拆分——计划文档本身即步骤清单）；`Task` 增加 `planText` 字段（withPrompt 透传）；TaskCard 轮内「按计划实现」按钮移除（决策条接管） | ✅ | `model/Task`、`web/ConversationController`（MessageRequest/Task 构造）、`core/TaskDispatcher`（3.8 注入 + 4.6 计划实施分支）、`pages/Chat.jsx`（implementPlan） |
| 计划文档卡片 + 抽屉：计划轮结论不再整篇铺开——渲染「计划」卡片（📋 标题取首个 Markdown 标题 + 截断预览（约 132px 渐隐）+「查看完整计划 →」）；点击打开右侧挤压式 `PlanDrawer`（与文件抽屉同壳，Markdown 全文渲染，Esc/关闭收起，Git 面板经 --drawer-offset 让位）；回放同构 | ✅ | `components/TaskCard.jsx`（PlanDocCard）、`components/PlanDrawer.jsx`（新）、`pages/Chat.jsx`（planDrawer 状态）、`styles.css`（.plan-doc-card/.plan-drawer-body） |
| E2E/验证：`e2e_plan_check.py` 扩到 22 项——新增「计划模式跳过 Planner（无 plan 事件）」与场景 4「执行此计划」（planText 注入后 done 正常、实施轮无 plan_mode、用户消息落库仅「执行此计划」原文）；Mock 22/22；回归 e2e_v2/check/multiagent 全过；浏览器实测：计划任务 → 问题卡答复 → 计划卡（截断预览+渐隐）→ 决策条替代输入框 → 查看完整计划抽屉（挤压式全文）→ 执行此计划（气泡仅四字、实施落盘、权限切完全访问、输入框恢复）→ 补充细节（提示条输入 → 新计划 → 决策条重现）→ 刷新回放同构 | ✅ | 仓库根 + `e2e_plan_check.py` |

### 体验修复八（2026-09-06，前端交互清理：确认弹窗组件化 + 表单重置 + 样式统一）
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 通用确认弹窗（替代全部 window.confirm 原生弹窗）：`ConfirmDialog.jsx` 导出程序化 `confirmDialog({title, message, confirmText, danger})`（Promise→boolean，Esc/点遮罩=取消）+ `ConfirmHost`（App.jsx 挂载一次，模块级总线 push/resolve）；danger=true 确认键红色警示态（删除类）；Host 未挂载兜底原生 confirm。替换 7 处：知识删除 / 侧栏删除会话 / 解除项目 / 模型删除 / MCP 删除 / 子智能体删除 / 技能删除 | ✅ | `components/ConfirmDialog.jsx`（新）、`App.jsx`、`pages/Knowledge.jsx`、`Sidebar.jsx`、`SettingsModal.jsx`、`pages/Skills.jsx`、`styles.css`（.confirm-modal/.confirm-title/.confirm-message） |
| 知识编辑表单样式对齐「手动添加」：编辑态原来是裸 input/textarea（无 label 无主题样式，藏在卡片里很突兀），重构为与添加表单同结构——分类下拉 + 标题 + 内容均带 form-label，样式走新 `.kc-edit`（与 .add-knowledge 输入同款底色/边框/圆角）；同时编辑/添加取消时清空草稿（编辑草稿由 startEdit 重新预填，不残留） | ✅ | `pages/Knowledge.jsx`（kc-edit 结构/closeAdd/closeEdit）、`styles.css`（.kc-edit） |
| 表单取消/提交后一律重置（全盘检查）：模型表单的 `closeForm()` 原来只收起不清空——编辑模型 A → 取消 → 点「添加模型」会残留 A 的数据（用户报障根因）；现 closeForm 重置为空默认值（成功提交也统一走它），MCP/子智能体取消按钮同步重置各自表单；技能新建取消清空粘贴内容；验证：三处表单「填一半 → 取消 → 再添加」均为空默认值 | ✅ | `SettingsModal.jsx`（EMPTY_MODEL_FORM/EMPTY_MCP_FORM/EMPTY_AGENT_FORM 常量 + closeForm + 两处取消）、`pages/Skills.jsx` |
| 技能页按钮样式统一：页头「⬆ 导入 .md」「＋ 新建技能」与新建表单「取消」「创建」、页脚「返回对话」原用侧栏 `.side-btn`（透明底/透明边框，在页面里像裸文本）；统一换 `.btn` / `.btn primary`（有边框底色，与全站按钮一致） | ✅ | `pages/Skills.jsx`、`.skills-header .side-btn` 覆盖规则自然失效 |
| 侧栏项目折叠修复（选中项目会话时无法折叠/展开）：原 `expanded = 手动状态 || activeInside` 强制展开恒真——当前会话所在项目点折叠箭头无反馈；改为纯手动控制（`projOpen[p.id] !== false`），「避免正看的会话被藏」改为**进入会话时一次性自动展开**（ref 记录已处理会话 id，3s 轮询不会抢回手动折叠态）；点击折叠态项目行打开最近会话时顺带展开 | ✅ | `Sidebar.jsx`（expanded 推导 + autoExpandedFor ref effect + openProject 顺带展开） |
| 验证：浏览器实测——知识编辑态样式与添加一致/编辑取消回显正常、删除确认弹窗（知识+技能+模型）主题内渲染、模型/MCP/子智能体表单取消重置、技能页四按钮样式、项目会话选中后折叠↔展开往返 + 折叠态点项目行自动展开；`e2e_plan_check.py` 22/22 回归通过（后端零改动） | ✅ | 仓库根 |

### 体验升级九（2026-09-06，消息气泡操作：复制 + 编辑重发（撤回到输入框））
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 用户气泡悬停操作行：鼠标悬停用户气泡，气泡下方右侧显现两个小图标——⧉ 复制（**任何轮次都有**，点后 ✓ 反馈 1.2s，clipboard API + execCommand 兜底）；✏ 编辑（**仅最后一个带正文的轮次**，运行中的 live 轮也算） | ✅ | `components/TaskCard.jsx`（BubbleActions）、`styles.css`（.bubble-actions/.bubble-act） |
| 编辑形态选定「撤回到输入框」而非气泡内编辑：composer 具备图片/技能/文件引用/模型/权限全套能力；项目文件引用以内联 chip 展示（悬停显示完整路径），底层仍以 `@{相对路径}` 保留顺序，发送时仅模型提示词按原位展开受限文件内容，气泡和落库消息保留原文 | ✅ | `pages/Chat.jsx`、`components/TaskCard.jsx`、`core/FileReferenceExpander.java` |
| 编辑重发 = 截断 + 正常发送：点发送先 `DELETE /messages/{id}` 截断该用户消息及其后全部消息（含 Agent 回复与「已停止」残留），本地轮次列表按服务端重建后再走既有 sendPayload——上下文不再包含被撤回轮；落库消息 id 出现空洞是预期 | ✅ | `pages/Chat.jsx`（send 编辑分支）、`api.js`（truncateMessagesFrom） |
| 运行中编辑（用户核心场景：执行中发现要补充）：点 ✏ 时若任务在跑，自动 POST /stop + 断开本地两条流，再轮询 `GET /conversations/{id}` 的 `running` 字段等服务端收尾（「已停止」结论落库完成）后才进入编辑态——防止删除后停止消息乱序复活；20s 超时报错不进入 | ✅ | `pages/Chat.jsx`（waitUntilStopped + startEdit）、`web/ConversationController`（GET 元信息增 running 字段） |
| 后端截断端点：`ConversationStore.deleteFrom`（`DELETE FROM messages WHERE conversation_id=? AND id>=?`，返回删除条数，0=幂等）；控制器 404（会话不存在）/409（运行中拒绝，前端已先停但防御兜底）；截断范围恒在对话摘要水位线之后（水位线只指向更早消息），不影响已生成的 ctx_summary | ✅ | `core/memory/ConversationStore`、`web/ConversationController`（DELETE /api/conversations/{id}/messages/{messageId}） |
| 计划轮兼容：最后一轮是计划轮（决策条替代输入框）时点 ✏ → 决策条撤下（planDismissed 快照进编辑态），取消编辑后决策条原样恢复；E2E：`e2e_edit_check.py` 18 项（两轮对话截断/幂等/404/续聊上下文不含被撤回轮/running 字段）；浏览器实测：悬停图标分区、复制剪贴板精确匹配、计划轮编辑进出、空闲编辑重发、**运行中编辑**（自动停止收尾→截断→重发→文件产出）全链路、刷新回放一致；回归 e2e_v2/e2e_check/e2e_plan(22)/e2e_multiagent(23) 全过 | ✅ | 仓库根 + `e2e_edit_check.py` |

### P0 自进化闭环（2026-09-12，自动技能合成 + 隔离沙箱校验 + 进化仪表盘）
> 落地 `docs/plan/P0开发文档-自进化闭环收口.md`（M3 的按现状修订版）。E2E：`e2e_evolution_check.py` 26/26（Mock）；回归 e2e_skill_check 24/24、e2e_v2_check、e2e_plan_check 22/22 全过。
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| RecurrenceTrigger 合成闸门（P0-F1）：TaskDispatcher finally 块异步挂接（虚拟线程 + AtomicBoolean 单飞），usedSkill 由显式/意图技能分支打标（走技能路径不再合成）、approval=plan 跳过；成功任务写 task_vectors 归簇（有 Embedding Key 用余弦，无 Key 降级字符 bigram Jaccard），簇内成功 ≥ recurrence-threshold（默认 3）且该簇无 active 草稿才合成 | ✅ | `core/evolution/EvolutionPipeline`、`core/evolution/TaskVectorStore`（task_vectors 表）、`core/TaskDispatcher`（usedSkill 标记 + finally 挂接） |
| SkillSynthesizer 流水线化（P0-F2）：DraftMeta 增加 testInput（「相似但不相同」校验输入，LLM 生成 + 确定性兜底）；buildSkillMarkdown 从 SkillController 抽移为共用渲染（手动「存为技能」与新流水线同一产物形态）；Mock 复用既有 `[ROLE=SKILL_WRITER]` 锚点，mockSkillMeta 的 test_input 刻意包含触发词（保证无 Key 关键词路由闭环可命中） | ✅ | `skill/SkillSynthesizer`、`web/SkillController.fromTrace`（委托）、`llm/MockChatClient.mockSkillMeta` |
| SkillValidator 隔离沙箱校验（P0-F3）：临时工作区 `workspace/.validator/draft-{id}/`（Sandbox 以临时目录为 root，结束后递归删除）；steps 型填参后逐步执行、prompt 型受限工具白名单（fileWrite/fileEdit/fileRead/listFiles/docxWrite）跑 ≤4 轮 mini 工具循环（手工回灌 LlmMessage.tool，不经 AgentLoop/审批）；5 项检查（执行无错/产出文件/结构合法/无危险操作/无写死绝对路径）落 report_json；失败带报告重试 validator-retries 次后 rejected | ✅ | `skill/SkillValidator`（ValidationReport record） |
| 草稿管理 API + 入库（P0-F4）：drafts 列表/详情/手动创建（POST drafts，导入与 E2E 构造用例）/validate/commit/reject/delete；commit 仅 validated 可提交 → SkillLibrary.create + 启用 + markSynthesized（synth=1）；默认人工确认，`harness.evolution.auto-commit=true` 时校验通过即入库 | ✅ | `web/SkillController`（/api/skills/drafts*）、`skill/SkillDraftStore`（skill_drafts 表，状态机 pending→validating→validated/rejected→committed） |
| 技能使用统计（P0-F5）：skills 表加 hit_count/fail_count/last_used_at/synth；SkillExecutor 入口统一 incrementHit（显式/提及/意图命中），步骤失败或异常 incrementFail（用户停止不计失败）；编辑名称/描述实际变更时 version+1；列表接口合并统计侧车 | ✅ | `core/memory/Db`（addColumnIfMissing ×4）、`skill/SkillStore`（SkillStats + bump）、`core/SkillExecutor`、`skill/SkillLibrary.update` |
| 进化仪表盘（P0-F6）：`GET /api/skills/evolution`（草稿状态分布/复用统计 totalHits+totalTasks+hitRate+topSkills/知识近 8 周增长/节省估算）；技能页「技能 \| 进化」Tab（localStorage 记忆），进化 Tab = 统计卡 + 知识周增长柱状（纯 div）+ 复用 TOP5 横条 + 草稿卡（状态徽标/展开看校验报告与 SKILL.md/重新校验/提交/拒绝/删除）；技能卡显示「复用 N 次 · 失败 M」 | ✅ | `web/SkillController.evolution`、`pages/Skills.jsx`（renderEvolution/renderDraft/renderReport）、`styles.css`（.evo-*） |
| 配置化 + Mock（P0-F7）：`harness.evolution.{enabled, recurrence-threshold, cluster-similarity, auto-commit, validator-retries, scan-recent}`；enabled=false 零行为；Mock 全链路可演示（合成走 mockSkillMeta，校验器 mini-loop 走 Mock 通用 fileWrite 演示分支） | ✅ | `config/HarnessProperties.Evolution`、`application.yml` |
| E2E（P0-F8）：三次同类任务→草稿 validated（轮询）→5 项检查→临时目录清理→commit→synth/enabled 断言→校验输入命中技能（skill 事件）→hit_count 递增→手动建非法草稿（引用未注册工具）→rejected+问题清单→evolution API 字段→回放 | ✅ | `e2e_evolution_check.py`（Mock 确定性 / 真实自适应双模式，HARNESS_BASE 可指定） |

---

### P1 编码硬实力（2026-09-12，代码语义索引 + 验证钩子 + 执行隔离 + 成本观测）
> 落地 `docs/plan/P1开发文档-编码硬实力.md`。E2E：e2e_codeindex_check 4/4、e2e_hooks_check 9/9、e2e_cost_check 9/9、e2e_worktree_check 5/5；回归 e2e_v2 / skill 24 / plan 22 / evolution 26 全过（Mock，worktree 隔离开启的同一实例）。
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 代码语义索引（F1）：code_chunks 表（scope=工作根 SHA-256 指纹）+ 语义分块（≤80 行，Java/JS/TS/PY 等顶层符号行优先断开）+ 批量向量化（EmbeddingClient.embedBatch，OpenAI 兼容端点 input 数组 32/批）；懒构建有界（2000 文件/3000 块，超顶标记 incomplete），fileWrite/fileEdit/fileDelete/docxWrite 成功后按 content_hash 增量重切 | ✅ | `core/codeindex/CodeIndexService`、`CodeChunksStore`、`embedding/EmbeddingClient.embedBatch`、`AgentLoop`（FILE_INDEX_TOOLS 挂接） |
| codeSearch 工具（F2）：@Component 自动注册（BASE 之外的扩展工具，systemPrompt 动态带上）；参数 query/path_prefix/top_k；无 Embedding Key 明确报错指引 grep（不静默空结果）；返回 path:起止行+相似度+两行预览，相关度阈值 0.30；内置「代码探索」白名单幂等迁移追加 codeSearch | ✅ | `tools/CodeSearchTool`、`core/agents/AgentLibrary.upgradeExplorerWithCodeSearch` |
| 验证钩子（F3）：工作根 `.harness/hooks.json` `{verify:[{name,command,timeout}]}`，每任务读一次（改文件即生效，无文件零行为）；AgentLoop 结论闸门——无工具调用回复先跑 hooks，失败回灌模型自动修复（max-retries 默认 2，消耗轮次），耗尽后结论强制附带失败详情；forceSummary 结论只跑一次如实报告；`hook {name,ok,durationMs,output}` 事件落库回放，前端过程块时间序渲染「✔ 验证: smoke (101ms)」；命令经 ShellTool 执行（deny-patterns 自动生效），shell.enabled=false 时钩子同步禁用并启动告警 | ✅ | `core/hooks/HookService`、`AgentLoop`（verifyHooks 闸门 + hookFeedback）、`TaskCard`（hook case）、`styles.css`（.hook-row） |
| bash 黑名单 + docker 隔离（F4）：内置危险正则清单（rm -rf /、mkfs、dd、shutdown、fork bomb、format、del /s、chmod 777 / 等）+ `harness.shell.deny-patterns` 追加，命中即拒绝回灌；`harness.shell.sandbox=docker` 时 `docker run --rm -v <工作根>:/work -w /work [--network none] [--memory] [--cpus] image sh -c`，Docker 不可用明确报错**不静默降级**；审批卡照常展示完整命令 | ✅ | `tools/ShellTool`（BUILTIN_DENY + denyHit + dockerCommand/probeDocker）、`HarnessProperties.Shell/Docker` |
| 多 Agent worktree 隔离（F5）：`harness.multi-agent.worktree-isolation`（默认 false）+ 项目为 git 仓库（--show-toplevel 经 Path 规范化比较，git 输出正斜杠）；**懒创建**（runOne 内，从当前 HEAD 分支——上游合并提交对下游可见）；成功/partial 后 worktree commit → 主根 `merge --no-ff`，冲突 `checkout --theirs` 以子任务版本覆盖并在结论追加冲突清单；失败/取消不合并；worktree 存放系统临时目录 `<tmp>/harness-worktrees/<repoHash>/`（零 git status 污染），结束后 remove --force + branch -D；create/merge synchronized 防并行 index 竞争 | ✅ | `core/multi/WorktreeManager`、`Orchestrator`（worktreeMode + mergeConflicts 透传） |
| 成本观测（F6）：usage_log 表每轮主泳道 LLM 调用一行（AgentLoop.emitUsage 加 client 参数取 `client.modelName()` 归属模型，轻模型路由也正确记账；ChatExecutor CHAT 轮同写；子 Agent 泳道不写与 last_prompt_tokens 同口径）；`GET /api/conversations/{id}/usage`（404 防探测/rounds/tokens/cost/byModel，按 `harness.pricing` 计价 $/1M 缺价 0）；ContextMeter 弹层「本会话累计」行（懒加载，未配计价表 title 注明只统计 token） | ✅ | `core/memory/UsageLogStore`、`llm/ChatClient.modelName`、`web/ConversationController`、`Chat.jsx`（ContextMeter） |
| 轻模型路由（F7）：`harness.model-routing.light-model-id`——仅当请求未显式指定 modelId 时，CHAT 直答与两个技能分支改用轻模型；TASK/多 Agent/计划模式/planText 不变；解析失败回退默认模型；用户显式选模型时完全不受影响 | ✅ | `core/TaskDispatcher`（lightClient 分支） |
| E2E（F8）：codeindex（绑定夹具项目→Mock codeSearch 分支→无 Key 降级指引断言）；hooks（通过/失败回灌重试/结论强制详情/deny 拦截四场景）；cost（404 防探测/CHAT+TASK 计入/rounds 递增/byModel 结构/mock 归属/无计价 cost=0）；worktree（独立脚本，需 HARNESS_WORKTREE_ISOLATION=true 启动：扇出→交付物合并回主根→合并提交存在→临时目录清理→夹具 git reset 还原） | ✅ | `e2e_codeindex_check.py`、`e2e_hooks_check.py`、`e2e_cost_check.py`、`e2e_worktree_check.py`（Mock 确定性 + 真实自适应） |

---

### P2 采用门槛（2026-09-12，README 重写 + 一键启动 + 首跑体验 + 执行报告 + 演示素材）
> 落地 `docs/plan/P2开发文档-采用门槛与传播.md`。定位一句话（README / 本文件 / 仓库简介三处一致）：**本地优先的自进化编码 Agent：规划并执行任务，把成功经验沉淀为可复用技能，代码与数据永不出本机。** E2E：e2e_report_check 21/21；回归 e2e_v2 / skill 24 / evolution 26 / plan 22 全过；浏览器实测：欢迎卡/示例卡/报告按钮全链路 + 三幕 GIF 帧采集。
| 功能 | 状态 | 落地位置 |
|------|------|----------|
| README 重写（F1）：Gitee 占位符 → 门面文档（一句话定位 / demo GIF / 三行启动 + Docker / **Mock 无 Key 演示加粗宣传** / 与 Cursor·Cline·Claude Code·OpenHands 八维对比表 / 隐私与数据归属 / 功能总览 / 常见问题 JDK·构建·Windows bash·端口·代理·CRLF）；README.en.md 同步；两文件与 AGENTS.md 互链；LICENSE（MIT） | ✅ | `README.md`、`README.en.md`、`LICENSE`、`docs/images/`（三幕 GIF + 六张截图） |
| 一键启动（F2）：run.bat / run.sh（Java≥21 检查给下载页与 JAVA_HOME 指引 → jar 缺失自动 mvn package → 启动后轮询 /api/config 就绪即开浏览器）；Dockerfile 多阶段（maven 构建 → temurin 21-jre，**前端产物已内嵌无需 Node**）；docker-compose.yml（HARNESS_LLM_API_KEY/BASE_URL/MODEL 注入 + named volume /data）；.dockerignore（构建上下文只需 harness/）；可选 GitHub Actions 发版工作流 | ✅ | `run.bat`、`run.sh`、`Dockerfile`、`docker-compose.yml`、`.dockerignore`、`.github/workflows/release.yml`、`docs/发布清单.md` |
| 路径配置补齐（F2 前置）：`harness.skills-dir`（env HARNESS_SKILLS_DIR）——SkillLibrary.skillsDir() 显式配置优先，缺省维持派生规则（root 配置→<root>/skills，否则启动目录/skills）；Docker 三路径统一 /data（db/workspace/skills） | ✅ | `config/HarnessProperties.skillsDir`、`skill/SkillLibrary.skillsDir`、`application.yml` |
| 首跑向导（F3）：Chat 空态欢迎卡（`/api/config` mockMode && 会话数 0 且未关闭时显示）——「⚙️ 配置模型」（复用 open-settings 事件直达设置弹窗）/「▶ 先看演示」（预填 Mock 全链路示例）；示例任务卡 ×3（空态常显、收起记忆 localStorage `harness.samples.dismissed`）：Mock 全链路 / 绑定本地项目（派发 `harness:bind-project` 事件，Sidebar 监听打开文件夹选择器）/ 技能执行（命中内置 weekly-report 触发词） | ✅ | `pages/Chat.jsx`（bootInfo + SAMPLE_CARDS + WelcomeCard）、`Sidebar.jsx`（bind-project 监听）、`styles.css`（.welcome-card/.sample-cards*） |
| 演示夹具（F3）：samples/demo-app（极简待办页：index.html/style.css/app.js + README 引导 git init 后绑定项目体验 diff/Git 面板） | ✅ | `samples/demo-app/` |
| 结论导出与历史报告兼容（F4）：页面轮次操作区仅“导出”或“复制”当前结论 Markdown；原 `GET /api/conversations/{id}/report?messageId=` 报告接口保留供旧调用方使用，页面不再调用 | ✅ | `components/TaskCard.jsx`（ConclusionActions）、`web/ReportController` |
| 演示素材（F5）：docs/images/ 三幕 GIF（demo-task：欢迎页→任务→过程块→文件抽屉；demo-project：选择器→Git徽标→变更条→面板→彩色diff→提交成功；demo-evolution：技能chip→存为技能→草稿→技能库→进化仪表盘）+ 六张截图（chat-dark/welcome/skills/knowledge/settings/evolution）；浏览器自动化逐帧截图 + PIL 合成，分镜与重录步骤见录制脚本文档 | ✅ | `docs/images/*`、`docs/演示素材录制脚本.md`、`.capture/assemble.py`（帧与脚本不入库） |
| 命名与品牌（F6）：候选 SkillLoop / EvoHarness / MemForge / FarmAgent + 理由与改移步骤（git 地址改名保留重定向、包名不动、tagline 三处统一）；发布前安全检查（**application.yml 默认 Key 视为泄露须轮换并置空**） | ✅ | `docs/发布清单.md` |

---

### P4 Task Runtime v2（2026-09-18，任务度量与结构化结论）

| 功能 | 状态 | 落地位置 |
|------|------|----------|
| 每轮请求持久化为完整 UUID `TaskRun`，关联用户/Assistant 消息；启动时恢复遗留运行为 `INTERRUPTED` | ✅ | `core/task/TaskRunService`、`TaskRunStore`、`core/memory/Db` |
| 显式追踪每次 LLM/视觉/工具调用，记录阶段、Agent、真实或估算 Token、首响应、累计耗时和计价快照；审批/askUser 并发等待按区间并集计时 | ✅ | `LlmCallTracker`、`ToolCallTracker`、`TaskRunContext` |
| 唯一 `TaskFinalizer` 在 SQLite 事务内写 Assistant 与 Run 终态，按固定顺序发送 `task_metrics → task_outcome → done` | ✅ | `TaskFinalizer`、`TaskOutcomeBuilder` |
| 新轮次沿用普通 Markdown 结论样式，下方显示本轮耗时；状态、结构化证据与调用指标保存在后端，历史回放和 Markdown 报告统一消费持久化 Outcome，旧轮次保留兼容渲染 | ✅ | `TaskOutcomeCard.jsx`、`TaskCard.jsx`、`ReportController` |
| Run/指标 API 与新旧用量聚合；明细响应脱敏且最多返回 200 条（可调上限 500） | ✅ | `TaskRunController`、`UsageLogStore` |

---

## 4. 自进化闭环（架构总览）

未来形态（M2/M3 接入技能层后）：

```
用户输入 → [意图路由] ─命中─→ 技能执行器(SKILL.md)
               │                        ↑
            未命中                      │
               ↓                        │
         [规划器] → [AgentLoop] → 成功? → [技能合成器] → [技能库]
```

**当前实际形态**：所有输入先经 **ChatModeRouter 分流**——CHAT（闲聊/问答）由 `ChatExecutor` 直接流式回答，不进规划与循环；TASK 再经 IntentRouter 检索技能，命中走 SkillExecutor；未命中先经 `Planner.decompose` 拆分——多 Agent 开启且通过触发门槛（最大波宽 ≥2）时走 `Orchestrator` 扇出，否则回落 `Planner + AgentLoop` 单 Agent（与历史行为一致）。M3 技能合成器未实现。普通对话工作根 = `workspace/`；**项目对话工作根 = 绑定的本地文件夹**（`projects.root_path`）。

- **模式路由（CHAT/TASK）**：✅ 已落地（`core/ChatModeRouter`，可配 `harness.chat-router` 开关）
- **技能路由（Embedding/关键词）**：✅ 已落地（`router/IntentRouter`）
- **命中技能** → SkillExecutor 运行既有 `SKILL.md`（✅ 已落地）
- **未命中（可并行）** → Planner.decompose 拆分 + Orchestrator 多 Agent 扇出（✅ 已落地，M4，默认开启）
- **未命中（顺序/拆不动）** → Planner 拆分 + AgentLoop 执行（✅ 已落地，流式版）
- **成功** → 记录执行轨迹（✅ 已落地：`traces` 表）
- **技能合成器**：✅ 已闭环（P0）——TaskDispatcher finally 异步挂接 `EvolutionPipeline`：成功轨迹写 task_vectors 聚类 → 达阈值自动合成（SkillSynthesizer 复用）→ SkillValidator 隔离沙箱校验 → 入库（人工确认或 auto-commit）→ IntentRouter 复用命中即 hit_count+1。手动入口「存为技能」保留并行。

---

## 5. 技术栈（实际落地）

| 层 | 选型 | 说明 |
|----|------|------|
| 语言 / 运行 | Java 21（源码级），Spring Boot **3.4.7** | 构建用 JDK 21+（开发机用 IntelliJ JBR 25；系统 Java 8 不可用） |
| LLM 接入 | **自研 OpenAI 兼容客户端（SSE 流式）**（非 Spring AI） | `llm/ChatClient` 接口（StreamListener 增量回调）；`OpenAiCompatChatClient`（真实端点，stream:true）/ `MockChatClient`（无 Key 分片模拟）；可接 DeepSeek / 通义 / OpenAI，兼容 reasoning_content 思考链 |
| Embedding | `embedding/` 全套（OpenAI 兼容客户端 + 缓存 + 索引） | M2 已落地，未配 Key 降级关键词匹配 |
| Git | **本机 git 命令行**（ProcessBuilder，无 JGit 依赖） | `core/git/GitService`；stdout/stderr 分离收集（CRLF 警告走 stderr） |
| 存储 | SQLite（`sqlite-jdbc` 3.49.1） | 轨迹 + 会话 + 项目 + 技能/向量库 + 模型档案 + 知识；余弦在 Java 内计算 |
| 前端 | Vite 6 + React 19 + react-router-dom 7 + react-markdown/remark-gfm | 构建产物内嵌 jar，单进程 serve；Markdown 流式渲染 |
| 办公文档 | Apache POI 5.3（`poi-ooxml`） | docx 生成 |

> ⚠️ **修正原文档**：计划文档 §4 写的是「Spring AI 1.x」，实际**未引入 Spring AI**，LLM 调用由 `OpenAiCompatChatClient` 直接打 OpenAI 兼容 `/chat/completions` + function calling 完成。

---

## 6. 目录结构（实际）

```
pig-harness/
├─ 项目说明文档.md                 # 本文
├─ README.md / README.en.md        # 门面文档（P2-F1；引用 docs/images/ 素材，与本文互链）
├─ run.bat / run.sh                # 一键启动（Java≥21 检查 → 自动 mvn package → 开浏览器）
├─ Dockerfile / docker-compose.yml # 容器部署（数据统一 /data 卷；构建上下文只需 harness/）
├─ LICENSE                         # MIT
├─ docs/plan/                      # M1–M5 开发文档（M3 进化 / M4 多 Agent / M5 外部能力扩展为规划）
├─ docs/images/                    # README 演示素材（三幕 GIF + 截图，重录见 docs/演示素材录制脚本.md）
├─ samples/demo-app/               # 项目模式演示夹具（git init 后绑定体验 diff / Git 面板）
├─ e2e_check.py                    # 端到端验收脚本
├─ harness/                        # Spring Boot 后端（打包为 harness.jar）
│  ├─ pom.xml
│  ├─ src/main/java/com/example/harness/
│  │  ├─ HarnessApplication.java
│  │  ├─ config/  HarnessProperties / LlmConfig / RootResolver / StartupReporter
│  │  ├─ core/
│  │  │  ├─ AgentLoop.java        # plan→act→observe→reflect（流式 + 按请求模型 + 知识注入 + 取消；含 executeSubAgent/Emitter）
│  │  │  ├─ multi/  Orchestrator / SubTask / DecomposePlan / AgentResult / SharedBlackboard / SubAgentConfig（M4 多 Agent 编排）
│  │  │  ├─ ChatModeRouter.java   # 模式路由：CHAT 闲聊直答 / TASK 任务执行
│  │  │  ├─ ChatExecutor.java     # CHAT 模式流式直答
│  │  │  ├─ ModelRegistry.java    # 多模型注册中心（内置+用户档案 → 按请求解析 ChatClient）
│  │  │  ├─ git/GitService.java   # 项目 Git 集成（status/diff/commit/push，本机 git CLI）
│  │  │  ├─ Planner.java          # LLM 任务拆分（plan + M4 decompose 子任务 DAG）
│  │  │  ├─ SkillExecutor.java    # 技能执行器（M2）
│  │  │  ├─ ToolRegistry.java     # 工具注册/分发
│  │  │  ├─ knowledge/ KnowledgeExtractor.java  # 会话后自动沉淀长期记忆
│  │  │  └─ memory/
│  │  │     ├─ Db.java            # SQLite（traces/skills/embeddings/conversations/messages/model_profiles/knowledge）
│  │  │     ├─ WorkingMemory.java # 当前任务上下文
│  │  │     ├─ EpisodicStore.java # 轨迹持久化
│  │  │     ├─ ConversationStore.java # 多会话持久化（含 project_id 挂接）
│  │  │     ├─ ProjectStore.java  # 本地文件夹项目
│  │  │     ├─ ModelStore.java    # 用户自配模型档案
│  │  │     └─ KnowledgeStore.java# 长期记忆（知识）
│  │  ├─ tools/
│  │  │  ├─ Tool.java / ToolContext.java / ToolResult.java
│  │  │  ├─ FileWriteTool.java（创建或覆盖）/ FileEditTool.java（精确替换）/ FileReadTool.java（行分页）
│  │  │  ├─ ListFilesTool.java / FileDeleteTool.java / DocxWriteTool.java
│  │  ├─ llm/  ChatClient(StreamListener) / OpenAiCompatChatClient(SSE 流式) / MockChatClient / 消息与工具调用模型
│  │  ├─ embedding/  EmbeddingClient / OpenAiCompatEmbeddingClient / CachingEmbeddingClient / EmbeddingIndex / EmbeddingStore（M2）
│  │  ├─ router/  IntentRouter.java（技能检索：向量 + 关键词降级，M2）
│  │  ├─ skill/  Skill / SkillStore / SkillLibrary / SkillParser / ParameterFiller...（M2）
│  │  ├─ model/ Task / Trace / PlanStep / ToolCall / Conversation / StoredMessage / ModelProfile / Knowledge ...
│  │  ├─ sandbox/ Sandbox.java    # 路径越界校验
│  │  └─ web/  TaskController / ConversationController / ProjectController / FsController(文件夹浏览) / TraceController / FileController / SkillController / ModelController / KnowledgeController / SpaFallback
│  ├─ src/main/resources/
│  │  ├─ static/                  # 前端构建产物（内嵌）
│  │  └─ application.yml
│  └─ target/harness.jar           # 构建产物（mvn package 后）
├─ frontend/                       # React + Vite 前端源码（npm run build → 产物内嵌后端）
│  └─ src/  Sidebar.jsx(项目/对话折叠分组)  pages/Chat|Knowledge|Skills  components/TaskCard|FileDrawer|GitPanel|FolderPickerModal|Md
├─ db/                             # SQLite 文件（运行时生成，默认 db/harness.db）
├─ skills/                         # 技能库目录（SKILL.md）
├─ testproj/ testremote.git/ nogit-proj/  # 项目模式 E2E 夹具（git 仓库 + 裸远端 + 非 git 目录）
└─ workspace/                      # 沙箱默认根目录（普通对话的文件边界；留空时=启动目录/workspace）
```

---

## 7. API 速查

所有流式接口返回 `text/event-stream`，事件类型（**v2 协议**）：

| 事件 | payload | 落库回放 | 说明 |
|------|---------|----------|------|
| `text_delta` | `{delta}` | 否 | 正文增量（打字机），CHAT/TASK 均有 |
| `thinking_delta` | `{delta}` | 否 | 思考增量（模型 reasoning_content，如有） |
| `thinking` | `{text}` | 是 | 每轮聚合思考（回放） |
| `step_commentary` | `{round, text}` | 是 | 工具轮的模型说明（回放进思考面板） |
| `tool_start` | `{callId, name, target}` | 否 | 工具开始执行（体验升级四，仅推流；前端显示「正在执行 X」指示，对应 tool_call 按 callId 熄灭） |
| `skill` | `{id, name}` | 是 | 本次执行使用的技能（输入 / 显式选择或意图命中） |
| `vision` | `{index, text}` | 是 | 视觉模型对用户所附图片的转述（体验升级五；图片理解未配置时为提示文案） |
| `image` | `{index, path}` | 是 | 原图留存放置事件：path 为相对默认工作区的路径（`attachments/{会话}/xxx.png`），回放经 `/api/file?path=`（不带会话参数）取图 |
| `agent_spawn` | `{agent, task}` | 是 | 子 Agent 启动（M4，事件带 `agent` 字段） |
| `agent_done` | `{agent, status, summary, files}` | 是 | 子 Agent 结束（M4；status ∈ success/partial/failed/cancelled） |
| `blackboard` | `{key, value(≤2000), owner}` | 是 | 黑板登记/占坑可见（M4） |
| `approval_request` | `{callId, name, path, summary}` | 是 | 请求审批模式下的写操作确认（前端渲染审批卡片，见 §9.16；M4 子 Agent 事件额外带 `agent`，callId 含 `agent::` 前缀） |
| `plan` | `{steps[]}` | 是 | Planner 计划（仅 TASK） |
| `plan_step` | `{index, status}` | 是 | 计划步骤完成进度（status ∈ done/failed；循环结束时剩余步骤一次性补齐，见体验升级四） |
| `tool_call` | `{name, args, result(截断4000), ok}` | 是 | 每次工具执行（M4 子 Agent 的事件额外携带 `agent` 字段） |
| `file` | `{path, size}` | 是 | 文件产出（v2 增加 size） |
| `reflect` | `{reason}` | 是 | 工具失败反思提示 |
| `context_compact` | `{before, after, scope: conversation\|task}` | 是 | 上下文压缩发生（体验升级六；conversation=对话级 LLM 摘要，task=任务内工具输出占位替换；前端渲染「🗜 上下文已压缩」行） |
| `context_usage` | `{promptTokens, estimated}` | 否 | 每轮 LLM 调用后的 prompt 用量（体验升级六，仅推流；真实 usage 优先，估算时 estimated=true；前端实时更新容量徽标） |
| `question_request` | `{callId, question, options?, recommended?}` | 是 | 澄清提问（体验升级八，计划模式）：Agent 就关键分歧向用户提问；前端渲染问题卡（选项点选/自由输入/推荐徽标），已答态按同 callId 的 tool_call 判定；应答走 POST /question |
| `plan_mode` | `{}` | 是 | 计划模式标记（体验升级八）：轮次以 approval=plan 执行，结论为计划文档；前端据此渲染「计划」卡片（截断预览 + 抽屉看全文）与「执行此计划 / 补充细节信息」决策条（回放同构） |
| `task_metrics` | `{runId, wallDurationMs, ...}` | 是 | P4 汇总指标；终态三事件中的第一条 |
| `task_outcome` | `{runId, outcome}` | 是 | P4 结构化结论；状态、计划、文件、验证、风险和指标的共同事实源 |
| `done` | `{conclusion, traceId?, runId?, status?}` | 是 | 兼容结束事件；P4 开启时只由 Finalizer 发送一次 |

### 7.1 会话（主入口，F12）
```
POST   /api/conversations                     # 新建会话；body 可选 {projectId} 绑定项目
GET    /api/conversations                     # 会话列表（含 projectId/projectName/running——运行中标志，侧栏转圈数据源）
GET    /api/conversations/{id}                # 会话元信息（含 projectId/projectName/rootPath/running——运行中标志，编辑重发等待收尾的轮询依据）
GET    /api/conversations/{id}/messages       # 该会话全部历史消息
GET    /api/conversations/{id}/runs?messageId= # P4：列出会话 TaskRun，可按用户或 Assistant 消息 id 精确查询
GET    /api/conversations/{id}/context?modelId= # 上下文容量状态 {contextLength, usedTokens, percent, state, summarized}（体验升级六，输入框徽标数据源）；usedTokens = max(上一轮真实 prompt 用量, 基线估算)——任务后停留在真实用量不回落（2026-09-05 修订）
POST   /api/conversations/{id}/compact        # 手动触发对话级压缩 → {ok, before?, after?, reason?}（体验升级六）
GET    /api/conversations/{id}/report?messageId= # 执行报告导出（P2-F4）：text/markdown 附件下载；messageId=轮次用户消息 id，缺省=最后一个已完成轮次；404（会话/轮次不存在）/400（无已完成轮次）；内容含任务原文/模式/计划/工具表/文件/变更摘要/结论/用量
POST   /api/conversations/{id}/messages       # 发送 {prompt, modelId?, deepThink?, approval?, skillId?, planText?}（SSE 流；项目会话自动切换工作根；skillId 非空时跳过模式路由直接执行该技能；planText 非空时为「执行此计划」——计划注入执行提示词、落库保持 prompt 原文并跳过路由/技能/多 Agent；运行中再发 → 409）
GET    /api/conversations/{id}/live           # 接入会话实时流（体验升级七）：运行中 → 重放缓冲事件+续流，结束帧后关闭；空闲 → hub_idle 一帧后关闭（刷新页面/换标签页续看的数据源）
POST   /api/conversations/{id}/stop           # 显式停止当前任务（置取消标志 + 排水挂起中的审批/提问，结论「（已停止）」照常落库）；断开连接不再取消任务
POST   /api/conversations/{id}/question       # 澄清提问应答（计划模式）{callId, answer} → 任务继续；无挂起提问 → 409
PATCH  /api/conversations/{id}                # 重命名标题 {title}
DELETE /api/conversations/{id}                # 删除会话（及 messages）
DELETE /api/conversations/{id}/messages/{messageId} # 从某条消息起截断（体验升级九编辑重发第一步：删该消息及其后全部，返回 {ok, removed}；会话不存在 404 / 运行中 409——前端先 stop 并等 running=false 再调）
```

### 7.2 任务（兼容入口，F1）
```
POST /api/task                                # 无会话一次性任务（SSE 流）—— 兼容保留，前端统一走 7.1
GET  /api/config                              # 返回当前绑定根目录 / LLM 配置状态（是否 Mock）
GET  /api/task-runs/{runId}                   # P4：Run 终态与结构化 Outcome
GET  /api/task-runs/{runId}/metrics           # P4：模型/阶段/Agent 汇总与最多 200 条 LLM/Tool 明细
```

### 7.4 模型管理（多模型 + 深度思考）
```
GET    /api/models                            # 模型档案列表（不回传 apiKey；含 baseUrl 供编辑表单预填 + supportsThinking 标记）
POST   /api/models                            # 新增用户模型 {name, baseUrl, apiKey, model, thinkingModel?}
PATCH  /api/models/{id}                       # 修改（内置模型不可改；apiKey 留空保留原值，thinkingModel 传空串清除，contextLength 缺省保持不变）
DELETE /api/models/{id}                       # 删除（内置模型不可删）
```
发送消息时可携带 `modelId` 与 `deepThink`（7.1 的 POST body）；deepThink=true 且档案配置了 thinkingModel 时切换到推理模型。

### 7.5 知识（长期记忆）
```
GET    /api/knowledge?category=&status=       # 列表 + 统计（total/byCategory/byStatus/conversationCount）
POST   /api/knowledge                         # 手动添加 {category, key, content, projectId?}；项目事实需绑定项目
PATCH  /api/knowledge/{id}                    # 编辑 / 置顶 / 采用 / 归档 {pinned?, status?, projectId?}；旧 rejected 请求兼容为删除
DELETE /api/knowledge/{id}                    # 删除；待审核知识的“忽略”使用此接口
POST   /api/knowledge/extract                 # 手动触发：从最近会话最新用户输入提取待审核候选 {conversationId?}
```

### 7.3 轨迹 / 文件
```
GET /api/traces                               # 轨迹列表
GET /api/traces/{id}                          # 单条轨迹详情
GET /api/file?path=<相对沙箱路径>&conversationId=   # 预览/下载；项目会话按会话解析项目根
```

### 7.7 技能库（M2 + 技能体验一）
```
GET    /api/skills                     # 列表（origin: system|user；系统在前，各按更新时间倒序）
POST   /api/skills                     # 创建/导入 {name?, content}（SKILL.md 全文，frontmatter 照常解析）
GET    /api/skills/{id}                # 详情（含 content/steps/params/origin）
PATCH  /api/skills/{id}                # {enabled?/name?/description?}（系统技能可禁用不可删）
DELETE /api/skills/{id}                # 删除（系统技能文件被删后重启会自动播种恢复）
POST   /api/skills/{id}/reindex        # 重建向量索引
POST   /api/skills/from-trace          # {traceId, name?} → SkillSynthesizer 结合对话上下文生成草稿（默认禁用）
```

### 7.8 MCP 服务器管理（M5 + stdio 扩展）
```
GET    /api/mcp/servers               # 列表（status: connected/connecting/error/disabled，toolCount；含 transport/command/args/env）
POST   /api/mcp/servers               # 新增 {name, transport?, url?, headers?, command?, args?, env?, enabled}
                                      # transport 缺省按字段推断（有 command 即 stdio，否则 http）；
                                      # http → url 必填仅 http/https；stdio → command 必填；
                                      # args 接受字符串（空白/双引号分词）或数组，落库统一数组 → 后台连接
PATCH  /api/mcp/servers/{id}          # 改 name/url/headers/transport/command/args/env/enabled（任一连接目标变更自动重连）
DELETE /api/mcp/servers/{id}          # 删除并注销其全部 mcp__ 工具（stdio 同步终止子进程）
POST   /api/mcp/servers/{id}/refresh  # 重连 + 重新 tools/list（同步，自带超时）
GET    /api/mcp/servers/{id}/tools    # 已注册工具清单（本地名 mcp__{server}__{tool} + 描述）
```

### 7.9 子智能体（spawnAgent 体系，体验升级七）
```
GET    /api/agents                     # 清单（含 tools 白名单/maxSteps/enabled/origin）
POST   /api/agents                     # 新建 {name, description, prompt, tools?, maxSteps?}（同名 400；tools 空=不限制）
PATCH  /api/agents/{id}                # 修改（含 enabled 启停）
DELETE /api/agents/{id}                # 删除（origin=builtin 返回 400；内置档案删除后重启自动播种恢复）
```
主 Agent 侧经 `spawnAgent` 工具派出（参数 agent=名称、task=自包含任务描述）；内置「代码探索」白名单 fileRead/listFiles/grep。

### 7.6 项目模式（本地工程 + Git）
```
GET    /api/projects                          # 项目列表（含会话数）
POST   /api/projects                          # 绑定 {name?, rootPath}（文件夹必须存在；同路径去重；name 留空默认取文件夹名）
DELETE /api/projects/{id}                     # 删除项目（会话解绑，文件不动）
GET    /api/projects/{id}/git                 # Git 状态 {repo, branch, ahead, behind, changes[{path,code,label,additions,deletions}], totalAdditions, totalDeletions, error?}
GET    /api/projects/{id}/git/diff?path=      # 单文件 diff（未跟踪合成新增 diff）
POST   /api/projects/{id}/git/commit          # {message} → git add -A + commit
POST   /api/projects/{id}/git/push            # git push（无 upstream 自动关联 origin）
POST   /api/conversations/{id}/approval       # 审批 {callId, approve}（权限=请求审批时完成挂起的写操作确认）
GET    /api/fs/list?path=                     # 本地目录浏览（仅子目录；path 空返回盘符根；文件夹选择器数据源）
```

> 前端实际调用以 `POST /api/conversations/{id}/messages` 为主；`/api/task` 仅作后向兼容。

---

## 8. 存储（SQLite）

数据库文件：`db/harness.db`（路径可配，见 §10）。表：

| 表 | 用途 | 阶段 |
|----|------|------|
| `traces` | 任务执行轨迹（task / steps JSON / outcome / created_at） | M1 F8 |
| `conversations` | 会话元信息（id / title / created_at / updated_at） | M1 F12 |
| `messages` | 会话消息（role / content / events / created_at，关联 conversation） | M1 F12 |
| `skills` | 技能（SKILL.md 元数据 + 内容 + 触发词；`origin`=system/user，`mode`=steps/prompt，M2 + 技能体验一 + M5） | M2 |
| `mcp_servers` | MCP 服务器（name/url/headers_json/enabled/status/last_error/tool_count） | M5 |
| `agent_profiles` | 子智能体档案（name UNIQUE/description/prompt/tools_json 白名单/max_steps/enabled/origin builtin\|user；内置「代码探索」播种） | 体验升级七 |
| `embeddings` | 技能向量（`skill_id + vector(BLOB)`，Java 内算余弦） | M2（旧 trace_id 占位表已自动迁移） |
| `model_profiles` | 用户自配模型档案（name/base_url/api_key/model/thinking_model） | 体验升级二 |
| `knowledge` | 长期记忆（category/key/content/source auto|manual/pinned，UNIQUE(category,key)） | 体验升级二 |
| `projects` | 本地文件夹项目（id/name/root_path/created_at） | 项目模式 |
| `conversations.project_id` | 会话挂接项目（列；项目删除时置 NULL 解绑） | 项目模式 |

---

## 9. 已知设计偏差（与计划文档不一致，务必注意）

1. **未使用 Spring AI**：LLM 层为自研 `OpenAiCompatChatClient`（直接调 OpenAI 兼容协议），不依赖 `spring-ai`。`ChatClient` 接口在 `llm/`，Embedding 同模式扩展于 `embedding/`。
2. **`Tool` 接口带 `ToolContext`**：签名 `ToolResult execute(Map<String,Object> args, ToolContext ctx)`，ctx 提供沙箱与工作记忆；计划文档中的 `execute(Map)` 已演进。
3. **SSE 事件协议为 v2（2026-08-29 起）**：新增 `text_delta` / `thinking_delta` / `thinking` / `step_commentary` / `plan_step` 事件；`file` 事件带 `size`；delta 类事件只推流**不落库**（transcript 只存聚合事件，防 DB 膨胀）；`done.conclusion` 仍为完整最终文本，历史回放兼容。
4. **LLM 调用全部走 SSE 流式**：`ChatClient.chat(messages, tools, listener)`；`OpenAiCompatChatClient` 请求体固定 `stream:true`，按 index 聚合 tool_calls 增量；`reasoning_content`（DeepSeek-R1/reasoner 等）自动接入思考面板，普通模型则展示每轮说明文字。
5. **文件覆盖策略变更**：`fileWrite` / `docxWrite` 由「禁止覆盖」改为**直接覆盖**（重复任务可重跑）；修改已有文件应使用 `fileEdit`（old_string/new_string 精确替换）。旧版报错文案已废弃。
6. **`/api/file` 安全修复**：不再接受客户端传入的 `root` 参数，固定使用服务端默认沙箱根（旧版可被任意指定，等于全盘可读）。
7. **模式路由**：所有输入先经 `ChatModeRouter` 判定 CHAT/TASK；CHAT 不走 Planner/工具。关闭开关 `harness.chat-router=false` 可回到全任务模式。
8. **多模型与深度思考**：模型档案 = 内置（`harness.llm` + `harness.models[]`）+ 用户自配（设置页添加，存 `model_profiles`，API 永不回传 apiKey）。每次请求带 `modelId`/`deepThink`；deepThink=true 且档案有 `thinking-model` 时切换推理模型（如 deepseek-reasoner），否则按钮置灰。
9. **知识（长期记忆）审核与检索**：会话任务结束后 `KnowledgeExtractor` 只从最新用户输入提出最多 2 条有逐字证据的候选；自动候选和迁移前的自动知识均为 `pending`，不进入提示词。用户确认后为 `active`，按当前请求相关性、项目 ID、最多 5 条/约 600 字预算检索；置顶最多优先注入 2 条。项目事实必须绑定项目；`其他` 只作笔记。`harness.knowledge.auto-extract=false` 可关闭候选提取；Mock 模式自动跳过。运行轨迹页已由知识页替代（`/api/traces` 接口保留未删）。
10. **Mock 演示模式**：`HARNESS_LLM_API_KEY` 为空时默认档案走 `MockChatClient`（含流式分片、思考演示、意图分类、fileEdit/listFiles/fileDelete 演示流程）；无 Key 的用户自配档案同样降级 Mock。
11. **Java 版本**：源码目标 Java 21；开发机构建用 IntelliJ 自带 JBR 21（`D:\IntelliJ IDEA 2025.1.2\jbr`）+ Maven 3.8.2（`D:\Downloder\apache-maven-3.8.2`）；系统 PATH 上的 Java 17 / Java 8 均无法运行或构建本 jar。
12. **项目模式 root 来自 DB**：项目会话的工作根在 `ConversationController.send` 中按 conversationId→project 解析；`/api/file` 同理（带 `conversationId` 时）。客户端不可指定 root（安全边界）。
13. **Git 集成依赖本机 git CLI**（非 JGit），`git` 必须在 PATH。**GitService 解析三陷阱（已踩坑，改代码前必读）**：
    - git 的 LF→CRLF 警告走 **stderr**，若 redirectErrorStream 混流会污染 porcelain 解析 → stdout/stderr 必须分离收集；
    - `git status --porcelain` 首行前导空格是「未暂存」状态位，**不要对整体输出 trim**（曾把 ` M utils.js` 裁成 `M utils.js` 导致 path 错位成 `tils.js`）；
    - `git rev-parse --is-inside-work-tree` 会向上匹配父仓库 → 必须校验 `--show-toplevel` 等于项目根，否则在上级仓库的子目录里 `add -A` 会误提交父仓库文件。
14. **模型显示名 ≠ API model id**：内置双档案 `DeepSeek V4 Flash`（id=`default`，模型 `harness.llm.model`）与 `DeepSeek V4 Pro`（id=`pro`，模型 = `harness.llm.thinking-model`，即深度思考推理模型本身，故无思考开关）都是前端展示名，实际请求模型由 yml 的 `model` / `thinking-model` 决定；换端点时按实际服务的模型填写，避免误导。
16. **权限三档与审批流**：`approval` 随每条消息下发（默认 auto）。`ask` 模式下写操作经 `ApprovalGate` 挂起（内存态 CompletableFuture，重启即失效，前端卡片按 `tool_call.callId` 关联判定已决）；审批事件落库可回放。`full` 模式仅放宽路径解析（`ToolContext.resolve`），不放宽 Git 面板与轨迹。工具描述写「路径默认相对项目根」，避免 full 模式下模型被描述劝退拒绝用绝对路径。
17. **SSE 客户端读取陷阱（E2E 踩坑）**：Python `res.read(n)` 在 chunked 流上会攒满 n 字节才返回——审批挂起等「静默期」会永久饿死；必须用 `res.read1(n)` 返回当前可用字节。浏览器 fetch reader 无此问题。
18. **SkillParser 禁用键值对正则**：技能步骤的单行 content 值可达数 KB（整页 HTML 模板），旧 KV 正则 `(?:\.|[^"\])*` 按字符递归回溯会 StackOverflowError（内置 frontend-design 技能即触发）；现为手写线性扫描器 `parseKvPairs`（仅接受双引号值，转义 `\"` 与 `
`）。新增技能解析逻辑勿再对超长行用嵌套量词正则。
19. **系统技能播种与恢复**：内置技能以 classpath 资源 `harness/src/main/resources/skills/builtin/*.md` 为源，启动时复制到 `skills/system/`（仅目标缺失时复制，用户对文件的修改会被保留）；`origin=system` 的技能前端不显示删除按钮，通过 API 删除后重启会自动恢复（禁用状态存 DB，重启后仍保留）。
15. **项目入口在侧栏，无独立项目页**：项目的新增（文件夹选择器）/ 快捷新建会话 / 解绑都在侧栏「项目」分组内完成（`/projects` 路由与 Projects 页已移除，`/api/projects` 接口保留）。文件夹选择用**后端浏览 API**（`GET /api/fs/list`，仅列子目录）而非浏览器原生目录选择——浏览器出于安全不暴露绝对路径，而绑定项目必须拿到真实绝对路径；本应用定位本机单用户工具，该 API 不做额外鉴权。
20. **技能双形态（M5）**：`mode: steps/prompt`，缺省 auto（有 `N. tool(...)` 步骤行即 steps，否则 prompt）。prompt 技能执行 = 填参后把散文正文作为最高优先级指令注入 AgentLoop（跳过 Planner）；**未填上的占位符保留 `{{name}}`** 并在指令中告知模型按语境自行决策。声明 steps 但引用未注册工具时执行期自动降级 prompt（社区技能正文常含样例行）。旧技能文件无 mode 列时按 steps 兼容。
21. **MCP 客户端为手写 JSON-RPC 2.0 实现**（`mcp/McpHttpClient`，约 250 行），未引入官方 `io.modelcontextprotocol.sdk`——2.x 刚 GA 包结构不稳，且接口层（`McpConnectionManager`）已隔离实现，日后可整体替换。仅支持 **Streamable HTTP** 传输（JSON/SSE 双帧响应 + Mcp-Session-Id 会话），**stdio 未支持**（Windows 进程托管成本高，按需再加）。服务器由用户显式配置，其 URL 不受 httpFetch 的私网限制；启动连接走后台线程，失败不阻塞启动（status=error）。
22. **httpFetch 的 SSRF 边界**：默认拒绝环回/私网/链路本地/IPv6 ULA/CGNAT（`harness.http.allow-private` 放开）；重定向手动跟随 ≤3 跳且**每跳重新校验**目标地址；不透传 host/content-length/connection 头。已知限制：DNS 重绑定（解析后连接前域名再变更）不在防护范围。只读工具，不进审批白名单。
23. **图片理解（体验升级五）**：视觉模型**只在 `application.yml` 的 `harness.vision` 配置**（单模型，切换=改配置重启），不进模型档案/设置页编辑；`model` 留空 = 未启用。图片以 data URL 随 `MessageRequest.images` 下发；原图**留存到默认工作区 `attachments/{会话}/`**（不进项目根，避免污染项目 git status；文件名服务端生成不受输入影响），`image` 事件只存相对路径、不存 base64——回放经 `/api/file`（不带会话参数=默认根）取图，文件被删则前端显示空白占位；**附件无自动清理**（删除会话不动文件）。视觉转述仍用请求里的 data URL 直发、不读磁盘。图片描述只注入本轮运行时 prompt；模式路由用原始 prompt 分类，避免描述文本干扰 CHAT/TASK 判定。未配置视觉模型时：Mock 主模型返回演示文案（本地链路可跑通），真实模式返回配置指引文案，绝不阻断消息。
24. **任务变更聚合条为前端聚合**：`TurnDiffBar`（`TaskCard.jsx`）在任务结束后用本轮事件流中「被文件工具触碰的路径」与 `git status` 清单求交集——**bash 等命令产生的文件变更不在其中**（看全量变更用右侧 Git 悬浮面板）；历史回放同样生效（事件已落库，status 每次现查）。不提供「撤销」按钮（误触丢失工作风险大），需要时用 Git 面板提交前审查或手动 `git checkout`。
25. **fileEdit 已做行尾自适应**（体验修复三）：CRLF 文件在 `\n` old_string 精确匹配失败时自动按 `\r\n` 重试并保持文件行尾写入；`old_string` 匹配语义从「逐字符」放宽为「逐字符或仅行尾差异」。构造测试时勿再假设 CRLF 文件必然匹配失败。
26. **上下文压缩只作用于发给 LLM 的上下文，落库消息永远完整**（体验升级六）：DB（messages/conversations）是回放真相源，压缩=「对话级摘要（ctx_summary + 水位线）+ 任务级工具输出占位替换（仅内存中的 msgs 列表）」。两个陷阱：① TaskDispatcher 的当前用户消息在压缩**之后**才落库（防止摘要提示词混入本条），且持久化原文（无图片描述注记）——改历史构建时保持该顺序；② TokenEstimator 是启发式（CJK≈0.6、其余≈0.3/字符，每条消息 +8 结构开销——对齐 DeepSeek 官方口径），压缩决策以 API 真实 usage 为基准、估算仅做发请求前预检，替换占位符有最小长度护栏（<300 字符不替换，否则越压越长）。容量徽标与压缩预检的「有效占用」= max(`conversations.last_prompt_tokens`〔主泳道每轮真实 usage，子 Agent 不写〕, 基线估算〔TASK 口径含工具定义 JSON ~2.5k token〕)；**压缩成功后必须回写 last_prompt_tokens**，否则旧真实用量会让预检每轮都判定超限、反复触发摘要调用（2026-09-05 修订）。
27. **模式路由的快路径与解析（体验修复三）**：「身份/模型问句」（你是什么模型等）走 CHAT_LEADIN 剥引导词 + IDENTITY_Q 全锚定正则直判 CHAT，不进 LLM；分类输出解析取 CHAT/TASK **先出现者**（contains("TASK") 优先会被「CHAT，不是TASK」式长输出误判）。新增快路径正则时必须保持全锚定（^...$）+ 首字符限定（你/您），防止「模型/文件」等词误伤任务句。
28. **spawnAgent 是 AgentLoop.runLoop 的特判，不是普通注册表工具（体验升级七，改代码前必读）**：`spawnAgent` 的工具 spec 由 `AgentLibrary` @PostConstruct 经 `toolRegistry.register(new SpawnAgentTool())` 注册（其 execute 永不被调用，返回错误兜底）——真正的派发在 `runLoop` 工具分发处特判（与审批闸门/占坑同构），因为派发需要循环内上下文（sse/client/history/transcript/hub）；若改成 `@Component implements Tool` 会形成 ToolRegistry→Tool→AgentLoop→ToolRegistry 循环依赖。子智能体的工具约束有两层：`ToolRegistry.specsFor(allowed)` 过滤请求 specs（且永不包含 spawnAgent）+ runLoop 分发处 `cfg.canUse` 防御性回灌。内置档案靠 `AgentLibrary` 启动播种（findByName 为空才插入——用户删除重启自动恢复、禁用状态保留）。
29. **会话任务的取消语义已显式化（体验升级七）**：会话入口（POST messages）的 Emitter 携带 `StreamHub.Hub.cancelFlag` 作为 shared 标志——**SSE 发送失败不再置 cancelled**（刷新页面/断开连接后任务后台继续跑完并落库），停止只能 `POST /stop`；`shared == null`（旧 /api/task 入口、无 Hub）时保持旧的「发送失败即取消」。TaskDispatcher→四个执行器→Orchestrator 全链穿 `Hub` 参数（旧重载委托传 null，e2e /api/task 不受影响）。事件缓冲在 `Hub.record`（send/sendLive 都进，delta 也缓冲供重放）；`/live` 先发快照再续流，快照+订阅注册在 `synchronized (hub.buffer)` 内完成防止漏事件。
30. **扇出门槛（体验升级七收紧后）**：真实 LLM 判据 =「每个子任务各自需要多步加工的实质性工作才 parallel，内容简单的小文件一律 single」（DECOMPOSER prompt 内置正反例，含「生成两个小文件 → single」）；Mock 门槛 = ≥2 类交付动词 + **≥3 个文件候选**（文件提取上限 3）。**给 e2e_multiagent_check 构造扇出任务时必须用实质性交付物**（如登录页+API 文档+测试计划），「生成 N 个一句话小文件」在新判据下会正确地走单 Agent。`harness.multi-agent.auto-decompose=false` 可整体关闭计划期拆分，主 Agent 按需 spawnAgent 不受影响。
31. **计划模式（体验升级八 + 体验修复七加固，改代码前必读）**：approval 第四档 `plan` 是**权限档位而非新会话类型**——`TaskDispatcher` 在显式技能分支之前拦截（计划模式 > 技能 chip 优先级；带 `planText` 的「执行此计划」轮不进计划分支，走任务路径且跳过路由/技能/多 Agent），跳过模式路由/意图/多 Agent/Planner 后直走单 Agent。只读承诺靠**四层机制**（缺一不可，真实 LLM 已验证会击穿前两层）：① specs 白名单（PLAN_TOOLS = fileRead/listFiles/httpFetch/askUser + spawnAgent spec 手工并回；bash 可写文件故整体禁用、mcp__* 不提供）；② runLoop **主泳道硬闸门**——模型返回的工具名 ∉ PLAN_TOOLS 一律拒绝回灌（tools 数组没有的工具，模型也会照提示词文本构造调用）；③ 系统提示计划分支**只列只读工具**（常规分支的 fileEdit/bash 文案是诱导源，勿改回）；④ 子 Agent 白名单收敛（档案白名单 ∩ PLAN_TOOLS 且剔除 askUser）。askUser 是 **AgentLoop 特判而非注册表工具**（与 spawnAgent 同构，规避循环依赖），仅 planMode 主泳道拦截，`cfg!=null`（子 Agent）不可问。`question_request` 与其 `tool_call` 事件共用同一 callId（= gateId），改 callId 生成必须两边同步。**停止排水**：/stop 会 cancelAll 审批（按拒绝）与提问（按未答复），`Emitter.doSend` 取消后不再跳过发送——若回滚这点，停止客户端将收不到 done。Mock 检测锚点 = 系统提示 `[MODE=PLAN]`（勿删）。前端两个陷阱：计划决策条只在「最后一个轮次是已完成计划轮」时替代输入框，`send()` 发出补充细节后必须复位 `planRefine`（否则新计划到达决策条永不重现）；「执行此计划」经 `MessageRequest.planText` 注入执行提示词，**气泡与落库只显示 prompt 原文四个字**（勿把计划拼进 prompt 回显）。
23. **M4 多 Agent 实现要点（与 v2 文档的偏差，改代码前必读）**：
    - `enabled` 默认 **true**（v2 文档写观察期 false，经用户确认改为默认开启；`HARNESS_MULTI_AGENT_ENABLED=false` 一键回到单 Agent）；
    - 触发门槛按「**最大波宽 ≥2**」解释（Kahn 分层后任一波次能同时跑 ≥2 个子任务才扇出）：`A→B,C` 可并行、`A→B→C` 纯链退化单 Agent——比文档原文「≥2 个 depends_on 为空的根任务」更合理（否则「1 上游 + 2 下游并行」会被误杀）；
    - 子 Agent 复用 `AgentLoop.runLoop` 抽取体：`executeSubAgent` 不 emit done、不落 trace、不 persist、不 complete（收尾归 Orchestrator）；`Emitter` 为 public 且支持 `sharedCancel`（AtomicBoolean）与 `agentTag`（事件注入 `agent` 字段，含 delta）；
    - 并发子 Agent 的 callId 加 `agent::` 前缀（ApprovalGate key `conversationId:callId` 不串扰，前端按 callId 关联审批卡不受影响）；
    - 黑板只做登记 + `putIfAbsent` 占坑兜底：fileWrite/docxWrite/fileDelete 执行前校验，路径被其它子 Agent 占用且非本任务分配路径 → 合成错误回灌改名；拆分期输出路径重叠直接降级单 Agent（计划期预防为主）；
    - Mock 拆分触发门槛 = **≥2 类交付动词**（生成/创建/做一个/写…）+ **≥2 个文件候选**——任何修改/查询/单文件任务一律 single，保证旧 E2E（v2/check）在多 Agent 开启下行为不变；
    - transcript 已改 `Collections.synchronizedList`（子线程并发写入）；`Emitter.cancelled` 已改 AtomicBoolean。

---

## 10. 配置与启动

### 环境变量 / 启动参数（`application.yml` 可覆盖）
| 配置 | 环境变量 | 默认值 | 说明 |
|------|----------|--------|------|
| 服务端口 | `server.port` | `8080` | HTTP 端口 |
| 沙箱根 | `HARNESS_ROOT` / `--harness.root` | 空（=`<启动目录>/workspace`） | 文件操作边界 |
| 数据库 | `HARNESS_DB` | `db/harness.db` | SQLite 路径（相对启动目录） |
| LLM 端点 | `HARNESS_LLM_BASE_URL` | `https://api.deepseek.com/v1` | OpenAI 兼容 base-url |
| LLM Key | `HARNESS_LLM_API_KEY` | 空 → **Mock 模式** | 配了即走真实 LLM |
| 模型 | `HARNESS_LLM_MODEL` | `deepseek-chat` | API 模型名 |
| 模型显示名 | `HARNESS_LLM_NAME` | `DeepSeek V4 Flash` | 前端选择器展示名（按端点实际模型填写） |
| 深度思考模型 | `HARNESS_LLM_THINKING_MODEL` | `deepseek-reasoner` | 🧠 深度思考按钮使用的推理模型；同时是内置 `DeepSeek V4 Pro` 档案的实际模型（留空则 Pro 兜底 deepseek-reasoner 且 Flash 无思考开关） |
| 长任务检查点 | `harness.max-steps` | `30` | 主 Agent 每 N 轮注入反思提醒，不终止任务；子 Agent 使用独立局部上限 |
| 模式路由 | `HARNESS_CHAT_ROUTER` / `harness.chat-router` | `true` | CHAT 闲聊直答 / TASK 分流开关 |
| 历史条数上限 | `harness.history-limit` | `20` | 续聊携带的历史消息条数上限 |
| 自动沉淀记忆 | `harness.knowledge.auto-extract` | `true` | 会话后 LLM 自动提取长期记忆 |
| 内置模型列表 | `harness.models[]` | 空 | 系统预配模型（id/name/base-url/api-key/model/thinking-model） |
| MCP 审批 | `HARNESS_MCP_REQUIRE_APPROVAL` / `harness.mcp.require-approval` | `true` | `mcp__*` 工具 auto 模式也需审批；false 时随权限三档 |
| MCP 超时 | `harness.mcp.connect-timeout-ms` / `call-timeout-ms` | `10000` / `60000` | 握手超时 / tools-call 超时 |
| httpFetch 私网 | `HARNESS_HTTP_ALLOW_PRIVATE` / `harness.http.allow-private` | `false` | 放开则允许访问环回/私网地址（SSRF 防护开关） |
| 视觉模型 | `HARNESS_VISION_BASE_URL` / `harness.vision.base-url` | 空（继承 `harness.llm.base-url`） | 图片理解端点（OpenAI 兼容） |
| 视觉模型 Key | `HARNESS_VISION_API_KEY` / `harness.vision.api-key` | 空（继承 `harness.llm.api-key`） | 视觉模型鉴权 |
| 视觉模型名 | `HARNESS_VISION_MODEL` / `harness.vision.model` | 空（= 未启用图片理解） | 如 `qwen-vl-plus` / `glm-4v` / `gpt-4o-mini` |
| 图片上限 | `harness.vision.max-images` / `timeout-seconds` | `4` / `60` | 单条消息图片数 / 单张识别超时 |
| bash 工具开关 | `HARNESS_SHELL_ENABLED` / `harness.shell.enabled` | `true` | bash 命令执行工具总开关；false 时工具返回禁用错误（请求审批模式下每条命令先经用户确认） |
| bash 超时 | `harness.shell.default-timeout-seconds` / `max-timeout-seconds` | `60` / `300` | 单条命令默认/上限超时（模型传更大值被收敛） |
| 多 Agent 开关 | `HARNESS_MULTI_AGENT_ENABLED` / `harness.multi-agent.enabled` | `true` | M4 多 Agent 编排总开关；false 时行为与单 Agent 完全一致 |
| 多 Agent 护栏 | `harness.multi-agent.*` | `3/4/6/180/true` | `max-fanout` 单次拆分子任务上限 / `max-concurrency` 同时活跃 / `sub-agent-max-steps` 子 Agent 步数 / `sub-agent-timeout-seconds` 整体 deadline / `fail-isolation` 失败隔离 |
| 自动拆分开关 | `HARNESS_MULTI_AGENT_AUTO_DECOMPOSE` / `harness.multi-agent.auto-decompose` | `true` | false 时跳过计划期自动拆分（简单任务全走单 Agent）；spawnAgent 按需派子智能体不受影响（体验升级七） |
| 上下文长度（内置模型） | `HARNESS_LLM_CONTEXT_LENGTH` / `harness.llm.context-length` | `131072` | 内置默认/Pro 档案的上下文窗口（token）；`harness.models[].context-length` 可按档案覆盖，用户模型在设置页填写 |
| 上下文压缩 | `harness.context.*` | `true/0.8/6/2/true` | `enabled` 总开关 / `soft-threshold` 软阈值（超窗口比例即自动压缩）/ `keep-recent-turns` 对话级逐字保留条数 / `keep-recent-rounds` 任务级保留完整工具输出的轮数 / `request-usage` 请求携带 stream_options.include_usage |

### 构建与运行
```bash
# 后端：JDK 21+（开发机用 IntelliJ JBR 25 + 内置 Maven）
mvn -f harness/pom.xml package            # 产物 harness/target/harness.jar（前端已内嵌）

# 前端（如需改 UI 后重新内嵌）
cd frontend && npm install && npm run build   # 产物写入 harness/src/main/resources/static

# 运行（一键启动，前端随 jar 同起）
java -jar harness/target/harness.jar
# 或开发态：mvn -f harness/pom.xml spring-boot:run  （工作目录已固定为仓库根）

# 浏览器访问
#   http://localhost:8080
```

> 沙箱内 shell 若注入 `SERVER__PORT=0` 会随机化端口；本机测试建议 `env -u SERVER__PORT` 或显式 `--server.port=8080`。

---

## 11. 里程碑与路线图

| 阶段 | 目标 | 状态 |
|------|------|------|
| **M1 骨架** | loop + 文件工具 + 规划 + SQLite 轨迹 + 最小 Web UI + 本地启动绑定（F1–F11） | ✅ 已完成 |
| **M1 补充** | 多会话持久化与切换续聊 + 左侧导航栏 + 会话重命名/删除（F12–F14） | ✅ 已完成 |
| **M2 路由** | Embedding 检索 + 技能库(`skills` 表) + SkillExecutor + 技能管理页 | ✅ 已落地 |
| **体验升级一** | 流式输出 + 思考面板 + CHAT/TASK 模式路由 + 文件工具 v2（fileEdit/listFiles/delete/分页/覆盖）+ 前端 Markdown/停止/进度 + /api/file 安全修复 | ✅ 已完成（2026-08-29） |
| **体验升级二** | 多模型选择（内置+用户自配）+ 🧠深度思考 + 知识（长期记忆自动沉淀/注入）+ Codex 风格 UI + 文件预览抽屉 + 明暗双主题 + 知识页替代轨迹页 | ✅ 已完成（2026-08-29） |
| **项目模式** | 本地文件夹项目绑定 + 会话挂接（沙箱根切换）+ Git 状态/diff/提交/推送悬浮面板 + 模型显示名 | ✅ 已完成（2026-08-29） |
| **体验升级三** | 侧栏「项目/对话」折叠分组（项目入口移入侧栏）+ 文件夹选择器（/api/fs/list）+「新对话」跟随当前项目 + 双内置模型（Flash/Pro）+ 未跟踪行数口径修复 | ✅ 已完成（2026-08-29） |
| **体验修复一** | 权限三档（替我审批/请求审批/完全访问）+ 审批卡片与闸门 + 模式路由带上下文 + 流式断流看门狗与自动重试 + 气泡防溢出 + 代码块复制按钮 | ✅ 已完成（2026-08-29） |
| **技能体验一** | `/` 技能提及（淡蓝 chip + skillId 显式执行 + skill 事件）+ 技能库系统/个人分区（4 个内置系统技能播种）+ 存为技能两步弹窗 + 合成器上下文化描述/触发词 + .md 导入 | ✅ 已完成（2026-08-30） |
| **体验修复二** | Embedding 未配 Key 时零网络请求（NoopEmbeddingClient + 索引连续失败熔断，启动 ~90s→~5s）+ 技能页三列卡片网格（修复 nowrap 长文本撑破网格轨道导致的横向滚动）+ 全局 overscroll-behavior（触摸/触摸板滚动到边界不再回弹拖动） | ✅ 已完成（2026-08-28） |
| **M5 外部能力扩展** | 提示词式技能执行（散文 SKILL.md 注入 AgentLoop，兼容社区技能）+ MCP Host（Streamable HTTP）+ httpFetch 通用工具；开发文档见 `docs/plan/M5开发文档.md` | ✅ 已落地（2026-08-30） |
| **M4 多 Agent** | 计划期 DAG 拆分（Planner.decompose + 多重降级）+ 虚拟线程波次扇出（deadline/并发/步数三闸）+ 黑板登记占坑 + LLM 流式聚合 + 子 Agent 泳道 UI + 审批/权限/沙箱全继承；文档见 `docs/plan/M4开发文档.md`（v2） | ✅ 已落地（2026-08-30，默认开启） |
| **体验升级四** | bash 命令执行工具（审批门内 + 超时/输出截断护栏）+ 工具行聚合折叠与「正在执行 X」实时指示（tool_start 事件）+ 计划进度收尾修复（单 Agent 与多 Agent 两处 0/N bug）+ 文件抽屉改挤压式（composer/Git 面板联动） | ✅ 已完成（2026-08-30） |
| **体验升级五** | 图片理解（Ctrl+V/拖拽/📷 贴图 ≤4 张 → `harness.vision` 视觉模型转述 → vision 事件 + 描述注入 prompt，仅后端配置）+ 设置弹窗左侧导航四分区 + 项目任务「N 个文件已更改 +X −Y」聚合条（点击开抽屉行级红绿 diff）+ fileEdit CRLF 行尾自适应 | ✅ 已完成（2026-08-30） |
| **体验修复三 + 体验升级六** | 事件顺序渲染（执行计划插在 plan 事件位置，图片理解不再被压到下面）+ 模式路由身份问句快路径与分类解析加固 + 上下文容量徽标（真实 usage 闭环 + 启发式兜底）+ 两级压缩（对话级 LLM 摘要 ctx_summary 水位线 / 任务级工具输出机械占位替换）+ 400 超限紧急压缩重试 + 模型档案 context_length | ✅ 已完成（2026-09-03） |
| **体验升级七** | 子智能体体系（spawnAgent 按需派发 + agent_profiles 表 + 内置「代码探索」+ 用户自建角色 + 设置分区）+ grep 只读搜索工具 + 会话实时续看（StreamHub 事件缓冲 + GET /live 重放续流 + 侧栏转圈 + /stop 显式停止，断开不再杀任务）+ 扇出门槛收紧（简单小文件任务不再派子 Agent）+ 工具分组展开压扁修复 | ✅ 已完成（2026-09-05） |
| **徽标修复** | 真实 usage 持久化（conversations.last_prompt_tokens）+ 容量估算口径对齐真实请求（含工具定义 JSON）+ ASCII 系数/消息开销修正——徽标数字真实可信 | ✅ 已完成（2026-09-05） |
| **体验修复四** | 工具分组折叠态类型分解 + 展开去重 ×N/命令目标/内部滚动 + 文件 chip 云（>12 收敛「+N 个文件」）+ 步数耗尽收尾总结（无工具总结轮替代「已达最大步数」系统文案）+ max-steps 默认 30 | ✅ 已完成（2026-09-05） |
| **体验修复五** | 侧栏运行中转圈移到对话行最左（zcode 式）+ 任务过程折叠块（思考/说明/工具/文件时间序交织，done 收起为「已思考与执行 N 项工具」+ 结论常显）+ 模型档案可编辑（共用表单/留空保留原 Key） | ✅ 已完成（2026-09-05） |
| **体验修复六** | 最终结论自成一体（总结/文件清单/验证结果/遗留建议四段式，禁止延续中间步骤编号）+ 交付前验证（代码工程改动后必须 bash 编译/构建/测试通过才给结论）+ 项目对话列表折叠展开（状态持久化 + 当前会话所在项目强制展开） | ✅ 已完成（2026-09-05） |
| **体验升级八** | 计划模式（权限第四档 approval=plan：只读调研 + askUser 澄清提问（选项+推荐+自由输入）+ 计划文档结论；跳过路由/技能/多 Agent/Planner）+ 问题卡片 UI + 停止排水（/stop 唤醒挂起审批与提问） | ✅ 已完成（2026-09-05） |
| **体验修复七** | 计划模式只读硬闸门（主泳道工具名白名单校验，真实 LLM 击穿 specs 已验证）+ 系统提示计划分支只列只读工具（消除写工具文案诱导）+ 计划确认双选项决策条（执行此计划/补充细节信息替代输入框）+ 计划文档卡片（截断预览 + 查看完整计划抽屉）+ 执行此计划经 planText 程序内下发不回显 | ✅ 已完成（2026-09-06） |
| **体验修复八** | 通用确认弹窗组件（confirmDialog/ConfirmHost 替换全部 window.confirm）+ 表单取消/提交后重置（模型/MCP/子智能体/技能）+ 知识编辑表单样式对齐手动添加 + 技能页按钮样式统一 + 侧栏项目折叠纯手动控制 | ✅ 已完成（2026-09-06） |
| **体验升级九** | 消息气泡悬停操作（⧉ 复制任何轮次 / ✏ 编辑仅最后一轮）+ 编辑重发「撤回到输入框」（截断该轮含回复 → 内容回填 composer → 重新发送，上下文不再含被撤回轮）+ 运行中编辑自动停止并等收尾（running 轮询防乱序复活） | ✅ 已完成（2026-09-06） |
| **M3 进化** | 技能合成器（轨迹→参数化 `SKILL.md`）+ 沙箱校验 | ⬜ 未实现 |

---

## 12. 参考文档

- `M1开发文档.md` —— M1 主体功能清单、验收标准、API / Trace 表 / 沙箱伪码
- `M1开发文档-补充.md` —— F12–F14 会话管理与导航栏的详细规格
- `M2开发文档.md` —— 路由 + 技能层的目标与接口约定（已落地）
- `docs/plan/M5开发文档.md` —— 外部能力扩展（提示词式技能执行 + MCP Host + httpFetch）的功能清单、完成目标与验收标准（已落地）
- `docs/plan/M4开发文档.md` —— 多 Agent v2 修订版（计划期 DAG 数据依赖 + 虚拟线程扇出 + 取消/成本/权限护栏）的功能清单与验收标准（✅ 已落地，实现偏差见 AGENTS §9.23）
- `e2e_prose_skill_check.py` —— M5 阶段一验收（mode 判定与徽标、社区 frontmatter 兼容、prompt 技能全链路、未注册工具降级、步骤技能回归），需真实 LLM
- `e2e_mcp_check.py` —— M5 阶段二/三验收（MCP 服务器注册/连接/审批批准与拒绝/禁用注销/删除 + httpFetch 私网拒绝与公网抓取），依赖夹具 `fixtures/mcp_echo_server.py`（脚本自动启停），需真实 LLM
- `e2e_multiagent_check.py` —— M4 多 Agent 验收（全链路扇出与聚合、降级一致性、审批继承 + callId 前缀、黑板登记、历史回放还原执行树、依赖 DAG 波次），Mock 与真实 LLM 双模式自适应
- `e2e_check.py` —— 端到端验收脚本（会话、建页、建 docx、续聊、重命名、沙箱拦截）
- `e2e_v2_check.py` —— v2 事件协议验收（CHAT 直答 / TASK 循环 / 新文件工具 / file 事件 size / 安全修复），支持 Mock 与真实 LLM 双模式
- `e2e_fix_check.py` —— 体验修复验收（审批批准/拒绝流、完全访问绝对路径写入与默认拒绝对照、「继续」路由上下文），需真实 LLM
- `e2e_skill_check.py` —— 技能体验验收（系统技能播种/origin、skillId 显式调用与 skill 事件、from-trace 合成质量约束、导入解析、系统技能禁用恢复、回放），需真实 LLM
- `e2e_project_check.py` —— 项目模式验收（绑定/会话挂接/Git 状态/diff/提交/推送/工作根隔离），依赖夹具 `testproj/`（git 仓库，origin 指向本地裸远端 `testremote.git/`）与 `nogit-proj/`（非 git 目录）；夹具缺失时按脚本头部常量重建
- `e2e_vision_check.py` —— 图片理解验收（/api/config vision 状态、vision 事件与回放、用户消息原文落库、空 prompt 补位、超 4 张/非图片 400 拦截），Mock 与真实 LLM 双模式自适应；真实视觉模型调用需在 `harness.vision.model` 配置后验证
- `e2e_context_check.py` —— 上下文容量与压缩验收（容量状态接口、context_usage 事件、小窗口下对话级自动压缩与摘要承接续聊、手动压缩、context_compact 落库回放），Mock 与真实 LLM 双模式自适应；压缩触发项需小窗口（`HARNESS_LLM_CONTEXT_LENGTH=3000` 启动），默认窗口自动跳过
- `e2e_subagent_check.py` —— 子智能体体系验收（agent_profiles CRUD 与内置保护、spawnAgent 全链路泳道与落库回放、两文件简单任务不扇出），Mock 走确定性演示分支、真实模式宽松断言
- `e2e_live_check.py` —— 会话实时续看验收（hub_idle、running 标志、/live 重放含审批卡、运行中再发消息 409、跨连接审批、结束后复位、/stop 兜底），Mock 模式即可全量验收
- `e2e_task_metrics_check.py` —— P4 Run 生命周期、唯一终态事件、消息关联、指标明细和新旧 usage 聚合验收
- `e2e_task_outcome_check.py` —— P4 Outcome、历史回放、Run API 与 Markdown 报告一致性验收

---

## 13. 给后续开发 / AI 的提示

- 改 UI：在 `frontend/` 改，记得 `npm run build` 重新内嵌到后端（需重启 jar 生效）。
- 加工具：实现 `tools/Tool` 并在 `ToolRegistry` 注册（Spring 自动收集 Bean），沙箱路径一律经 `sandbox/Sandbox` 解析；同时更新 `AgentLoop.systemPrompt` 与 `MockChatClient` 的演示分支。基础工具（文件 + bash）加进 `ToolRegistry.BASE_TOOLS`（否则 systemPrompt 重复罗列）与 `MUTATING_TOOLS`（请求审批模式需弹卡时）；执行耗时类工具记得先 `out.sendLive(tool_start)`（transient，勿落库），前端靠 callId 配对熄灭指示行。
- 接 LLM：真实调用走 `OpenAiCompatChatClient`（SSE 流式）；无 Key 自动 `MockChatClient`，调试框架逻辑无需联网。需要流式回调用 `chat(msgs, tools, listener)`，内部调用（分类/规划）用两参重载。
- 事件协议：新增 SSE 事件时记得前端 `Chat.jsx` 的 onEvent 分支 + `TaskCard.EventView`；delta 类事件**不要**写入 transcript（落库膨胀）。
- 做 M3：从「任务成功后 LLM 复盘 traces → 生成参数化 SKILL.md → 沙箱校验 → 入库」起步，复用 `SkillLibrary` / `IntentRouter` / `EpisodicStore`。
- 查轨迹：接口保留 `GET /api/traces`；UI 已由知识页替代。
- 自进化记忆：`KnowledgeExtractor` 在会话任务后异步提取；改提取规则编辑其 system prompt；知识注入上限见 `TaskDispatcher.knowledgePrompt`。
- 回归验证：`python e2e_check.py`（M1/F12 基线）+ `python e2e_v2_check.py`（v2 协议）+ `python e2e_multiagent_check.py`（M4 多 Agent，Mock/真实双模式）+ `python e2e_project_check.py`（项目模式/Git）+ `python e2e_fix_check.py`（权限审批/完全访问/路由上下文，需真实 LLM）+ `python e2e_skill_check.py`（技能库/显式调用/合成，需真实 LLM）。
- 改技能层：系统内置技能在 `harness/src/main/resources/skills/builtin/`（启动播种到 `skills/system/`，注意其中的步骤是单行长 content，解析走 `SkillParser.parseKvPairs` 手写扫描器，勿改回正则）；技能显式调用链 = 前端 chip → `MessageRequest.skillId` → `TaskDispatcher` → `SkillExecutor`（含审批门）。
- 改审批/权限：写操作清单在 `AgentLoop.MUTATING_TOOLS`；放宽路径只动 `ToolContext.resolve`；不要绕过 `Sandbox` 直接 `Paths.get`。`bash` 也在 MUTATING_TOOLS（命令可变更任意文件）；ShellTool 的命令本身不受沙箱限制（这是工具的用途），工作目录经 `ToolContext.resolve` 解析、默认工作根。
- 改对话页布局：`.chat-flex`（弹性行）> `.chat-col`（对话列 + sticky composer）> 抽屉列 `--drawer-w`；抽屉打开时 `.chat-flex.drawer-open` 经 CSS 变量 `--drawer-offset` 把 Git 悬浮面板推离右缘——改抽屉宽度只需动 `:root` 的 `--drawer-w`。
- 改消息编辑重发（体验升级九）：三条不变量——① 截断（`DELETE /messages/{messageId}`）必须在服务端 `running=false` 之后调用（否则「已停止」结论会在删除后落库、以乱序复活；前端 startEdit 里 stop + waitUntilStopped 轮询 GET 元信息的 running 字段）；② 截断只允许从「最后一条用户消息」发起——该 id 恒大于对话摘要水位线（ctx_summary_until_id 只指向更早消息），不要开放任意中间消息的截断，否则摘要水位线语义被破坏；③ 编辑态经 `planDismissed` 快照接管计划决策条，退出编辑要恢复（cancelEdit），否则计划轮的决策条丢失。
- 改图片理解：转述提示词与降级文案在 `llm/VisionService`（SYSTEM_HINT / describe 的未配置分支）；注入点在 `TaskDispatcher` 步骤 3.5（路由用 `routePrompt` 原文、执行用追加描述后的 prompt——改这段时保持该分离）；上限/超时在 `harness.vision.*`。图片校验（前缀/大小/数量）在 `ConversationController.validateImages`，前端压缩在 `Chat.jsx readImageDataUrl`。
- 改任务变更聚合条：`TaskCard.jsx` 的 `TurnDiffBar`（触碰路径提取 = file 事件 + MUTATING_TOOLS 的 args.path；与 `fetchGitStatus` 清单交集）；diff 渲染/行号解析在 `FileDrawer.jsx` 的 `DiffView/diffRows`（吃 `GET /api/projects/{id}/git/diff` 的统一 diff 文本）。bash 产生的变更有意不纳入（语义=本轮文件工具改动），全量看 GitPanel。
- 项目模式开发：项目会话的工作根在 `ConversationController.send` 解析（DB→project.rootPath），`/api/file` 带 `conversationId` 时同理；Git 操作一律走 `GitService`，新增 git 能力前先读 §9.13 的三条解析陷阱。项目入口在侧栏（`Sidebar.jsx` 分组 + `FolderPickerModal`），目录浏览走 `FsController`（仅列子目录）。
- 多 Agent 开发：拆分在 `Planner.decompose`（system prompt 带 `[ROLE=DECOMPOSER]`，Mock 同名分支按「≥2 类交付动词 + ≥2 文件候选」触发）；编排在 `core/multi/Orchestrator`（波次调度/deadline/聚合/父 trace）；子 Agent 走 `AgentLoop.executeSubAgent`（事件由 `Emitter` 自动注入 `agent` 字段，勿在事件构造处手工加）；新增 SSE 事件若来自子线程，transcript 已是同步列表，直接 `Emitter.send` 即可；改拆分规则勿放松「输出路径不得重叠」与降级规则（宁可少扇出）。
- 改上下文压缩（体验升级六）：估算在 `llm/TokenEstimator`（启发式），压缩编排分两层——对话级在 `core/context/ContextService`（buildHistory 组装摘要+逐字窗口 / maybeCompact 软阈值触发）+ `ConversationCompactor`（LLM 摘要，`[ROLE=COMPACTOR]`）；任务级在 `core/context/ToolOutputCompactor`（原位占位替换，勿改成增删消息——tool 配对结构会破）；400 超限签名在 `AgentLoop.isContextOverflow`。压缩失败一律静默跳过、绝不阻断消息流。压缩触发验收用小窗口启动（`HARNESS_LLM_CONTEXT_LENGTH=3000`）。
- 改子智能体 / spawnAgent（体验升级七）：档案 CRUD 在 `AgentProfileStore` + `AgentController`，内置播种在 `core/agents/AgentLibrary`（prompt 首行 `[子智能体:代码探索]` 标记是 Mock 演示分支的锚点，改内置 prompt 时保留）；派发特判在 `AgentLoop.runLoop`（见 §9.28，勿改成注册表工具——循环依赖）；子智能体工具白名单 = `ToolRegistry.specsFor` + `SubAgentConfig.canUse` 两层；systemPrompt 的清单段在 `baseSystemPrompt`（agents 参数，ContextService 基线估算同步传空清单）。
- 改会话实时续看（体验升级七）：事件缓冲与广播在 `core/stream/StreamHub`；新的发送点若想进缓冲，调 `hub.record(event)`（参照 TaskDispatcher 的 vision/compact 事件）；前端事件归并统一走 `Chat.jsx` 的 `applyEvent`（POST 流与 live 流共用），live 接入在 `maybeAttachLive`（落点=最后一个「有 prompt 无结论」轮次）；**断开连接不再取消任务**，停止只能走 `POST /stop`（见 §9.29）；前端轮询在 `Sidebar.jsx`（3s，visibility 感知）。
- 改多 Agent 扇出门槛：真实 LLM 判据在 `Planner.decompose` 的 DECOMPOSER prompt（正反例驱动），Mock 在 `MockChatClient.decomposeJson`（≥2 类动词 + ≥3 文件）；给 E2E 构造扇出任务用实质性交付物（见 §9.30）。
- 改计划模式（体验升级八/修复七）：工具白名单 = `AgentLoop.PLAN_TOOLS` + `planModeSpecs()`（spawnAgent 手工并回）；只读保障 = runLoop 主泳道硬闸门（工具名 ∉ 白名单一律拒绝，勿删——specs/提示词都可能被真实模型击穿）+ 系统提示计划分支只列只读工具（勿把常规分支的写工具文案带回计划分支）；askUser 拦截特判见 §9.31；`QuestionGate`/`AskUserTool` 在 `core/question/`；前端问题卡在 `TaskCard.QuestionCard`（已答判定靠同 callId tool_call）；计划决策条/执行此计划在 `Chat.jsx`（planPending 推导 + implementPlan 经 `planText` 下发，气泡只显示「执行此计划」）；计划卡片在 `TaskCard.PlanDocCard` + `PlanDrawer.jsx`（挤压式抽屉）；Mock 演示分支锚点 = 系统提示 `[MODE=PLAN]` + `MockChatClient.planDocument`；E2E = `e2e_plan_check.py`（交互读取用后台线程+队列，勿用 socket timeout 轮询——chunked 流超时后 read1 会永久损坏）。
- 改 TaskCard 轮次渲染（体验修复五重构后）：TASK 轮所有过程事件收进 `ProcessBlock`（运行中自动展开、done 自动收起，结论/变更条/存为技能常显在外），过程内部由 `ChronoFlow` 按 `task.events` 时间序交织渲染——`thinking` → ThinkRow（摘要预览折叠行）、`step_commentary` → 正文、连续 `tool_call` → ToolGroup、连续 `file` → FileChips、`plan` → PlanView（仍插在 plan 事件位置）、`approval_request` → 原位审批卡；子 Agent 泳道与黑板仍在 ProcessBlock 体内、ChronoFlow 之前。CHAT 直答轮（无事件）走 `ThinkingPanel` 原形态。勿把事件改回「顶部聚合 + 尾部分组」三段式——时间序是本修复的核心诉求。
32. **自进化流水线（P0，改代码前必读）**：挂接点在 `TaskDispatcher.run` 的 finally 块（`knowledgeExtractor.extractAsync` 之后），**不在四个执行器内部**——traceId 由执行器各自落库，流水线靠「会话最后一条带 traceId 的 assistant 消息」反查 `EpisodicStore.get`；改四个执行器的落库逻辑时保持 `messages.trace_id` 关联不断。`usedSkill` 标记只认 TaskDispatcher 的显式技能/意图命中两个分支（prompt 型技能经 SkillExecutor.executeGuided 委托 AgentLoop 也算技能路径）；`approval=plan` 轮一律跳过合成（产出是计划文档不是可执行轨迹）。成功判定 = trace.outcome 非空、非 `ERROR:` 前缀、非 `（已停止）`，且 steps 非空数组（CHAT 直答恒 `[]` 被排除）。聚类无 Embedding Key 时降级为**字符 bigram Jaccard**（与 IntentRouter 的子串匹配是两套算法，勿混用）；Mock 演示闭环依赖 `mockSkillMeta` 的 test_input 包含触发词短语（关键词路由按子串命中），改触发词生成逻辑时保持这一包含关系。SkillValidator 的 prompt 型校验是**独立 mini 工具循环**（手工拼 `LlmMessage.assistant/tool` 回灌，≤4 轮），不走 AgentLoop——别给它接 emitter/审批；临时目录 `workspace/.validator/draft-{id}/` 执行后递归删除，E2E 断言其无残留。技能统计（hit_count）在 `SkillExecutor.execute` 入口统一计数，**不要**在 TaskDispatcher 分支里重复加；`incrementFail` 对「用户拒绝步骤」也计数（属执行失败），但「用户停止」不计。E2E 构造「同类任务」文本必须避开 4 个内置技能的触发词（尤其 frontend-design 的「做个登录页」是字面子串），否则 usedSkill=true 流水线直接跳过；`e2e_skill_check.py` 的 BASE 原为硬编码 localhost:8080，已改为读 `HARNESS_BASE`。
33. **P1 编码硬实力（改代码前必读）**：① **验证钩子闸门在 AgentLoop 结论分支内**——模型给出无工具调用回复时先跑 hooks、失败 `continue` 回灌（消耗轮次预算，受 max-steps 与 max-retries 双重约束）；给模型发钩子反馈前必须先 `msgs.add(LlmMessage.assistant(proposed, null))`（无工具调用回复默认不入 msgs，跳过会破坏消息序列）。② **codeSearch 与增量索引的 scope 是工作根指纹**——子 Agent 用 worktree 时是独立 scope（各自建索引，互不污染）；增量挂接点在 AgentLoop 工具成功分支（fileWrite/fileEdit/fileDelete/docxWrite），HookService/Validator 的沙箱写不在挂接面（临时目录不入索引）。③ **worktree 懒创建是正确性要求而非优化**：预创建会让下游 worktree 缺上游产物（分支从旧 HEAD 切出）；merge 全程 synchronized（主根 git index 不容并发）；`--show-toplevel` 输出是正斜杠（`D:/a/b`），必须 Path 规范化后再比较（曾因此整轮回退共享沙箱）。④ **usage_log 只记主泳道**（emitUsage mainLane + ChatExecutor），模型归属取 `client.modelName()` 而非 task.modelId（轻模型路由下两者不同）；Mock 的 usage 是估算填充但 estimated=false（客户端视角是真实回传）。⑤ **ContextMeter 等组件的 useEffect 必须在早退 return 之前**（hooks 条件调用会整页白屏，已踩）。⑥ 测试基建：E2E 任务文本要含 Mock 分类器的 TASKY 触发词（查看/文件/做个…），否则被 CHAT 路由分流、工具分支不触发；bash heredoc 里写 `\\n` 会被还原成真实换行——Java 字符串补丁一律走「Write 工具写脚本文件 + python 执行」。
34. **P2 采用门槛（改代码前必读）**：① **执行报告与前端 buildTurns 的轮次模型同构**（user 消息开轮、assistant 挂到最近无结论轮）——改消息落库结构时两边同步；report 的 messageId 是**用户消息 id**（前端 task.id=`m-{id}` 解析），live 轮无落库 id 时靠 `isLastTurn` 兜底（端点缺省=最后一个带结论轮次），TaskCard 报告按钮的显隐条件是 `messageId!=null || isLastTurn`，勿改成无条件显示（老轮次会导出错轮）。② **项目会话变更摘要是「事件触碰路径 ∩ 当前 git status」**（对齐前端 TurnDiffBar）——已提交的变更不在报告里，这是口径特性不是 bug。③ **Mock 模式触发条件是 `HARNESS_LLM_API_KEY` 置空**（set-but-empty 覆盖 yml 默认）——application.yml 的默认 Key 是作者本机便利配置，**公开仓库前必须按 docs/发布清单.md §0 轮换并置空**。④ 欢迎卡/示例卡的显隐依赖 `fetchConversations()` 的会话计数（bootInfo effect 以 conversationId 为依赖），首条消息发出后 conversationId 变化即自动消失——不要给欢迎卡再加独立持久化以外的关闭状态。⑤ 示例卡「绑定本地项目」经 `window` 事件 `harness:bind-project` 由 Sidebar 打开选择器（picker 状态在 Sidebar）——Chat 与 Sidebar 间不要新增反向 import。⑥ 演示素材在 `docs/images/`（GIF 由 `.capture/assemble.py` 合成，采集帧不入库）；重录步骤见 `docs/演示素材录制脚本.md`，README 引用路径固定勿改名。⑦ Docker 镜像不含 Node 构建阶段（前端产物随仓库提交在 `harness/src/main/resources/static/`）——**改前端必须先 `npm run build` 再提交 static 产物**，否则 Docker 构建的 jar 是旧界面。
