// ===== 配置 =====
export async function fetchConfig() {
  const res = await fetch('/api/config')
  if (!res.ok) throw new Error('config ' + res.status)
  return res.json()
}

// ===== 会话管理（F12/F14）=====
export async function createConversation(projectId) {
  const res = await fetch('/api/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(projectId ? { projectId } : {})
  })
  if (!res.ok) throw new Error('创建会话失败: ' + res.status)
  return res.json()
}

export async function fetchConversationMeta(id) {
  const res = await fetch(`/api/conversations/${id}`)
  if (!res.ok) throw new Error('会话读取失败: ' + res.status)
  return res.json()
}

export async function fetchConversations() {
  const res = await fetch('/api/conversations')
  if (!res.ok) throw new Error('会话列表失败: ' + res.status)
  return res.json()
}

export async function fetchConversationMessages(id) {
  const res = await fetch(`/api/conversations/${id}/messages`)
  if (!res.ok) throw new Error('消息读取失败: ' + res.status)
  return res.json()
}

export async function renameConversation(id, title) {
  const res = await fetch(`/api/conversations/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title })
  })
  return res.ok
}

export async function deleteConversation(id) {
  const res = await fetch(`/api/conversations/${id}`, { method: 'DELETE' })
  return res.ok
}

/**
 * 从某条消息起截断（消息编辑重发第一步）：删除该消息及其后的全部消息（撤回最后一轮对话含回复）。
 * 返回 {ok, removed}；会话运行中返回 409（调用前需先 stop 并等待收尾）。
 */
export async function truncateMessagesFrom(id, messageId) {
  const res = await fetch(`/api/conversations/${id}/messages/${messageId}`, { method: 'DELETE' })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || err.message || '消息撤回失败: ' + res.status)
  }
  return res.json()
}

// ===== 上下文容量与压缩 =====
export async function fetchConversationContext(id, modelId) {
  const q = modelId ? `?modelId=${encodeURIComponent(modelId)}` : ''
  const res = await fetch(`/api/conversations/${id}/context${q}`)
  if (!res.ok) throw new Error('上下文状态读取失败: ' + res.status)
  return res.json()
}

// P1 成本观测：会话累计用量（轮数/token/成本）
export async function fetchConversationUsage(id) {
  const res = await fetch(`/api/conversations/${id}/usage`)
  if (!res.ok) throw new Error('用量读取失败: ' + res.status)
  return res.json()
}

export async function fetchTaskRun(runId) {
  const res = await fetch(`/api/task-runs/${encodeURIComponent(runId)}`)
  if (!res.ok) throw new Error('任务运行读取失败: ' + res.status)
  return res.json()
}

export async function fetchTaskRunMetrics(runId, limit = 200) {
  const res = await fetch(`/api/task-runs/${encodeURIComponent(runId)}/metrics?limit=${limit}`)
  if (!res.ok) throw new Error('任务指标读取失败: ' + res.status)
  return res.json()
}

export async function fetchConversationRuns(id, messageId) {
  const q = messageId == null ? '' : `?messageId=${encodeURIComponent(messageId)}`
  const res = await fetch(`/api/conversations/${id}/runs${q}`)
  if (!res.ok) throw new Error('任务运行列表读取失败: ' + res.status)
  return res.json()
}

export async function compactConversation(id) {
  const res = await fetch(`/api/conversations/${id}/compact`, { method: 'POST' })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || err.reason || '压缩失败: ' + res.status)
  }
  return res.json()
}

// ===== 项目模式（本地文件夹项目 + Git 集成）=====
export async function fetchProjects() {
  const res = await fetch('/api/projects')
  if (!res.ok) throw new Error('项目列表失败: ' + res.status)
  return res.json()
}

export async function createProject(name, rootPath) {
  const res = await fetch('/api/projects', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, rootPath })
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || err.message || '创建项目失败: ' + res.status)
  }
  return res.json()
}

export async function deleteProject(id) {
  const res = await fetch(`/api/projects/${id}`, { method: 'DELETE' })
  return res.ok
}

/** 浏览本地目录（文件夹选择弹窗数据源）。path 为空时返回盘符根列表。 */
export async function fsList(path) {
  const qs = path ? `?path=${encodeURIComponent(path)}` : ''
  const res = await fetch('/api/fs/list' + qs)
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || err.message || '目录读取失败: ' + res.status)
  }
  return res.json()
}

/** 项目内文件联想（输入框 @ 引用）：返回 {files:[{name,path}], truncated}，最多 10 条。 */
export async function searchProjectFiles(projectId, q, signal) {
  const res = await fetch(`/api/projects/${projectId}/files?q=${encodeURIComponent(q || '')}`, { signal })
  if (!res.ok) throw new Error('文件搜索失败: ' + res.status)
  return res.json()
}

export async function fetchGitStatus(projectId) {
  const res = await fetch(`/api/projects/${projectId}/git`)
  if (!res.ok) throw new Error('Git 状态读取失败: ' + res.status)
  return res.json()
}

export async function fetchGitDiff(projectId, path) {
  const res = await fetch(`/api/projects/${projectId}/git/diff?path=${encodeURIComponent(path)}`)
  if (!res.ok) throw new Error('diff 读取失败: ' + res.status)
  return res.json()
}

export async function gitCommit(projectId, message) {
  const res = await fetch(`/api/projects/${projectId}/git/commit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message })
  })
  const data = await res.json().catch(() => ({}))
  return { ok: res.ok && data.ok !== false, message: data.message || `HTTP ${res.status}` }
}

