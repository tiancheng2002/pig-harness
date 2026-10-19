# 接入 GitHub MCP 服务器指南

> 前提：Harness 已支持 MCP 双传输（Streamable HTTP + stdio 本地进程，2026-09-26）。
> GitHub 官方提供两种形态，本指南分别覆盖：
> - **方式 A：远程托管**（`https://api.githubcopilot.com/mcp/`）——最简单，HTTP 直连，本机无需装任何东西；
> - **方式 B：本地 stdio**（`github/github-mcp-server`，官方 Go 实现）——数据流走本机，Docker 或二进制均可。

---

## 第 0 步：创建 GitHub Personal Access Token（PAT）

1. 打开 <https://github.com/settings/personal-access-tokens>（细粒度 PAT，推荐）→ **Generate new token**；
2. **Token name**：随意（如 `harness-mcp`）；**Expiration**：按需（如 90 天）；
3. **Resource owner**：选你自己（或目标组织）；
4. **Repository access**：
   - 只读检索 → `Public repositories (read-only)`；
   - 要建 Issue/PR/写文件 → `All repositories` 或指定仓库；
5. **Permissions → Repository permissions** 按需勾选：
   - 基础使用：`Metadata: Read`（默认必选）+ `Contents: Read` + `Issues: Read` + `Pull requests: Read`；
   - 需要写操作 → 对应项升级为 `Read and write`；
6. **Generate token**，复制 `github_pat_...`（只显示一次）。

---

## 方式 A：远程托管（推荐，2 分钟接完）

网页 **设置 → MCP 服务器 → ＋ 添加 MCP 服务器**：

| 字段 | 填写 |
|------|------|
| 名称 | `github` |
| 传输方式 | `HTTP（Streamable）`（默认，不用改） |
| URL | `https://api.githubcopilot.com/mcp/` |
| 自定义请求头 | `{"Authorization": "Bearer github_pat_你的PAT"}` |

点「保存并连接」→ 状态徽标变绿并显示工具数（默认工具集约 30+：issues / PR / Actions / 代码检索等）。
工具以 `mcp__github__*` 命名注册进 Agent 工具清单。

> 进阶：URL 换 `https://api.githubcopilot.com/mcp/insiders` 可提前试用实验性工具（详见官方 README 的 Insiders Mode）。

---

## 方式 B：本地 stdio（数据流走本机）

### B1. Docker 方式（需要本机已安装并运行 Docker Desktop）

设置 → MCP 服务器 → 添加，**传输方式选 `stdio（本地子进程）`**：

| 字段 | 填写 |
|------|------|
| 名称 | `github` |
| 传输方式 | `stdio` |
| 启动命令 | `docker` |
| 启动参数 | `run -i --rm -e GITHUB_PERSONAL_ACCESS_TOKEN ghcr.io/github/github-mcp-server` |
| 环境变量 | `{"GITHUB_PERSONAL_ACCESS_TOKEN": "github_pat_你的PAT"}` |

说明：
- 环境变量字段的 JSON 会被注入 docker 进程环境，`-e GITHUB_PERSONAL_ACCESS_TOKEN`（不带值）再把变量透传进容器——PAT 不会出现在参数明文里；
- 首次连接需要拉镜像（可能 1~2 分钟），stdio 的 initialize 超时默认 60 秒（`harness.mcp.stdio-init-timeout-ms` 可调）；镜像拉过之后就秒连。

### B2. 二进制方式（不想装 Docker）

1. 到 <https://github.com/github/github-mcp-server/releases> 下载 `github-mcp-server_Windows_x86_64.zip`；
2. 解压到固定目录，如 `D:\tools\github-mcp-server\github-mcp-server.exe`；
3. 添加服务器（传输方式 `stdio`）：

| 字段 | 填写 |
|------|------|
| 启动命令 | `D:\tools\github-mcp-server\github-mcp-server.exe`（含空格的路径请整体加双引号） |
| 启动参数 | `stdio` |
| 环境变量 | `{"GITHUB_PERSONAL_ACCESS_TOKEN": "github_pat_你的PAT"}` |

> Windows 上启动命令写裸名 `npx` / `docker` 即可，Harness 会自动在 PATH 中解析 `.cmd`/`.exe`；
> 给绝对路径则必须写到 `.exe`（或带扩展名）。

### 可选：收窄工具集 / 只读模式

官方支持 `--toolsets` 选择工具集（如只要 issues + repos：启动参数末尾加 `--toolsets "repos,issues"`）、
`--read-only` 只读模式。以官方 README 为准：<https://github.com/github/github-mcp-server>。

---

## 验证接入

1. **状态**：设置弹窗该服务器行显示绿色「N 工具」徽标；
   - 一直「连接中…」→ 冷启动慢（B1 首次拉镜像），等一会或调大 `harness.mcp.stdio-init-timeout-ms`；
   - 「连接失败」→ 看行内 lastError（Docker 未启动 / PAT 无效 / 命令不存在都会写在这里）；
2. **对话验证**（权限保持默认 auto 即可）：

   ```text
   列出 github/github-mcp-server 仓库最近的 3 个 issue 标题
   ```

   Agent 会调用 `mcp__github__list_issues` 等工具；`harness.mcp.require-approval` 默认开启，
   首次调用会弹审批卡，点批准继续（免去每次审批：`application.yml` 中改为 `false`）；
3. **写操作验证**（按需）：如「在 我的用户名/某仓库 创建一个 issue，标题 test-mcp，正文 hello」。

## 故障排查

| 现象 | 处理 |
|------|------|
| 无法启动 MCP 服务器进程 docker | Docker Desktop 未安装/未运行；或改用 B2 二进制方式 |
| initialize 超时（60s） | 首次拉镜像慢：手动 `docker pull ghcr.io/github/github-mcp-server` 后重试，或调大 `harness.mcp.stdio-init-timeout-ms` |
| 401 / Bad credentials | PAT 过期或复制不全；重新生成后在环境变量里更新，点该行 ↻ 重连 |
| 工具调用返回 403 | PAT 权限不足：回到第 0 步第 5 条补权限，或 PAT 的 resource owner / repository access 范围不对 |
| 想看服务器日志 | stdio 子进程的 stderr 会进 Harness 控制台日志（`[mcp-stdio:服务器名]` 前缀） |

## 安全提示

- PAT 存在本地 SQLite（mcp_servers.env_json），仅本机使用，请勿把数据库文件提交进仓库；
- 外部工具（`mcp__*`）默认每次执行需审批（`harness.mcp.require-approval: true`），写操作建议保持开启；
- 远程方式下 PAT 走请求头（存 env_json/headers_json 同表），不要把 PAT 粘贴进对话内容。
