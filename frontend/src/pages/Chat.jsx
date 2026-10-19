import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  compactConversation,
  createConversation,
  fetchConfig,
  fetchConversations,
  fetchConversationContext,
  fetchConversationMessages,
  fetchConversationMeta,
  fetchConversationUsage,
  fetchModels,
  fetchSkills,
  resolveApproval,
  resolveQuestion,
  searchProjectFiles,
  stopConversation,
  streamConversationLive,
  streamConversationMessage,
  truncateMessagesFrom
} from '../api.js'
import TaskCard from '../components/TaskCard.jsx'
import FileDrawer from '../components/FileDrawer.jsx'
import PlanDrawer from '../components/PlanDrawer.jsx'
import GitPanel from '../components/GitPanel.jsx'
import PromptEditor from '../components/PromptEditor.jsx'
import { IconCheck, IconChevronDown, IconFolder, IconShield, IconHelp, IconUnlock, IconLayers, IconFile, IconBook } from '../components/icons.jsx'
import Select from '../components/Select.jsx'

/** 空态快速指引（点击填入输入框）。 */
const COMMON_EXAMPLES = [
  '帮我创建一个 login.html 登录页面，包含用户名/密码输入框和提交按钮',
  '生成一份 Word 周报，含本周进展与风险两项',
  '先列出 workspace 里有哪些文件，再把 readme.md 的标题改成中文',
  '用一句话解释什么是闭包（对话模式，直接回答）'
]
const PROJECT_EXAMPLES = [
  '看一下项目结构，指出可以优化的地方',
  '修复 utils.js 里的空指针问题，只做最小改动',
  '给 readme.md 加一个安装说明章节'
]

/**
 * P2-F3 首跑示例任务卡 ×3（空态常显，可关闭并记忆 localStorage）。
 * ① Mock 全链路（规划/工具/文件产出，无需 Key）；② 项目模式（引导绑定文件夹 + Git 面板）；
 * ③ 技能执行（内置技能命中演示）。action==='bind-project' 时派发事件由 Sidebar 打开文件夹选择器。
 */
const SAMPLE_CARDS = [
  {
    icon: '🧩',
    title: '跑一个完整任务',
    desc: '自动规划 → 工具执行 → 文件产出 → 变更汇总。未配置 API Key 时以 Mock 演示模式全链路可跑。',
    prompt: '帮我创建一个 login.html 登录页面，包含用户名/密码输入框和提交按钮',
    cta: '填入任务'
  },
  {
    icon: '📁',
    title: '绑定本地项目',
    desc: '选择一个本地文件夹（建议先在其中 git init），对话改动会实时汇总到右侧 Git 面板，可看行级 diff 并直接提交。',
    action: 'bind-project',
    cta: '选择文件夹'
  },
  {
    icon: '⚡',
    title: '触发一次技能执行',
    desc: '内置技能库含周报生成 / 前端设计 / 代码评审，消息命中触发词后按 SKILL.md 步骤执行，也可输入 / 手动选择。',
    prompt: '帮我生成一份本周周报，包含进展与风险两项',
    cta: '填入任务'
  }
]

/** 文件图标按扩展名分类着色（md 文档 / 源码 / web / 配置 / 图片 / 其它）。 */
function fileIconKind(name) {
  const ext = (name.match(/\.([a-z0-9]+)$/i)?.[1] || '').toLowerCase()
  if (['md', 'markdown', 'txt', 'doc', 'docx', 'pdf', 'rtf'].includes(ext)) return 'doc'
  if (['js', 'jsx', 'ts', 'tsx', 'java', 'py', 'go', 'rs', 'c', 'h', 'cpp', 'cs', 'rb', 'php', 'kt', 'sh', 'bat', 'cmd', 'sql'].includes(ext)) return 'code'
  if (['html', 'htm', 'css', 'scss', 'less', 'vue', 'xml', 'svg'].includes(ext)) return 'web'
  if (['json', 'yml', 'yaml', 'toml', 'ini', 'conf', 'properties', 'lock'].includes(ext)) return 'conf'
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'ico', 'bmp'].includes(ext)) return 'img'
  return 'other'
}

/** 正文内的文件引用保持原位；filePaths 仅作为旧接口兼容字段。 */
function fileRefsIn(text) {
  return [...new Set([...text.matchAll(/@\{([^{}\r\n]+)\}/g)].map(m => m[1].trim()).filter(Boolean))].slice(0, 10)
}

function fileRefRanges(text) {
  return [...text.matchAll(/@\{([^{}\r\n]+)\}/g)].map(match => ({
    start: match.index,
    end: match.index + match[0].length,
    path: match[1].trim()
  }))
}

/** 文件引用是原子节点：任何落在 token 内部的光标都吸附到最近边界。 */
function normalizeFileCaret(text, offset, direction = 'nearest') {
  const ref = fileRefRanges(text).find(item => offset > item.start && offset < item.end)
  if (!ref) return offset
  if (direction === 'left') return ref.start
  if (direction === 'right') return ref.end
  return offset - ref.start <= ref.end - offset ? ref.start : ref.end
}