export async function gitPush(projectId) {
  const res = await fetch(`/api/projects/${projectId}/git/push`, { method: 'POST' })
  const data = await res.json().catch(() => ({}))
  return { ok: res.ok && data.ok !== false, message: data.message || `HTTP ${res.status}` }
}

// ===== 模型管理（多模型 + 深度思考）=====
export async function fetchModels() {
  const res = await fetch('/api/models')
  if (!res.ok) throw new Error('模型列表失败: ' + res.status)
  return res.json()
}

export async function createModel(payload) {
  const res = await fetch('/api/models', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || '添加模型失败: ' + res.status)
  }
  return res.json()
}

export async function updateModel(id, patch) {
  const res = await fetch(`/api/models/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch)
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || '更新模型失败: ' + res.status)
  }
  return res.json()
}

export async function deleteModel(id) {
  const res = await fetch(`/api/models/${id}`, { method: 'DELETE' })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || '删除模型失败: ' + res.status)
  }
  return true
}

// ===== 知识（长期记忆）=====
export async function fetchKnowledge(category, status = 'all') {
  const qs = new URLSearchParams()
  if (category && category !== '全部') qs.set('category', category)
  if (status !== 'all') qs.set('status', status)
  const res = await fetch('/api/knowledge' + (qs.size ? `?${qs}` : ''))
  if (!res.ok) throw new Error('知识读取失败: ' + res.status)
  return res.json()
}

export async function createKnowledge(payload) {
  const res = await fetch('/api/knowledge', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.error || `保存失败: ${res.status}`)
  }
  return res.json()
}

export async function updateKnowledge(id, patch) {
  const res = await fetch(`/api/knowledge/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch)
  })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.error || `更新失败: ${res.status}`)
  }
  return true
}

export async function deleteKnowledge(id) {
  const res = await fetch(`/api/knowledge/${id}`, { method: 'DELETE' })
  return res.ok
}

export async function extractKnowledge(conversationId) {
  const res = await fetch('/api/knowledge/extract', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(conversationId ? { conversationId } : {})
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || '提取失败: ' + res.status)
  }
  return res.json()
}

// ===== SSE 流解析（共用）=====
async function parseSse(res, onEvent) {
  if (!res.ok || !res.body) {
    throw new Error('HTTP ' + res.status)
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const chunk = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      for (const line of chunk.split('\n')) {
        const trimmed = line.trim()
        if (trimmed.startsWith('data:')) {
          const payload = trimmed.slice(5).trim()
          if (payload) {
            try { onEvent(JSON.parse(payload)) } catch { /* 忽略坏帧 */ }
          }
        }
      }
    }
  }
}

/**
 * 在会话内发送消息（SSE 续聊）。
 * body: {prompt, modelId, deepThink, approval}
 * approval: auto（替我审批，默认）/ ask（写操作请求审批）/ full（完全访问，允许沙箱外路径）
 * onEvent: {type: plan|plan_step|text_delta|thinking_delta|thinking|step_commentary|tool_call|approval_request|reflect|file|done}
 * signal: 可选 AbortSignal，用于「停止」按钮中断执行。
 */
export async function streamConversationMessage(conversationId, body, onEvent, signal) {
  const res = await fetch(`/api/conversations/${conversationId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal
  })
  await parseSse(res, onEvent)
}

/** 审批接口：批准/拒绝一次待确认的写操作（请求审批模式）。 */
export async function resolveApproval(conversationId, callId, approve) {
  const res = await fetch(`/api/conversations/${conversationId}/approval`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ callId, approve })
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || '审批失败: ' + res.status)
  }
  return true
}

/** 澄清提问应答（计划模式）：answer 为所选选项文本或自由输入。 */
export async function resolveQuestion(conversationId, callId, answer) {
  const res = await fetch(`/api/conversations/${conversationId}/question`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ callId, answer })
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || '应答失败: ' + res.status)
  }
  return true
}

/**
 * 接入会话实时流（体验升级七）：GET /live。
 * 任务运行中 → 重放缓冲事件后继续实时推送（收到 done/hub_end 自然结束）；
 * 无运行任务 → 收到 {type:'hub_idle'} 后服务端关闭。
 */
export async function streamConversationLive(conversationId, onEvent, signal) {
  const res = await fetch(`/api/conversations/${conversationId}/live`, { signal })
  await parseSse(res, onEvent)
}

/** 显式停止会话当前任务（断开连接不再取消任务，停止必须走此接口）。 */
export async function stopConversation(id) {
  try {
    await fetch(`/api/conversations/${id}/stop`, { method: 'POST' })
  } catch { /* 服务端尽力停止，失败不阻塞前端收尾 */ }
  return true
}

/** 兼容入口：无会话任务（POST /api/task）。 */
export async function streamTask(body, onEvent, signal) {
  const res = await fetch('/api/task', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal
  })
  await parseSse(res, onEvent)
}

// ===== 技能库（M2）=====
export async function fetchSkills() {
  const res = await fetch('/api/skills')
  if (!res.ok) throw new Error('skills ' + res.status)
  return res.json()
}

export async function fetchSkill(id) {
  const res = await fetch(`/api/skills/${id}`)
  if (!res.ok) throw new Error('skill ' + res.status)
  return res.json()
}

export async function createSkill(name, content) {
  const res = await fetch('/api/skills', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, content })
  })
  if (!res.ok) throw new Error('create skill ' + res.status)
  return res.json()
}

export async function updateSkill(id, patch) {
  const res = await fetch(`/api/skills/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch)
  })
  if (!res.ok) throw new Error('update skill ' + res.status)
  return res.json()
}