export default function Chat() {
  const { conversationId } = useParams()
  const navigate = useNavigate()
  const [turns, setTurns] = useState([])
  const [prompt, setPrompt] = useState('')
  const [running, setRunning] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [models, setModels] = useState([])
  const [modelId, setModelId] = useState(() => localStorage.getItem('harness.modelId') || '')
  const [deepThink, setDeepThink] = useState(() => localStorage.getItem('harness.deepThink') === '1')
  const [approval, setApproval] = useState(() => localStorage.getItem('harness.approval') || 'auto')
  const [drawer, setDrawer] = useState(null) // {path, size, diff?}
  const [planDrawer, setPlanDrawer] = useState(null) // 计划文档抽屉 {text}（计划卡片「查看完整计划」打开）
  const [planRefine, setPlanRefine] = useState(false) // 计划决策：补充细节模式（临时恢复输入框）
  const [planDismissed, setPlanDismissed] = useState(false) // 计划决策：放弃（恢复常规输入框）
  const [editingMsgId, setEditingMsgId] = useState(null) // 消息编辑重发：被撤回的最后一条用户消息 id
  const [editBusy, setEditBusy] = useState(false) // 编辑流转中（停止等待收尾 / 撤回请求进行时）
  const [project, setProject] = useState(null) // 项目会话：{id, name, rootPath}
  const [menuOpen, setMenuOpen] = useState(false) // 模型下拉
  const [skills, setSkills] = useState([]) // 已启用技能（输入 / 触发提及选择）
  const [mentionedSkill, setMentionedSkill] = useState(null) // 已选中的技能（chip 展示）
  const [mention, setMention] = useState(null) // 技能提及下拉状态 {q, idx}
  const [images, setImages] = useState([]) // 待发送图片 [{dataUrl, name}]（Ctrl+V 粘贴 / 拖拽 / 📷 选择）
  const [fileMention, setFileMention] = useState(null) // 文件提及下拉状态 {q, idx, start, caret}（仅项目会话）
  const [fileOptions, setFileOptions] = useState([]) // 文件提及候选 [{name, path}]（≤10 条，后端截断）
  const [armedFileStart, setArmedFileStart] = useState(null) // Backspace 第一次：选中末尾文件 chip
  const [ctx, setCtx] = useState(null) // 上下文容量状态 {contextLength, usedTokens, percent, state, summarized}
  const [activeTurnId, setActiveTurnId] = useState(null) // 视口当前阅读到的轮次（左侧导航高亮）
  const seqRef = useRef(0)
  const bottomRef = useRef(null)
  const abortRef = useRef(null)
  const liveTurnRef = useRef(null)
  const attachRef = useRef(null) // live 接入状态 {id, conversationId}（体验升级七）
  const liveCtrlRef = useRef(null) // live 接入的 AbortController
  const fileInputRef = useRef(null)
  const textareaRef = useRef(null)
  const promptRef = useRef('')
  const skillPopRef = useRef(null) // 技能下拉容器（高亮项滚动跟随）
  const filePopRef = useRef(null) // 文件下拉容器（高亮项滚动跟随）
  const editSnapRef = useRef(null) // 进入编辑前的输入区快照 {draft, planRefine, planDismissed}（退出编辑恢复）

  // 切会话：重置计划决策与消息编辑状态（决策条按当前会话最后一个计划轮重新推导）
  useEffect(() => {
    setPlanRefine(false)
    setPlanDismissed(false)
    setPlanDrawer(null)
    setEditingMsgId(null)
    setEditBusy(false)
    editSnapRef.current = null
  }, [conversationId])

  // 模型列表
  useEffect(() => {
    fetchModels().then(list => {
      setModels(list)
      if (!list.some(m => m.id === modelId)) {
        setModelId(list[0]?.id || '')
      }
    }).catch(() => setModels([]))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // P2-F3 首跑检测：Mock 模式 + 会话数为 0 → 显示欢迎卡（引导配置模型或一键演示）
  const [bootInfo, setBootInfo] = useState(null) // {mock, convCount}
  const [welcomeClosed, setWelcomeClosed] = useState(() => localStorage.getItem('harness.welcome.dismissed') === '1')
  const [samplesClosed, setSamplesClosed] = useState(() => localStorage.getItem('harness.samples.dismissed') === '1')
  useEffect(() => {
    let alive = true
    Promise.all([fetchConfig().catch(() => null), fetchConversations().catch(() => [])]).then(([cfg, convs]) => {
      if (!alive) return
      setBootInfo({ mock: !!cfg?.mockMode, convCount: Array.isArray(convs) ? convs.length : 0 })
    })
    return () => { alive = false }
  }, [conversationId])

  useEffect(() => {
    if (modelId) localStorage.setItem('harness.modelId', modelId)
  }, [modelId])
  useEffect(() => {
    localStorage.setItem('harness.deepThink', deepThink ? '1' : '0')
  }, [deepThink])
  useEffect(() => {
    localStorage.setItem('harness.approval', approval)
  }, [approval])

  // 已启用技能列表（/ 提及选择用）；「存为技能」后通过 skills-changed 事件刷新
  useEffect(() => {
    const load = () => fetchSkills()
      .then(list => setSkills(list.filter(s => s.enabled)))
      .catch(() => setSkills([]))
    load()
    window.addEventListener('skills-changed', load)
    return () => window.removeEventListener('skills-changed', load)
  }, [])

  const currentModel = models.find(m => m.id === modelId) || models[0] || null
  const canDeepThink = !!currentModel?.supportsThinking

  // 上下文容量：切换会话 / 模型后刷新（发送结束后与每轮 usage 事件也会更新，见 send / onEvent）
  useEffect(() => {
    if (!conversationId) {
      setCtx(null)
      return
    }
    fetchConversationContext(conversationId, modelId).then(setCtx).catch(() => setCtx(null))
  }, [conversationId, modelId])

  // 文件提及候选：@token 变化后防抖拉取（仅项目会话；竞态用 AbortController 处理，失败清空）
  useEffect(() => {
    if (!fileMention || !project) return
    const ctrl = new AbortController()
    const t = setTimeout(() => {
      searchProjectFiles(project.id, fileMention.q, ctrl.signal)
        .then(data => setFileOptions(data?.files || []))
        .catch(e => { if (e?.name !== 'AbortError') setFileOptions([]) })
    }, 160)
    return () => { clearTimeout(t); ctrl.abort() }
  }, [fileMention?.q, project?.id])

  // ↑↓ 移动高亮时，下拉内让 active 项滚进可视区（block:nearest 只滚最近的可滚容器，不带动页面）
  useEffect(() => {
    filePopRef.current?.querySelector('.skill-m-opt.active')?.scrollIntoView({ block: 'nearest' })
  }, [fileMention?.idx, fileOptions])
  useEffect(() => {
    skillPopRef.current?.querySelector('.skill-m-opt.active')?.scrollIntoView({ block: 'nearest' })
  }, [mention?.idx])

  // 左侧轮次导航：跟踪视口当前阅读到的轮次（rAF 节流线性扫描，取最后一个顶部越过阈值的锚点）
  const turnsRef = useRef(turns)
  turnsRef.current = turns
  useEffect(() => {
    if (turns.length === 0) {
      setActiveTurnId(null)
      return
    }
    const ws = document.querySelector('.workspace')
    if (!ws) return
    let raf = 0
    const update = () => {
      raf = 0
      let cur = null
      for (const t of turnsRef.current) {
        const el = document.getElementById(`turn-${t.id}`)
        if (!el) continue
        if (el.getBoundingClientRect().top <= 150) cur = t.id
        else break
      }
      setActiveTurnId(cur || turnsRef.current[0]?.id || null)
    }
    const onScroll = () => { if (!raf) raf = requestAnimationFrame(update) }
    update()
    ws.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      ws.removeEventListener('scroll', onScroll)
      if (raf) cancelAnimationFrame(raf)
    }
  }, [conversationId, turns.length])

  // 加载指定会话的历史消息（F12：切换会话不丢失进度）+ 项目元信息
  useEffect(() => {
    setError(null)
    setMention(null)
    setMentionedSkill(null)
    setFileMention(null)
    setFileOptions([])
    setArmedFileStart(null)
    // 切换会话：终止上一会话的 live 接入并复位运行态（本页 POST 流不受影响，继续后台消费）
    if (liveCtrlRef.current) { liveCtrlRef.current.abort(); liveCtrlRef.current = null }
    // POST 流写入的轮次会随会话切换从 turns 中卸载，此后它的 setTurns 更新匹配不到任何轮次，
    // 期间产出的内容全部丢失（表现为切回来要等很久或刷新才显示）。标记为已脱离，
    // 回到该会话时改由 /live 重放接管渲染。
    if (liveTurnRef.current && liveTurnRef.current.conversationId !== conversationId) {
      liveTurnRef.current.detached = true
    }
    attachRef.current = null
    if (!abortRef.current) setRunning(false)
    if (!conversationId) {
      setTurns([])
      setProject(null)
      return
    }
    setLoading(true)
    fetchConversationMeta(conversationId)
      .then(meta => {
        if (meta && meta.projectId) {
          setProject({ id: meta.projectId, name: meta.projectName || '项目', rootPath: meta.rootPath || '' })
        } else {
          setProject(null)
        }
      })
      .catch(() => setProject(null))
    fetchConversationMessages(conversationId)
      .then(msgs => {
        const base = buildTurns(msgs)
        setTurns(prev => {
          // 首条消息场景：navigate 触发本 effect 时，live 轮次可能正在流式，需保留
          const ref = liveTurnRef.current
          const live = ref && ref.conversationId === conversationId
            ? prev.find(t => t.id === ref.id)
            : null
          if (live) {
            const next = [...base]
            const last = next[next.length - 1]
            if (last && last.prompt === live.prompt && !last.conclusion) next.pop()
            next.push(live)
            return next
          }
          return base
        })
        // 实时续看：任务后台运行中（刷新/换标签页回来）则接入 GET /live
        maybeAttachLive(conversationId, base)
        // 双重 rAF 确保 DOM 渲染完成后再滚到底部（刷新时不在顶部）
        requestAnimationFrame(() => requestAnimationFrame(() => {
          bottomRef.current?.scrollIntoView()
        }))
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [conversationId])

  /** 仅当用户贴近视口底部时才自动滚动，避免阅读历史时被拽走。 */
  function autoScroll() {
    const el = bottomRef.current
    if (!el) return
    const ws = el.closest('.workspace')
    if (!ws) return
    const nearBottom = ws.scrollHeight - ws.scrollTop - ws.clientHeight < 140
    if (nearBottom) el.scrollIntoView({ behavior: 'smooth' })
  }

  /**
   * 事件归并（发送流与 live 续看流共用）：把 SSE 事件应用到指定轮次。
   * usage 事件只更新容量徽标不进事件流（子 Agent 是独立小上下文，不计入会话徽标）。
   */
  function applyEvent(ev, id) {
    if (ev.type === 'context_usage') {
      if (ev.agent) return
      setCtx(c => c ? {
        ...c,
        usedTokens: ev.promptTokens || c.usedTokens,
        percent: c.contextLength
          ? Math.min(100, Math.round((ev.promptTokens || c.usedTokens) * 100 / c.contextLength))
          : c.percent
      } : c)
      return
    }
    setTurns(prev => prev.map(t => {
      if (t.id !== id) return t
      const next = { ...t }
      switch (ev.type) {
        case 'text_delta': // 正文增量（流式打字机）；子 Agent 增量进各自泳道
          if (ev.agent) {
            next.laneText = { ...(t.laneText || {}), [ev.agent]: (t.laneText?.[ev.agent] || '') + (ev.delta || '') }
            return next
          }
          next.streamText = (t.streamText || '') + (ev.delta || '')
          return next
        case 'thinking_delta': // 思考增量（reasoning_content）；子 Agent 的思考进泳道
          if (ev.agent) {
            next.laneText = { ...(t.laneText || {}), [ev.agent]: (t.laneText?.[ev.agent] || '') + (ev.delta || '') }
            return next
          }
          next.thinking = (t.thinking || '') + (ev.delta || '')
          return next
        case 'step_commentary': // 工具轮说明定稿：流式正文移入思考区（子 Agent 清空泳道流式缓存）
          next.events = [...t.events, ev]
          if (ev.agent) {
            next.laneText = { ...(t.laneText || {}) }
            delete next.laneText[ev.agent]
            return next
          }
          next.streamText = ''
          return next
        case 'thinking': // 聚合思考定稿入事件流，并清空流式思考缓存（防重复显示）
          next.events = [...t.events, ev]
          if (ev.agent) {
            next.laneText = { ...(t.laneText || {}) }
            delete next.laneText[ev.agent]
            return next
          }
          next.thinking = ''
          return next
        case 'task_metrics':
          next.metrics = ev.metrics || ev
          next.runId = ev.runId || next.runId
          next.events = [...t.events, ev]
          return next
        case 'task_outcome':
          next.outcome = ev.outcome
          next.runId = ev.runId || next.runId
          next.events = [...t.events, ev]
          return next
        case 'done':
          next.conclusion = ev.conclusion
          next.traceId = ev.traceId
          next.runId = ev.runId || next.runId
          next.status = ev.status || next.status
          next.running = false
          next.streamText = ''
          next.runningTool = null
          return next
        case 'tool_start': // 工具开始执行（仅推流不落库）：显示「正在执行 X」滚动指示
          next.runningTool = { callId: ev.callId, name: ev.name, target: ev.target || '', agent: ev.agent || null }
          return next
        default: // plan / tool_call / reflect / file / plan_step / agent_spawn / agent_done / 未知事件
          next.events = [...t.events, ev]
          if (ev.type === 'tool_call' && t.runningTool && t.runningTool.callId === ev.callId) {
            next.runningTool = null // 对应工具执行完成，熄灭滚动指示
          }
          return next
      }
    }))
    autoScroll()
  }

  /**
   * 实时续看（体验升级七）：进入会话时若任务正在后台运行（刷新页面/换标签页后回来），
   * 接入 GET /live——重放缓冲事件到最后一个未结轮次并继续实时渲染；
   * 本页自己发出的 POST 流（liveTurnRef + abortRef 存活）不重复接入。
   */
  function maybeAttachLive(convId, baseTurns) {
    if (!convId) return
    const posted = liveTurnRef.current
    // 本页 POST 流仍挂在该会话的轮次上时不重复接入；轮次一旦被卸载（detached），
    // POST 流的更新已无处落地，必须由 /live 重放接管。
    if (posted && posted.conversationId === convId && abortRef.current && !posted.detached) return
    const ctrl = new AbortController()
    liveCtrlRef.current = ctrl
    let targetId = null
    let started = false
    // 这些事件类型出现说明任务确实在跑（重放的首个结构事件）
    const STARTER = new Set(['text_delta', 'thinking_delta', 'thinking', 'step_commentary', 'tool_start',
      'plan', 'plan_step', 'tool_call', 'reflect', 'file', 'skill', 'vision', 'image',
      'agent_spawn', 'agent_done', 'blackboard', 'approval_request', 'context_compact',
      'plan_mode', 'question_request', 'verification', 'guard'])
    streamConversationLive(convId, ev => {
      if (ev.type === 'hub_idle' || ev.type === 'hub_end') return
      if (!started && STARTER.has(ev.type)) {
        started = true
        // 落点 = 历史中最后一个「有 prompt、无结论」的轮次（即已落库、尚未回复的用户消息）
        const target = [...(baseTurns || [])].reverse().find(t => t.prompt && !t.conclusion)
        if (target) {
          targetId = target.id
          attachRef.current = { id: targetId, conversationId: convId }
          setTurns(prev => prev.map(t => t.id === targetId
            ? { ...t, running: true, streamText: '', thinking: '', laneText: t.laneText || {} }
            : t))
          setRunning(true) // 输入框禁用 + 停止按钮可用（停止走 POST /stop）
        }
      }
      if (started && targetId) {
        applyEvent(ev, targetId)
        if (ev.type === 'done') {
          attachRef.current = null
          setRunning(false)
          window.dispatchEvent(new Event('conversations-changed'))
          fetchConversationContext(convId).then(setCtx).catch(() => {})
        }
      }
    }, ctrl.signal).catch(() => { /* 正常结束/切换会话的中断 */ }).finally(() => {
      if (liveCtrlRef.current === ctrl) liveCtrlRef.current = null
      // 流结束但没收到 done（服务端关闭/异常）：轮次就地落定，避免永远转圈
      if (started && attachRef.current?.id === targetId) {
        attachRef.current = null
        setRunning(false)
        setTurns(prev => prev.map(t => t.id === targetId && t.running
          ? {
            ...t,
            running: false,
            streamText: '',
            conclusion: (t.streamText || '') + (t.streamText ? '\n\n*（连接已结束）*' : '（连接已结束）')
          }
          : t))
      }
    })
  }

  function stop() {
    // 显式停止：后端置取消标志（任务尽快收尾并落库「已停止」），再断开本地两条流
    const convId = attachRef.current?.conversationId || liveTurnRef.current?.conversationId || conversationId
    if (convId) stopConversation(convId)
    abortRef.current?.abort()
    if (liveCtrlRef.current) { liveCtrlRef.current.abort(); liveCtrlRef.current = null }
    if (attachRef.current?.id) {
      const tid = attachRef.current.id
      setTurns(prev => prev.map(t => t.id === tid && t.running
        ? {
          ...t,
          running: false,
          streamText: '',
          conclusion: (t.streamText || '') + (t.streamText ? '\n\n*（已停止）*' : '*（已停止）*')
        }
        : t))
      attachRef.current = null
    }
    setRunning(false)
  }

  /** 等待服务端收尾（编辑重发前置）：运行中的任务必须完全落库后才能截断，
   *  否则「已停止」结论会在删除之后落库、以乱序复活。 */
  async function waitUntilStopped(convId, timeoutMs = 20000) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      try {
        const meta = await fetchConversationMeta(convId)
        if (!meta || !meta.running) return
      } catch {
        return // 元信息读取失败不无限等，交由后续接口自己的 409 兜底
      }
      await new Promise(r => setTimeout(r, 400))
    }
    throw new Error('等待当前任务停止超时，请稍后重试')
  }

  /** 进入编辑态（仅最后一轮）：解析落库消息 id → 运行中先停止并等收尾 → 内容回填输入框（原输入快照可恢复）。 */
  async function startEdit() {
    if (editingMsgId || editBusy || !conversationId) return
    setEditBusy(true)
    setError(null)
    try {
      // 运行中轮次在本地是 live-N（无落库 id），消息 id 必须现查服务端
      const [msgs, meta] = await Promise.all([
        fetchConversationMessages(conversationId),
        fetchConversationMeta(conversationId)
      ])
      const lastUser = [...msgs].reverse().find(m => m.role === 'user')
      if (!lastUser) return
      if (meta.running || running) {
        stop()
        await waitUntilStopped(conversationId)
      }
      editSnapRef.current = { draft: prompt, planRefine, planDismissed }
      setEditingMsgId(lastUser.id)
      promptRef.current = lastUser.content || ''
      setPrompt(promptRef.current)
      setPlanRefine(false)
      setPlanDismissed(true) // 撤下计划决策条（取消编辑时随快照恢复）
      requestAnimationFrame(() => {
        const el = textareaRef.current
        if (el) {
          el.focus()
          el.setSelectionRange(el.value.length, el.value.length)
        }
      })
    } catch (e) {
      setError('无法进入编辑：' + e.message)
    } finally {
      setEditBusy(false)
    }
  }

  /** 退出编辑：恢复进入前的输入框草稿与计划决策状态，原消息原封不动。 */
  function cancelEdit() {
    const snap = editSnapRef.current
    editSnapRef.current = null
    setEditingMsgId(null)
    if (snap) {
      promptRef.current = snap.draft
      setPrompt(promptRef.current)
      setPlanRefine(snap.planRefine)
      setPlanDismissed(snap.planDismissed)
    }
  }

  /** 添加图片附件：最多 4 张；大图（>1.5MB）先压到最长边 2000px 的 JPEG，避免请求体过大。 */
  async function addImages(fileList) {
    const files = [...fileList].filter(f => f && f.type?.startsWith('image/'))
    if (!files.length) return
    const next = [...images]
    for (const f of files) {
      if (next.length >= 4) {
        setError('单条消息最多附带 4 张图片')
        break
      }
      try {
        next.push({ dataUrl: await readImageDataUrl(f), name: f.name || '图片' })
      } catch (e) {
        setError('图片读取失败：' + e.message)
      }
    }
    setImages(next)
  }

  function onPaste(e) {
    const items = [...(e.clipboardData?.items || [])].filter(i => i.type?.startsWith('image/'))
    if (items.length) {
      e.preventDefault()
      addImages(items.map(i => i.getAsFile()).filter(Boolean))
    }
  }

  function onDrop(e) {
    e.preventDefault()
    if (e.dataTransfer?.files?.length) addImages(e.dataTransfer.files)
  }

  /** 输入框发送：快照输入区状态并清空，交给通用发送；编辑态下先撤回原轮再重发。 */
  async function send() {
    const text = prompt.trim()
    if ((!text && !mentionedSkill && !images.length) || running || editBusy) return
    const usedSkill = mentionedSkill
    const sentImages = images
    const usedFiles = fileRefsIn(text)
    promptRef.current = ''
    setPrompt('')
    setMentionedSkill(null)
    setMention(null)
    setImages([])
    setFileMention(null)
    setPlanRefine(false) // 补充细节消息已发出，退出补充模式（新计划到达后决策条重新接管）
    if (editingMsgId) {
      // 编辑重发第一步：撤回原轮（该用户消息及其后全部消息，含 Agent 回复）
      const msgId = editingMsgId
      setEditBusy(true)
      try {
        await truncateMessagesFrom(conversationId, msgId)
        const msgs = await fetchConversationMessages(conversationId)
        liveTurnRef.current = null // 被撤回的 live 轮已随删除消失，防止 refetch 合并把它复活
        setTurns(buildTurns(msgs))
        window.dispatchEvent(new Event('conversations-changed'))
      } catch (e) {
        setError('撤回失败：' + e.message)
        setEditBusy(false)
        promptRef.current = text
        setPrompt(text) // 还原输入内容（含原位置的文件引用）并保持编辑态，可重试
        return
      }
      editSnapRef.current = null
      setEditingMsgId(null)
      setEditBusy(false)
    }
    sendPayload({ text, usedSkill, sentImages, usedFiles }, approval)
  }

  /** 按计划实现 / 执行此计划（计划决策条主按钮）：权限默认切换为完全访问；气泡只显示「执行此计划」，
   *  计划全文经 planText 程序内下发给后端注入执行提示词（不回显到对话）。 */
  function implementPlan(planText) {
    if (running || !planText) return
    setApproval('full')
    setPlanRefine(false)
    setPlanDrawer(null)
    sendPayload({ text: '执行此计划', planText, usedSkill: null, sentImages: [], usedFiles: [] }, 'full')
  }

  /** 通用发送（输入框与计划决策共用）：payload 携带文本/技能/图片/文件引用/计划文档，approvalToUse 为本次权限。 */
  async function sendPayload({ text, usedSkill = null, sentImages = [], usedFiles = [], planText = null }, approvalToUse) {
    if (running) return
    setRunning(true)
    setError(null)

    // 首条消息：懒创建会话并切到 /c/:id 路由
    let convId = conversationId
    if (!convId) {
      try {
        const conv = await createConversation()
        convId = conv.id
        navigate(`/c/${convId}`, { replace: true })
        window.dispatchEvent(new Event('conversations-changed'))
      } catch (e) {
        setError('创建会话失败：' + e.message)
        setRunning(false)
        return
      }
    }

    const ctrl = new AbortController()
    abortRef.current = ctrl
    const id = `live-${++seqRef.current}`
    liveTurnRef.current = { id, conversationId: convId }
    // 文件引用已保留在正文原位置，实时气泡与历史回放使用同一文本。
    const turnPrompt = text
      || (usedSkill ? `/${usedSkill.name}` : '')
      || (sentImages.length ? `📷 图片 ×${sentImages.length}` : '')
    setTurns(prev => [...prev, {
      id,
      prompt: turnPrompt,
      images: sentImages.map(i => i.dataUrl),
      events: [],
      conclusion: null,
      streamText: '',
      thinking: '',
      laneText: {},
      running: true
    }])
    requestAnimationFrame(() => bottomRef.current?.scrollIntoView({ behavior: 'smooth' }))

    try {
      // 请求兜底 prompt：仅选技能 → 技能指令（后端要求 prompt 非空）
      let reqPrompt = text
      if (!reqPrompt && usedSkill) reqPrompt = `执行技能 ${usedSkill.name}`
      await streamConversationMessage(convId, {
        prompt: reqPrompt,
        modelId: currentModel?.id,
        deepThink: canDeepThink && deepThink,
        approval: approvalToUse,
        skillId: usedSkill?.id,
        images: sentImages.map(i => i.dataUrl),
        filePaths: usedFiles,
        planText: planText || undefined
      }, ev => applyEvent(ev, id), ctrl.signal)
      window.dispatchEvent(new Event('conversations-changed'))
    } catch (e) {
      setTurns(prev => prev.map(t => {
        if (t.id !== id) return t
        const aborted = e.name === 'AbortError'
        return {
          ...t,
          running: false,
          streamText: '',
          conclusion: aborted
            ? ((t.streamText || '') + (t.streamText ? '\n\n*（已停止）*' : '（已停止）'))
            : '连接异常：' + e.message
        }
      }))
    } finally {
      setTurns(prev => prev.map(t => t.id === id ? { ...t, running: false } : t))
      setRunning(false)
      abortRef.current = null
      liveTurnRef.current = null
      // 轮次结束后刷新容量（任务内压缩/摘要可能已生效）
      fetchConversationContext(convId, modelId).then(setCtx).catch(() => {})
    }
  }

  /** 输入变化：整条输入是「/前缀」形式时打开技能下拉；光标前是「@token」且为项目会话时打开文件下拉。 */
  function onPromptChange(v, caret) {
    promptRef.current = v
    setPrompt(v)
    setArmedFileStart(null)
    if (!mentionedSkill && v.startsWith('/') && !/\s/.test(v)) {
      setMention(m => ({ q: v.slice(1), idx: m ? m.idx : 0 }))
    } else {
      setMention(null)
    }
    // @ 文件提及：@ 位于行首或空白之后、@ 到光标无空白；@token 剪除位置随下拉状态携带
    const c = caret == null ? v.length : caret
    const fm = project ? v.slice(0, c).match(/(^|\s)@([^\s@]*)$/) : null
    if (fm && !fm[2].startsWith('{')) {
      const start = c - fm[2].length - 1
      setFileMention(m => ({ q: fm[2], idx: m && m.q === fm[2] ? m.idx : 0, start, caret: c }))
    } else {
      setFileMention(null)
    }
  }

  function mentionList() {
    if (!mention) return []
    const q = mention.q.trim().toLowerCase()
    return skills.filter(s =>
      !q
      || s.name.toLowerCase().includes(q)
      || (s.triggers || []).some(t => String(t).toLowerCase().includes(q)))
  }

  function pickSkill(s) {
    setMentionedSkill(s)
    promptRef.current = ''
    setPrompt('')
    setMention(null)
  }

  /** 选中文件：在原位置写入明确的相对路径引用；正文顺序即模型看到的顺序。 */
  function pickFile(f) {
    const fm = fileMention
    if (!fm) return
    const token = `@{${f.path}}`
    const source = promptRef.current
    const caret = Math.max(fm.start + 1, Math.min(textareaRef.current?.selectionStart ?? fm.caret, source.length))
    const before = source.slice(0, fm.start)
    const tailLength = source.slice(caret).match(/^[^\s@]*/)?.[0].length || 0
    const after = source.slice(caret + tailLength)
    const suffix = after && !/^\s/.test(after) ? ' ' : ''
    const next = before + token + suffix + after
    const pos = before.length + token.length + suffix.length
    promptRef.current = next
    setPrompt(next)
    setArmedFileStart(null)
    setFileMention(null)
    requestAnimationFrame(() => {
      const el = textareaRef.current
      if (el) {
        el.focus()
        el.setSelectionRange(pos, pos)
      }
    })
  }

  /** 将真实 textarea 光标同步到文件 token 边界，防止点击、方向键或输入法进入路径内部。 */
  function syncFileCaret(el, direction = 'nearest') {
    if (!el) return 0
    const start = el.selectionStart ?? 0
    const end = el.selectionEnd ?? start
    if (start !== end) {
      return start
    }
    const next = normalizeFileCaret(promptRef.current, start, direction)
    if (next !== start) el.setSelectionRange(next, next)
    return next
  }

  /** 点击 chip 的 × 时立即移除整个引用，并整理两侧重复空格。 */
  function removeFileReference(start, end) {
    const source = promptRef.current
    if (start < 0 || end <= start || end > source.length || !/^@\{[^{}\r\n]+\}$/.test(source.slice(start, end))) return
    let before = source.slice(0, start)
    let after = source.slice(end)
    if (!before && /^\s/.test(after)) after = after.slice(1)
    else if (!after && /\s$/.test(before)) before = before.slice(0, -1)
    else if (/\s$/.test(before) && /^\s/.test(after)) after = after.slice(1)
    const next = before + after
    const pos = before.length
    promptRef.current = next
    setPrompt(next)
    setArmedFileStart(null)
    setFileMention(null)
    requestAnimationFrame(() => {
      const el = textareaRef.current
      el?.focus()
      el?.setSelectionRange(pos, pos)
    })
  }

  /** 快速指引点击：填入输入框并聚焦到末尾。 */
  function fillPrompt(text) {
    promptRef.current = text
    setPrompt(text)
    setMention(null)
    setFileMention(null)
    requestAnimationFrame(() => {
      const el = textareaRef.current
      if (el) {
        el.focus()
        el.setSelectionRange(text.length, text.length)
      }
    })
  }

  function onKeyDown(e) {
    // 文件 chip 作为整体删除：第一次 Backspace 仅进入 hover/选中态，第二次才删除。
    if ((e.key === 'Backspace' || e.key === 'Delete') && !e.shiftKey && !e.altKey && !e.ctrlKey && !e.metaKey) {
      const el = textareaRef.current
      const originalCaret = el?.selectionStart ?? 0
      const direction = e.key === 'Backspace' ? 'left' : 'right'
      const caret = normalizeFileCaret(prompt, originalCaret, direction)
      const end = el?.selectionEnd ?? caret
      if (caret !== originalCaret) {
        e.preventDefault()
        el?.setSelectionRange(caret, caret)
        setArmedFileStart(null)
        return
      }
      if (caret === end) {
        const before = prompt.slice(0, caret)
        const after = prompt.slice(caret)
        const match = e.key === 'Backspace'
          ? before.match(/@\{([^{}\r\n]+)\}$/)
          : after.match(/^@\{([^{}\r\n]+)\}/)
        const start = match ? (e.key === 'Backspace' ? caret - match[0].length : caret) : -1
        if (match) {
          e.preventDefault()
          if (armedFileStart === start) {
            removeFileReference(start, start + match[0].length)
          } else {
            setArmedFileStart(start)
          }
          return
        }
      }
      setArmedFileStart(null)
    }
    if (mention) {
      const list = mentionList()
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        if (!list.length) return
        const d = e.key === 'ArrowDown' ? 1 : -1
        setMention(m => ({ ...m, idx: (m.idx + d + list.length) % list.length }))
        return
      }
      if ((e.key === 'Enter' || e.key === 'Tab') && list.length) {
        e.preventDefault()
        pickSkill(list[Math.min(mention.idx, list.length - 1)])
        return
      }
      if (e.key === 'Escape') {
        e.preventDefault()
        setMention(null)
        return
      }
    }
    if (fileMention) {
      const list = fileOptions
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        if (!list.length) return
        const d = e.key === 'ArrowDown' ? 1 : -1
        setFileMention(m => ({ ...m, idx: (m.idx + d + list.length) % list.length }))
        return
      }
      if ((e.key === 'Enter' || e.key === 'Tab') && list.length) {
        e.preventDefault()
        pickFile(list[Math.min(fileMention.idx, list.length - 1)])
        return
      }
      if (e.key === 'Escape') {
        e.preventDefault()
        setFileMention(null)
        return
      }
    }
    // 左右方向键始终跨过整个文件 chip，永远不进入隐藏的 @{path} 字符串。
    if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight')
        && !e.shiftKey && !e.altKey && !e.ctrlKey && !e.metaKey) {
      const el = textareaRef.current
      const start = el.selectionStart ?? 0
      const end = el.selectionEnd ?? start
      if (start === end) {
        const ref = e.key === 'ArrowLeft'
          ? fileRefRanges(prompt).find(item => start > item.start && start <= item.end)
          : fileRefRanges(prompt).find(item => start >= item.start && start < item.end)
        if (ref) {
          e.preventDefault()
          const next = e.key === 'ArrowLeft' ? ref.start : ref.end
          el.setSelectionRange(next, next)
          setArmedFileStart(null)
          return
        }
      }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      send()
    }
  }

  const showEmpty = !conversationId && turns.length === 0 && !loading

  // 计划决策条：最后一个轮次是已完成的计划轮（plan_mode 事件 + 计划结论）且未进入补充/放弃状态时，
  // 以「执行此计划 / 补充细节信息」双选项替代输入框（仿 zcode/codex 计划确认）
  const lastTurn = turns[turns.length - 1]
  const planPending = !!lastTurn && !running
    && !lastTurn.running && !!lastTurn.conclusion
    && lastTurn.events.some(e => e.type === 'plan_mode')
    && !planRefine && !planDismissed

  // 消息编辑：仅最后一个带正文的轮次可编辑（运行中的 live 轮也算——点击后先停止再进入编辑）
  const lastPromptTurn = [...turns].reverse().find(t => t.prompt) || null

  return (
    <div className={`chat-flex${drawer || planDrawer ? ' drawer-open' : ''}`}>
      <div className="chat-col">
        <div className="chat-page">
      {project && (
        <div className="proj-chip" title={project.rootPath}>
          <span className="proj-chip-icon"><IconFolder size={14} /></span>
          <span className="proj-chip-name">{project.name}</span>
          <span className="proj-chip-root">{project.rootPath}</span>
        </div>
      )}
      {showEmpty && (
        <div className="hint">
          闲聊与问答直接回答；需要操作文件的任务会自动规划执行。全程实时流式，可随时停止。
        </div>
      )}

      {/* P2-F3 首跑欢迎卡：Mock 模式且从无会话时显示，两条路——配置模型 / 先看演示 */}
      {showEmpty && bootInfo?.mock && bootInfo?.convCount === 0 && !welcomeClosed && (
        <div className="welcome-card">
          <div className="welcome-icon">✳</div>
          <div className="welcome-main">
            <h2>欢迎使用 Pig Harness</h2>
            <p>
              本地优先的自进化编码 Agent：把任务交给它，它会规划、执行并把成功经验沉淀为可复用技能。
              当前未配置 API Key，已进入 <b>Mock 演示模式</b>——全链路可跑，先看效果再配模型。
            </p>
            <div className="welcome-actions">
              <button className="btn primary" onClick={() => window.dispatchEvent(new Event('open-settings'))}>⚙️ 配置模型</button>
              <button className="btn" onClick={() => fillPrompt(SAMPLE_CARDS[0].prompt)}>▶ 先看演示</button>
            </div>
          </div>
          <button className="chip-x welcome-x" onClick={() => { setWelcomeClosed(true); localStorage.setItem('harness.welcome.dismissed', '1') }} title="不再显示">×</button>
        </div>
      )}
      {/* P2-F3 示例任务卡 ×3（空态常显，可关闭并记忆） */}
      {showEmpty && !project && !samplesClosed && (
        <div className="sample-cards">
          <div className="sample-cards-head">
            <span>示例任务</span>
            <button className="chip-x" onClick={() => { setSamplesClosed(true); localStorage.setItem('harness.samples.dismissed', '1') }} title="收起（侧栏新对话不会恢复）">收起 ×</button>
          </div>
          <div className="sample-cards-grid">
            {SAMPLE_CARDS.map(c => (
              <div key={c.title} className="sample-card">
                <div className="sample-card-icon">{c.icon}</div>
                <div className="sample-card-title">{c.title}</div>
                <div className="sample-card-desc">{c.desc}</div>
                <button className="btn sm sample-card-cta" onClick={() => {
                  if (c.action === 'bind-project') {
                    window.dispatchEvent(new Event('harness:bind-project'))
                  } else {
                    fillPrompt(c.prompt)
                  }
                }}>{c.cta}</button>
              </div>
            ))}
          </div>
        </div>
      )}

      {loading && <div className="hint">加载历史消息…</div>}
      {error && <div className="reflect-row">加载失败：{error}</div>}

      {showEmpty && !project && (
        <div className="empty">
          <div className="empty-icon">✳</div>
          <p>让 Harness 帮你做事（点击示例直接填入输入框）：</p>
          <ul>
            {COMMON_EXAMPLES.map(t => (
              <li key={t} onClick={() => fillPrompt(t)} title="点击填入输入框">{t}</li>
            ))}
          </ul>
        </div>
      )}
      {showEmpty && project && (
        <div className="empty">
          <div className="empty-icon"><IconFolder size={22} /></div>
          <p>项目对话已就绪（工作目录 {project.rootPath}，点击示例直接填入输入框）：</p>
          <ul>
            {PROJECT_EXAMPLES.map(t => (
              <li key={t} onClick={() => fillPrompt(t)} title="点击填入输入框">{t}</li>
            ))}
          </ul>
          <p className="empty-foot">输入 @ 可引用项目文件；改动会实时汇总在右侧 Git 面板，可直接提交 / 推送。</p>
        </div>
      )}

      {turns.map(t => (
        <div key={t.id} id={`turn-${t.id}`} className="turn-anchor">
          <TaskCard
            task={t}
            conversationId={conversationId}
            projectId={project?.id}
            onOpenFile={(path, size, opts) => setDrawer({ path, size, noConv: !!opts?.noConv })}
            onOpenDiff={change => setDrawer({ path: change.path, size: null, diff: change })}
            onOpenPlan={text => setPlanDrawer({ text })}
            onApprove={(callId, approve) => resolveApproval(conversationId, callId, approve)}
            onAnswer={(callId, answer) => resolveQuestion(conversationId, callId, answer)}
            onEditPrompt={t === lastPromptTurn ? startEdit : undefined}
            editActive={t === lastPromptTurn && editingMsgId != null}
            editBusy={editBusy}
          />
        </div>
      ))}
      <div ref={bottomRef} />
        </div>

      {turns.length > 1 && <TurnNav turns={turns} activeId={activeTurnId} />}

      {planPending ? (
        <div className="plan-decision">
          <div className="plan-decision-head">📋 计划已生成，请选择下一步</div>
          <div className="plan-decision-actions">
            <button className="plan-dec primary" onClick={() => implementPlan(lastTurn.conclusion)}
                    title="切换为完全访问权限，按计划实施">
              🚀 执行此计划
            </button>
            <button className="plan-dec" onClick={() => setPlanRefine(true)}
                    title="仍处于计划模式，补充要求后重新生成计划">
              ✏️ 补充细节信息
            </button>
          </div>
          <button className="plan-dec-dismiss" onClick={() => setPlanDismissed(true)}>先不执行，手动输入</button>
        </div>
      ) : (
      <div className="composer">
        <div className="composer-card"
             onDrop={onDrop}
             onDragOver={e => e.preventDefault()}>
          {planRefine && (
            <div className="composer-refine-hint">
              <span>✏️ 补充细节信息以完善计划（仍处于计划模式，不会修改文件）</span>
              <button className="chip-x" onClick={() => setPlanRefine(false)} title="返回计划选项">×</button>
            </div>
          )}
          {editingMsgId != null && (
            <div className="composer-refine-hint edit-hint">
              <span>✏️ 正在编辑最后一条消息——发送后将撤回该轮对话（含回复）并重新提交；原消息的图片附件不会保留</span>
              <button className="chip-x" onClick={cancelEdit} title="退出编辑，恢复原消息">×</button>
            </div>
          )}
          {images.length > 0 && (
            <div className="composer-images">
              {images.map((img, i) => (
                <div className="composer-img-chip" key={i} title={img.name}>
                  <img src={img.dataUrl} alt={img.name} />
                  <button className="chip-x" onClick={() => setImages(images.filter((_, j) => j !== i))}
                          title="移除图片">×</button>
                </div>
              ))}
            </div>
          )}
          {mentionedSkill && (
            <div className="composer-skill-chip" title={mentionedSkill.description}>
              <span className="chip-icon"><IconLayers size={12} /></span>
              <span className="chip-name">/{mentionedSkill.name}</span>
              <button className="chip-x" onClick={() => setMentionedSkill(null)} title="移除技能">×</button>
            </div>
          )}
          {mention && mentionList().length > 0 && (
            <div className="skill-mention-pop" ref={skillPopRef}>
              <div className="skill-m-head">选择技能（仅已启用，↑↓ 选择，Enter 确认）</div>
              {mentionList().map((s, i) => (
                <button
                  key={s.id}
                  className={`skill-m-opt ${i === mention.idx ? 'active' : ''}`}
                  onMouseEnter={() => setMention(m => ({ ...m, idx: i }))}
                  onMouseDown={e => { e.preventDefault(); pickSkill(s) }}
                >
                  <span className="skill-m-icon"><IconLayers size={13} /></span>
                  <span className="skill-m-name">/{s.name}</span>
                  <span className="skill-m-desc">{s.description || s.whenToUse}</span>
                </button>
              ))}
            </div>
          )}
          {mention && mentionList().length === 0 && (
            <div className="skill-mention-pop">
              <div className="skill-m-empty">没有匹配「{mention.q}」的技能（仅显示已启用技能）</div>
            </div>
          )}
          {fileMention && (
            <div className="skill-mention-pop" ref={filePopRef}>
              <div className="skill-m-head">插入到光标处，文件内容按正文顺序提供给模型（↑↓ / Enter）</div>
              {fileOptions.map((f, i) => (
                <button
                  key={f.path}
                  className={`skill-m-opt ${i === fileMention.idx ? 'active' : ''}`}
                  onMouseEnter={() => setFileMention(m => ({ ...m, idx: i }))}
                  onMouseDown={e => { e.preventDefault(); pickFile(f) }}
                  title={f.path}
                >
                  <span className={`skill-m-icon f-ic-${fileIconKind(f.name)}`}><IconFile size={13} /></span>
                  <span className="skill-m-name">{f.name}</span>
                  <span className="skill-m-desc">{f.path}</span>
                </button>
              ))}
              {fileOptions.length === 0 && (
                <div className="skill-m-empty">没有匹配「{fileMention.q}」的文件</div>
              )}
            </div>
          )}
          <div className="composer-editor">
            <PromptEditor
              ref={textareaRef}
              value={prompt}
              projectRoot={project?.rootPath}
              armedStart={armedFileStart}
              placeholder={
                editingMsgId != null
                  ? '编辑消息内容…（Enter 重新发送）'
                  : planRefine
                    ? '补充细节信息以完善计划…（仍处于计划模式，Enter 发送）'
                    : mentionedSkill
                      ? `使用 /${mentionedSkill.name}：补充要求或直接回车执行…`
                      : project
                        ? `在项目「${project.name}」中描述任务…（可粘贴图片；输入 / 可选技能，@ 引用文件）`
                        : conversationId ? '继续输入…（可粘贴图片；输入 / 可选技能）' : '描述你的任务…（可粘贴图片；输入 / 可选技能）'
              }
              onChange={onPromptChange}
              onSelectionChange={() => syncFileCaret(textareaRef.current)}
              onKeyDown={onKeyDown}
              onPaste={onPaste}
              disabled={running}
              onOpenFile={path => setDrawer({ path })}
              onRemoveFile={removeFileReference}
            />
          </div>
          <input ref={fileInputRef} type="file" accept="image/*" multiple
                 style={{ display: 'none' }}
                 onChange={e => { addImages(e.target.files); e.target.value = '' }} />
          <div className="composer-bar">
            <button className="attach-btn" onClick={() => fileInputRef.current?.click()}
                    disabled={running || images.length >= 4} title="添加图片（也可直接 Ctrl+V 粘贴或拖入）">📷</button>
            <ModelMenu
              models={models}
              current={currentModel}
              disabled={running}
              open={menuOpen}
              onToggle={() => setMenuOpen(o => !o)}
              onClose={() => setMenuOpen(false)}
              onSelect={id => { setModelId(id); setMenuOpen(false) }}
              onManage={() => { setMenuOpen(false); window.dispatchEvent(new Event('open-settings')) }}
            />
            <Select
              value={approval}
              onChange={setApproval}
              disabled={running}
              direction="up"
              title="文件操作权限：替我审批=自动执行（限工作根内）；请求审批=每次写/改/删先问你；完全访问=自动执行且允许沙箱外绝对路径；计划模式=只读调研并产出计划文档（不改文件，可向你提问澄清）"
              options={[
                { value: 'auto', label: '替我审批', icon: <IconShield size={14} />, hint: '自动执行文件操作（限工作根内）' },
                { value: 'ask', label: '请求审批', icon: <IconHelp size={14} />, hint: '每次写/改/删先问你' },
                { value: 'full', label: '完全访问', icon: <IconUnlock size={14} />, hint: '自动执行且允许沙箱外绝对路径' },
                { value: 'plan', label: '计划模式', icon: <IconBook size={14} />, hint: '只读调研 + 向你提问澄清 → 产出计划文档；「按计划实现」时自动切换为完全访问' }
              ]}
            />
            {canDeepThink && (
              <button
                className={`think-chip ${deepThink ? 'on' : ''}`}
                onClick={() => setDeepThink(!deepThink)}
                disabled={running}
                title={`开启后使用推理模型 ${currentModel?.thinkingModel}`}
              >
                深度思考
              </button>
            )}
            <ContextMeter
              ctx={ctx}
              conversationId={conversationId}
              disabled={running}
              onCompacted={() => {
                if (conversationId) fetchConversationContext(conversationId, modelId).then(setCtx).catch(() => {})
              }}
            />
            <div className="composer-spacer" />
            {running && (
              <button className="stop-btn" onClick={stop} title="中断当前执行">■ 停止</button>
            )}
            <button className="send-btn" onClick={send} disabled={running || editBusy || (!prompt.trim() && !mentionedSkill && !images.length)}>
              {running ? '执行中…' : editingMsgId != null ? '重新发送' : '发送'}
            </button>
          </div>
        </div>
      </div>
      )}

      </div>

      {drawer && (
        <FileDrawer path={drawer.path} size={drawer.size} diff={drawer.diff} noConv={drawer.noConv}
                    projectId={project?.id} conversationId={conversationId}
                    onClose={() => setDrawer(null)} />
      )}
      {planDrawer && (
        <PlanDrawer text={planDrawer.text} onClose={() => setPlanDrawer(null)} />
      )}
      {project && <GitPanel projectId={project.id} projectName={project.name} />}
    </div>
  )
}

/** 左侧轮次导航（minimap）：fixed 垂直居中；点击定位；hover 时中心项最长、前后各两项阶梯递减 + 自定义浮层提示。 */
function TurnNav({ turns, activeId }) {
  const [hoverIdx, setHoverIdx] = useState(null)
  const [canUp, setCanUp] = useState(false)
  const [canDown, setCanDown] = useState(false)
  const [navLeft, setNavLeft] = useState(null)
  const [tip, setTip] = useState(null) // {idx, text, top, left}
  const scrollRef = useRef(null)

  function measure() {
    const el = scrollRef.current
    if (!el) return
    setCanUp(el.scrollTop > 4)
    setCanDown(el.scrollHeight - el.scrollTop - el.clientHeight > 4)
  }

  useEffect(() => {
    const calc = () => {
      const page = document.querySelector('.chat-page')
      if (page) setNavLeft(page.getBoundingClientRect().left - 44)
    }
    calc()
    window.addEventListener('resize', calc)
    return () => window.removeEventListener('resize', calc)
  }, [])

  useEffect(() => { measure() }, [turns.length])

  const BASE_W = 14
  const widthOf = i => {
    if (hoverIdx == null) return BASE_W
    const d = Math.abs(i - hoverIdx)
    if (d === 0) return 32
    if (d === 1) return 24
    if (d === 2) return 18
    return BASE_W
  }

  function onEnter(i, t, e) {
    setHoverIdx(i)
    const r = e.currentTarget.getBoundingClientRect()
    setTip({ idx: i + 1, text: t.prompt, top: r.top + r.height / 2, left: r.right + 8 })
  }
  function onLeave() {
    setHoverIdx(null)
    setTip(null)
  }

  return (
    <>
      <div className="turn-nav" style={navLeft != null ? { left: navLeft } : undefined}>
        <span className={`tn-arrow ${canUp ? '' : 'tn-none'}`} title="上方还有对话">▲</span>
        <div className="turn-nav-scroll" ref={scrollRef} onScroll={measure} onMouseLeave={onLeave}>
          {turns.map((t, i) => (
            <button
              key={t.id}
              className={`tn-item ${t.id === activeId ? 'active' : ''}${hoverIdx === i ? ' tn-hover' : ''}`}
              style={{ width: widthOf(i) }}
              onMouseEnter={e => onEnter(i, t, e)}
              onClick={() => document.getElementById(`turn-${t.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
            />
          ))}
        </div>
        <span className={`tn-arrow ${canDown ? '' : 'tn-none'}`} title="下方还有对话">▼</span>
      </div>
      {tip && (
        <div className="tn-tooltip" style={{ top: tip.top, left: tip.left }}>
          <span className="tn-tip-idx">{tip.idx}</span>
          <span className="tn-tip-text">{tip.text}</span>
        </div>
      )}
    </>
  )
}

/** 模型切换下拉：圆角面板 + 选中档案右侧勾勾 + 底部「管理模型」入口（输入区在视口底部，向上弹出）。 */
function ModelMenu({ models, current, disabled, open, onToggle, onClose, onSelect, onManage }) {
  return (
    <div className="model-menu">
      <button className="model-trigger" onClick={onToggle} disabled={disabled} title="选择对话模型">
        <span className="model-trigger-name">{current?.name || '选择模型'}</span>
        <span className={`chev ${open ? 'open' : ''}`}><IconChevronDown size={11} /></span>
      </button>
      {open && (
        <>
          <div className="menu-overlay" onClick={onClose} />
          <div className="model-pop">
            <div className="model-pop-head">模型</div>
            {models.map(m => {
              const sel = current?.id === m.id
              return (
                <button key={m.id} className="model-opt" onClick={() => onSelect(m.id)} title={m.model}>
                  <span className="model-opt-name">
                    {m.name}
                    {!m.hasKey && <span className="pill warn">Mock</span>}
                  </span>
                  <span className="model-opt-model">{m.model}</span>
                  {sel && <span className="opt-check"><IconCheck size={14} /></span>}
                </button>
              )
            })}
            <div className="model-pop-sep" />
            <button className="model-opt" onClick={onManage}>管理模型</button>
          </div>
        </>
      )}
    </div>
  )
}

/** 上下文容量徽标：已用/窗口三色状态；点击弹出明细与「立即压缩」（输入区在视口底部，向上弹出）。 */
function ContextMeter({ ctx, conversationId, disabled, onCompacted }) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [usage, setUsage] = useState(null)
  // P1 成本观测：弹层打开时懒加载本会话累计用量（hook 必须在早退之前声明）
  useEffect(() => {
    if (open && conversationId && !usage) {
      fetchConversationUsage(conversationId).then(setUsage).catch(() => setUsage(null))
    }
  }, [open, conversationId, usage])
  if (!ctx || !ctx.contextLength) return null
  const state = ctx.state || 'ok'

  function fmt(n) {
    if (n == null) return '—'
    if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1).replace(/\.0$/, '') + 'M'
    if (n >= 1024) return (n / 1024).toFixed(1).replace(/\.0$/, '') + 'K'
    return String(n)
  }

  function fmtCost(c) {
    if (c == null) return ''
    if (c >= 1) return ' $' + c.toFixed(2)
    if (c > 0) return ' $' + c.toFixed(4)
    return ''
  }

  async function doCompact() {
    setBusy(true)
    setMsg(null)
    try {
      const r = await compactConversation(conversationId)
      setMsg(r.ok ? `已压缩：约 ${fmt(r.before)} → ${fmt(r.after)} tokens` : (r.reason || '没有可压缩的历史'))
      if (r.ok && onCompacted) onCompacted()
    } catch (e) {
      setMsg('压缩失败：' + e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="ctx-meter">
      <button className={`ctx-trigger ${state}`} onClick={() => { setOpen(o => !o); setMsg(null) }}
              disabled={disabled} title="上下文用量（估算；达到阈值会自动压缩）">
        <span className={`ctx-dot ${state}`} />
        <span className="ctx-num">{fmt(ctx.usedTokens)} / {fmt(ctx.contextLength)}</span>
      </button>
      {open && (
        <>
          <div className="menu-overlay" onClick={() => setOpen(false)} />
          <div className="ctx-pop">
            <div className="ctx-pop-row"><span>上下文窗口</span><code>{fmt(ctx.contextLength)} tokens</code></div>
            <div className="ctx-pop-row">
              <span>当前估算</span>
              <code>{fmt(ctx.usedTokens)} tokens（{ctx.percent}%）</code>
            </div>
            <div className="ctx-pop-row"><span>历史压缩摘要</span><code>{ctx.summarized ? '已生成' : '未生成'}</code></div>
            {usage && usage.rounds > 0 && (
              <div className="ctx-pop-row" title={usage.priced ? '按模型计价表估算' : '未配置计价表，仅统计 token'}>
                <span>本会话累计</span>
                <code>{usage.rounds} 轮 · {fmt(usage.promptTokens)} in / {fmt(usage.completionTokens)} out{fmtCost(usage.cost)}</code>
              </div>
            )}
            <button className="btn sm primary" onClick={doCompact}
                    disabled={busy || !conversationId} style={{ marginTop: 8 }}>
              {busy ? '压缩中…' : '立即压缩历史'}
            </button>
            {msg && <div className="ctx-pop-msg">{msg}</div>}
            <div className="ctx-pop-hint">
              用量达到阈值（80%）时，发送前会自动把旧对话压缩为摘要；任务执行中过旧的工具输出也会自动收起为占位符。
            </div>
          </div>
        </>
      )}
    </div>
  )
}

/** 读取图片为 data URL；超过 1.5MB 时缩放到最长边 2000px 的 JPEG（质量 0.9）再返回。 */
function readImageDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(new Error('读取失败'))
    reader.readAsDataURL(file)
  }).then(async dataUrl => {
    if (dataUrl.length < 1_500_000) return dataUrl
    try {
      const img = await loadImage(dataUrl)
      const scale = Math.min(1, 2000 / Math.max(img.width, img.height))
      const canvas = document.createElement('canvas')
      canvas.width = Math.max(1, Math.round(img.width * scale))
      canvas.height = Math.max(1, Math.round(img.height * scale))
      canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height)
      return canvas.toDataURL('image/jpeg', 0.9)
    } catch {
      return dataUrl // 解码失败就用原图（后端会拦超大图）
    }
  })
}

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('图片解码失败'))
    img.src = src
  })
}

/** 把持久化消息聚合成「对话轮」（user → assistant 一对）。 */
function buildTurns(messages) {
  const turns = []
  for (const m of messages) {
    if (m.role === 'user') {
      turns.push({
        id: `m-${m.id}`,
        prompt: m.content || '',
        events: [],
        conclusion: null,
        streamText: '',
        thinking: '',
        running: false
      })
    } else if (m.role === 'assistant') {
      const last = turns.length ? turns[turns.length - 1] : null
      if (last && last.conclusion == null && !last.running) {
        last.conclusion = m.content || ''
        last.events = Array.isArray(m.events) ? m.events : []
        last.traceId = m.traceId || undefined
        last.runId = m.taskRunId || undefined
        const outcomeEvent = last.events.find(e => e.type === 'task_outcome')
        last.outcome = outcomeEvent?.outcome
      } else {
        // 没有前置 user（异常情况）：单独成轮
        turns.push({
          id: `m-${m.id}`,
          prompt: '',
          events: Array.isArray(m.events) ? m.events : [],
          conclusion: m.content || '',
          traceId: m.traceId || undefined,
          runId: m.taskRunId || undefined,
          outcome: Array.isArray(m.events) ? m.events.find(e => e.type === 'task_outcome')?.outcome : undefined,
          streamText: '',
          thinking: '',
          running: false
        })
      }
    }
  }
  return turns
}