export async function deleteSkill(id) {
  const res = await fetch(`/api/skills/${id}`, { method: 'DELETE' })
  return res.ok
}

export async function reindexSkill(id) {
  const res = await fetch(`/api/skills/${id}/reindex`, { method: 'POST' })
  return res.ok
}

export async function saveSkillFromTrace(traceId, name) {
  const res = await fetch('/api/skills/from-trace', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ traceId, name })
  })
  if (!res.ok) throw new Error('save skill ' + res.status)
  return res.json()
}

// ===== 自进化：技能草稿 + 进化统计（P0）=====
export async function fetchDrafts(status) {
  const res = await fetch('/api/skills/drafts' + (status ? `?status=${encodeURIComponent(status)}` : ''))
  if (!res.ok) throw new Error('drafts ' + res.status)
  return res.json()
}

export async function createDraft(name, content, sampleTestInput) {
  const res = await fetch('/api/skills/drafts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, content, sampleTestInput })
  })
  if (!res.ok) throw new Error('create draft ' + res.status)
  return res.json()
}

export async function validateDraft(id) {
  const res = await fetch(`/api/skills/drafts/${id}/validate`, { method: 'POST' })
  if (!res.ok) throw new Error('validate draft ' + res.status)
  return res.json()
}

export async function commitDraft(id) {
  const res = await fetch(`/api/skills/drafts/${id}/commit`, { method: 'POST' })
  if (!res.ok) throw new Error('commit draft ' + res.status)
  return res.json()
}

export async function rejectDraft(id) {
  const res = await fetch(`/api/skills/drafts/${id}/reject`, { method: 'POST' })
  return res.ok
}

export async function deleteDraft(id) {
  const res = await fetch(`/api/skills/drafts/${id}`, { method: 'DELETE' })
  return res.ok
}

export async function fetchEvolution() {
  const res = await fetch('/api/skills/evolution')
  if (!res.ok) throw new Error('evolution ' + res.status)
  return res.json()
}

// ===== MCP 服务器管理（M5 外部能力）=====
export async function fetchMcpServers() {
  const res = await fetch('/api/mcp/servers')
  if (!res.ok) throw new Error('mcp servers ' + res.status)
  return res.json()
}

export async function createMcpServer(payload) {
  const res = await fetch('/api/mcp/servers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || err.message || '添加 MCP 服务器失败: ' + res.status)
  }
  return res.json()
}

export async function updateMcpServer(id, patch) {
  const res = await fetch(`/api/mcp/servers/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch)
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || err.message || '更新 MCP 服务器失败: ' + res.status)
  }
  return res.json()
}

export async function deleteMcpServer(id) {
  const res = await fetch(`/api/mcp/servers/${id}`, { method: 'DELETE' })
  return res.ok
}

export async function refreshMcpServer(id) {
  const res = await fetch(`/api/mcp/servers/${id}/refresh`, { method: 'POST' })
  if (!res.ok) throw new Error('刷新失败: ' + res.status)
  return res.json()
}

export async function fetchMcpServerTools(id) {
  const res = await fetch(`/api/mcp/servers/${id}/tools`)
  if (!res.ok) throw new Error('工具列表读取失败: ' + res.status)
  return res.json()
}

// ===== 子智能体（spawnAgent 体系）=====
export async function fetchAgents() {
  const res = await fetch('/api/agents')
  if (!res.ok) throw new Error('agents ' + res.status)
  return res.json()
}

export async function createAgent(payload) {
  const res = await fetch('/api/agents', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || err.message || '添加子智能体失败: ' + res.status)
  }
  return res.json()
}

export async function updateAgent(id, patch) {
  const res = await fetch(`/api/agents/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch)
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || err.message || '更新子智能体失败: ' + res.status)
  }
  return res.json()
}

export async function deleteAgent(id) {
  const res = await fetch(`/api/agents/${id}`, { method: 'DELETE' })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || err.message || '删除失败: ' + res.status)
  }
  return true
}
