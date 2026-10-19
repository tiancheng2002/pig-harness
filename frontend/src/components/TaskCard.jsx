import { useEffect, useRef, useState } from 'react'
import Md from './Md.jsx'
import SaveSkillModal from './SaveSkillModal.jsx'
import TaskOutcomeCard from './TaskOutcomeCard.jsx'
import { fetchGitStatus } from '../api.js'
import { FileIcon, IconLayers } from './icons.jsx'

const TOOL_VERBS = {
  fileWrite: '写入',
  fileEdit: '修改',
  fileRead: '读取',
  listFiles: '浏览目录',
  fileDelete: '删除',
  docxWrite: '生成文档',
  bash: '执行命令',
}

/** 会改动文件的工具（turn-diff 汇总的数据源之一）。 */
const MUTATING_TOOLS = ['fileWrite', 'fileEdit', 'fileDelete', 'docxWrite']

/**
 * 单轮对话（Codex/ZCode 风格，无头像）：
 * 用户消息右侧气泡（可含图片缩略图）；Agent 侧为「任务过程折叠块」——计划/思考/步骤说明/工具/文件/
 * 子 Agent 泳道按时间序收进一个可展开容器（运行中自动展开、结束后自动收起），只有最终结论常显在外；
 * CHAT 直答轮（无过程事件）保持「思考面板 + 直答」形态。
 */
export default function TaskCard({ task, onOpenFile, conversationId, onApprove, onAnswer, onOpenPlan, projectId, onOpenDiff,
                                   onEditPrompt, editActive, editBusy }) {
  const [saveSkillOpen, setSaveSkillOpen] = useState(false)

  // 多 Agent 泳道（M4）：按 agent 字段分组；主事件列表只渲染无 agent 标注的事件
  const laneOrder = []
  const laneMeta = {}
  const laneEvents = {}
  const blackboard = []
  for (const ev of task.events) {
    if (ev.type === 'blackboard') {
      blackboard.push(ev)
      continue
    }
    if (!ev.agent) continue
    if (!laneMeta[ev.agent]) {
      laneMeta[ev.agent] = { task: '', done: null }
      laneOrder.push(ev.agent)
      laneEvents[ev.agent] = []
    }
    if (ev.type === 'agent_spawn') {
      laneMeta[ev.agent].task = ev.task || ''
    } else if (ev.type === 'agent_done') {
      laneMeta[ev.agent].done = ev
    } else {
      laneEvents[ev.agent].push(ev)
    }
  }

  const mainEvents = task.events.filter(ev => !ev.agent && ev.type !== 'blackboard'
    && !['task_metrics', 'task_outcome', 'done'].includes(ev.type))
  // CHAT 直答轮没有任何过程事件；TASK 轮（含工具/思考/计划）全部收进折叠块
  const hasProcess = mainEvents.length > 0 || laneOrder.length > 0 || blackboard.length > 0

  // 已决审批/已答问题 = 同 callId 的 tool_call 已到达（结果里带答复文本）
  const toolCallById = new Map(
    task.events.filter(e => e.type === 'tool_call' && e.callId).map(e => [e.callId, e]))
  const resolvedCalls = new Set(toolCallById.keys())
  const hasPendingApproval = task.running
    && task.events.some(e => e.type === 'approval_request' && !resolvedCalls.has(e.callId))
  const mainRunningTool = task.running && task.runningTool && !task.runningTool.agent ? task.runningTool : null
  // 计划模式轮次（后端 plan_mode 事件标记）：结论区显示「按计划实现」
  const isPlanTurn = task.events.some(e => e.type === 'plan_mode')

  // 折叠块头部的执行统计（收起后也能看出这轮干了什么）
  const toolCount = task.events.filter(e => e.type === 'tool_call').length
  const fileCount = task.events.filter(e => e.type === 'file').length

  // CHAT 直答轮的思考（reasoning_content 不落库，仅本轮实时可见）
  const chatThoughts = hasProcess || !task.thinking
    ? []
    : [{ kind: 'reasoning', text: task.thinking, live: true }]
  const thinkingNow = task.running && !!task.thinking && !task.streamText

  return (
    <div className="turn">
      {(task.prompt || task.images?.length > 0 || task.events.some(e => e.type === 'image')) && (
        <div className="user-msg">
          {/* 实时轮次用内存 data URL（task.images）；回放轮次从 image 事件取留存路径（/api/file 按默认根取图） */}
          {task.images?.length > 0 && (
            <div className="user-images">
              {task.images.map((src, i) => (
                <img key={i} src={src} alt={`图片 ${i + 1}`} title={`图片 ${i + 1}`} />
              ))}
            </div>
          )}
          {task.images?.length > 0 ? null : (
            <TurnImages events={task.events} onOpenFile={onOpenFile} />
          )}
          {task.prompt && <div className={`user-bubble${editActive ? ' editing' : ''}`}>
            <PromptContent text={task.prompt} onOpenFile={onOpenFile} />
          </div>}
          {task.prompt && (
            <BubbleActions text={task.prompt} onEdit={onEditPrompt} editing={editActive} busy={editBusy} />
          )}
        </div>
      )}
      <div className="agent-msg">
        {hasProcess ? (
          <ProcessBlock task={task} toolCount={toolCount} fileCount={fileCount}>
            {laneOrder.map(id => (
              <AgentLane
                key={id}
                agentId={id}
                meta={laneMeta[id]}
                events={laneEvents[id]}
                liveText={task.laneText?.[id]}
                runningTool={task.runningTool}
                running={task.running}
                onOpenFile={onOpenFile}
              />
            ))}
            {blackboard.length > 0 && (
              <details className="blackboard-block">
                <summary>🗂 共享登记 · {blackboard.length}</summary>
                <div className="bb-body">
                  {blackboard.map((b, i) => (
                    <div key={i} className="bb-row">
                      <code>{b.key}</code>
                      <span className="bb-val">{b.value}</span>
                      <span className="bb-owner">{b.owner}</span>
                    </div>
                  ))}
                </div>
              </details>
            )}
            <ChronoFlow
              events={mainEvents}
              task={task}
              resolvedCalls={resolvedCalls}
              toolCallById={toolCallById}
              conversationId={conversationId}
              onApprove={onApprove}
              onAnswer={onAnswer}
              onOpenFile={onOpenFile}
            />
            <ToolLive tool={mainRunningTool} />
            {task.running && !task.streamText && !task.runningTool && !hasPendingApproval && (
              <div className="running-indicator"><span /><span /><span /></div>
            )}
            {task.running && task.streamText && (
              <div className="answer streaming">
                <Md text={task.streamText} />
                <span className="type-cursor" />
              </div>
            )}
          </ProcessBlock>
        ) : (
          <ThinkingPanel thoughts={chatThoughts} streaming={thinkingNow} running={task.running} />
        )}
        {!hasProcess && task.running && task.streamText && (
          <div className="answer streaming">
            <Md text={task.streamText} />
            <span className="type-cursor" />
          </div>
        )}
        {!task.running && task.outcome ? (
          <TaskOutcomeCard outcome={task.outcome} conclusion={task.conclusion} planMode={isPlanTurn && !!task.conclusion}>
            {isPlanTurn && task.conclusion && <PlanDocCard text={task.conclusion} onOpen={onOpenPlan} />}
          </TaskOutcomeCard>
        ) : !task.running && task.conclusion && (isPlanTurn ? (
          // 计划轮：结论即计划文档——渲染为「计划」卡片（截断预览 + 抽屉看全文），不整篇铺开
          <PlanDocCard text={task.conclusion} onOpen={onOpenPlan} />
        ) : (
          <div className="answer">
            <Md text={task.conclusion} />
          </div>
        ))}
        {!task.running && (task.traceId || task.conclusion || task.outcome?.summary) && (
          <div className="turn-actions">
            {task.traceId && <button className="btn sm" onClick={() => setSaveSkillOpen(true)}>💾 存为技能</button>}
            <ConclusionActions text={task.conclusion || task.outcome?.summary || ''} />
          </div>
        )}
        {!task.running && projectId && !task.outcome && (
          <TurnDiffBar events={task.events} projectId={projectId} onOpenDiff={onOpenDiff} />
        )}
      </div>
      {saveSkillOpen && (
        <SaveSkillModal
          traceId={task.traceId}
          taskPrompt={task.prompt}
          onClose={() => setSaveSkillOpen(false)}
        />
      )}
    </div>
  )
}

/**
 * 任务过程折叠块（zcode 式）：执行过程（计划/思考/说明/工具/文件/子 Agent）收进一个可展开容器，
 * 运行中自动展开、结束后自动收起 —— 只有最终结论常显在容器外；头部统计让收起态也知道干了什么。
 */
function ProcessBlock({ task, toolCount, fileCount, children }) {
  const [open, setOpen] = useState(task.running)
  const wasRunning = useRef(task.running)
  useEffect(() => {
    if (task.running && !wasRunning.current) {
      setOpen(true)
      wasRunning.current = true
    } else if (!task.running && wasRunning.current) {
      setOpen(false)
      wasRunning.current = false
    }
  }, [task.running])
  const stats = [
    toolCount > 0 ? `${toolCount} 项工具` : null,
    fileCount > 0 ? `${fileCount} 个文件` : null,
  ].filter(Boolean).join(' · ')
  return (
    <div className="process-block">
      <button className="process-head" onClick={() => setOpen(!open)}>
        {task.running ? <span className="spin">✳</span> : <span>✳</span>}
        <span>{task.running ? '思考与执行中…' : '已思考与执行'}</span>
        {!task.running && stats && <span className="process-stats">{stats}</span>}
        <span className={`chevron ${open ? 'up' : ''}`} />
      </button>
      {open && <div className="process-body">{children}</div>}
    </div>
  )
}

/** 单段思考（过程流内联）：折叠态显示开头摘要预览，展开看全文 —— 每步操作的依据随时可查。 */
function ThinkRow({ text }) {
  const [open, setOpen] = useState(false)
  if (!text) return null
  const preview = text.replace(/\s+/g, ' ').trim().slice(0, 100)
  return (
    <div className="think-row">
      <button className="think-row-head" onClick={() => setOpen(!open)}>
        <span className="think-icon">✳</span>
        <span className="think-label">思考</span>
        {!open && preview && <span className="think-preview">{preview}</span>}
        <span className={`chevron ${open ? 'up' : ''}`} />
      </button>
      {open && <div className="think-row-body">{text}</div>}
    </div>
  )
}

/**
 * 时间序过程流：思考段、步骤说明、工具调用、审批卡、文件按实际发生顺序交织渲染；
 * 连续 tool_call 仍归组为一条折叠汇总、连续 file 仍归组为 chip 云（沿用既有防刷屏规则）。
 */
function ChronoFlow({ events, task, resolvedCalls, toolCallById, conversationId, onApprove, onAnswer, onOpenFile }) {
  const out = []
  let toolRun = []
  let fileRun = []
  let k = 0
  // 写类工具每成功一次就跟一个 file 事件。若让 file 打断工具组，批量编辑就会被切成
  // 「1 项工具 + 1 个文件」的交替长列表；这里把二者收进同一组，折叠态只报改动文件数。
  const flush = () => {
    if (toolRun.length) {
      out.push(<ToolGroup key={'tg' + k++} tools={toolRun} files={fileRun}
                          onOpenFile={onOpenFile} />)
    } else if (fileRun.length) {
      out.push(<FileChips key={'fc' + k++} events={fileRun} onOpenFile={onOpenFile} />)
    }
    toolRun = []
    fileRun = []
  }
  events.forEach((ev, i) => {
    if (ev.type === 'tool_call') {
      toolRun.push(ev)
      return
    }
    if (ev.type === 'file') {
      fileRun.push(ev)
      return
    }
    flush()
    switch (ev.type) {
      case 'thinking': // 本轮思考定稿：折叠行 + 摘要预览（该步操作的依据）
        out.push(<ThinkRow key={'th' + i} text={ev.text} />)
        return
      case 'step_commentary': // 模型对下一步操作的说明：正文式常显
        out.push(<div key={'sc' + i} className="step-note">{ev.text}</div>)
        return
      case 'plan':
        out.push(<PlanView key={'p' + i} task={task} />)
        return
      case 'approval_request': // 审批卡在时间序原位（运行中折叠块强制展开，不会错过）
        out.push(
          <ApprovalCard
            key={'ap' + (ev.callId || i)}
            ev={ev}
            resolved={resolvedCalls.has(ev.callId)}
            canAct={!!conversationId && !!onApprove && task.running}
            conversationId={conversationId}
            onApprove={onApprove}
          />
        )
        return
      case 'question_request': // 澄清问题卡（计划模式）：原位渲染，已答态由同 callId 的 tool_call 判定
        out.push(
          <QuestionCard
            key={'qq' + (ev.callId || i)}
            ev={ev}
            resolvedToolCall={toolCallById ? toolCallById.get(ev.callId) : null}
            canAct={!!conversationId && !!onAnswer && task.running}
            conversationId={conversationId}
            onAnswer={onAnswer}
          />
        )
        return
      default:
        out.push(<EventView key={'e' + i} ev={ev} onOpenFile={onOpenFile} />)
    }
  })
  flush()
  return out
}

/** 子 Agent 泳道（M4）：agent_spawn 作标题，体内渲染该子 Agent 的工具/文件/说明与流式文本。 */
function AgentLane({ agentId, meta, events, liveText, runningTool, running, onOpenFile }) {
  const [open, setOpen] = useState(true)
  const done = meta.done
  const status = done ? done.status : 'running'
  const icon = status === 'success' ? '✅'
    : status === 'partial' ? '⚠️'
    : status === 'failed' ? '❌'
    : status === 'cancelled' ? '⏹' : '🤖'
  const commentary = events
    .filter(e => e.type === 'thinking' || e.type === 'step_commentary')
    .map(e => e.text)
  const laneTools = events.filter(e => e.type === 'tool_call')
  const laneOthers = events.filter(e =>
    e.type !== 'tool_call' && e.type !== 'thinking' && e.type !== 'step_commentary')
  const laneRunningTool = runningTool && runningTool.agent === agentId ? runningTool : null
  return (
    <div className={`agent-lane ${status}`}>
      <button className="lane-head" onClick={() => setOpen(!open)}>
        <span className="lane-icon">{icon}</span>
        <span className="lane-name">{agentId}</span>
        {meta.task && <span className="lane-task">{meta.task}</span>}
        {done && done.files?.length > 0 && (
          <span className="lane-files">{done.files.length} 文件</span>
        )}
        <span className={`chevron ${open ? 'up' : ''}`} />
      </button>
      {open && (
        <div className="lane-body">
          {commentary.map((t, i) => (
            <div key={i} className="lane-commentary">{t}</div>
          ))}
          <ToolGroup tools={laneTools} />
          <ToolLive tool={running && !done ? laneRunningTool : null} />
          <EventFlow events={laneOthers} onOpenFile={onOpenFile} />
          {running && !done && liveText && (
            <div className="lane-live">
              <Md text={liveText} />
              <span className="type-cursor" />
            </div>
          )}
          {done && done.summary && <div className="lane-summary">{done.summary}</div>}
        </div>
      )}
    </div>
  )
}

/** 工具调用目标摘要：与后端 toolTarget 同口径（路径/命令/目录/URL/检索词）。 */
function toolTargetOf(ev) {
  const a = ev.args || {}
  const v = a.path || a.dir || a.command || a.url || a.query || a.keywords
  if (!v) return ''
  return String(v).replace(/\s+/g, ' ').slice(0, 160)
}

/** 同一文件被多次写入只算一个文件，保留最后一次（尺寸为最终值）。 */
function dedupeFiles(files) {
  const byPath = new Map()
  for (const f of files || []) byPath.set(f.path, f)
  return [...byPath.values()]
}

/** 相邻同类调用去重：name+目标一致且结果状态相同的连续调用合并为一行 ×N（长任务避免刷屏）。 */
function dedupeTools(tools) {
  const out = []
  for (const t of tools) {
    const prev = out[out.length - 1]
    const k = t.name + '|' + toolTargetOf(t)
    if (prev && prev._k === k && prev.ok === t.ok) {
      prev._count++
      continue
    }
    out.push({ ...t, _k: k, _count: 1 })
  }
  return out
}

/**
 * 工具执行分组（Codex 式）：完成的工具与它们产出的文件收进一条折叠汇总。
 * 折叠态优先报「改动了几个文件」——批量编辑时这才是用户关心的量；展开看逐条明细与文件清单。
 */
function ToolGroup({ tools, files = [], onOpenFile }) {
  const [open, setOpen] = useState(false)
  if (!tools.length) return null
  const failCount = tools.filter(e => e.ok === false).length
  // 折叠态可见的类型分解：写入×10 · 读取×8 · 执行命令×4 …（不用展开就能知道执行了什么）
  const counts = {}
  for (const t of tools) counts[t.name] = (counts[t.name] || 0) + 1
  const brief = Object.entries(counts)
    .sort((a, b) => b[1] - a[1])
    .map(([n, c]) => `${TOOL_VERBS[n] || n}×${c}`)
    .join(' · ')
  const touched = dedupeFiles(files)
  return (
    <div className="tool-group">
      <button className="tool-group-head" onClick={() => setOpen(!open)}>
        <span className={`tool-dot ${failCount ? 'fail' : 'ok'}`} />
        <span className="tool-group-title">
          {touched.length
            ? `改动 ${touched.length} 个文件 · ${tools.length} 项工具`
            : `已执行 ${tools.length} 项工具`}
          {failCount ? ` · ${failCount} 项失败` : ''}
        </span>
        {brief && <span className="tool-group-brief">{brief}</span>}
        <span className={`chevron ${open ? 'up' : ''}`} />
      </button>
      {open && (
        <div className="tool-group-body">
          {dedupeTools(tools).map((ev, i) => <ToolRow key={i} ev={ev} />)}
          {touched.length > 0 && <FileChips events={touched} onOpenFile={onOpenFile} />}
        </div>
      )}
    </div>
  )
}

/** 正在执行的工具（滚动指示）：tool_start 推流亮起，对应 tool_call 到达后熄灭。 */
function ToolLive({ tool }) {
  if (!tool) return null
  const verb = TOOL_VERBS[tool.name] || '执行'
  const label = verb === '执行命令' ? '正在执行命令' : `正在执行${verb}`
  return (
    <div className="tool-live">
      <span className="spin">⚙</span>
      <span className="tool-verb">{label}</span>
      {tool.target && <span className="tool-target">{tool.target}</span>}
    </div>
  )
}

/** 路径统一为正斜杠相对形式，与 git porcelain 清单对齐。 */
function normPath(p) {
  return String(p).replace(/\\/g, '/').replace(/^\.\//, '')
}

/**
 * 本次变更文件聚合条（Codex 式，仅项目会话）：任务结束后从事件流提取本次触碰的文件，
 * 与项目 git 状态求交集，展示 N 个文件已更改 +X −Y；展开每行可点击 → 右侧抽屉看行级 diff。
 */
function TurnDiffBar({ events, projectId, onOpenDiff }) {
  const [open, setOpen] = useState(false)
  const [changes, setChanges] = useState(null)

  // 本次任务触碰的文件：file 事件 + 写/改/删工具的 path 参数（含子 Agent 泳道）
  const touched = new Set()
  for (const ev of events) {
    if (ev.type === 'file' && ev.path) touched.add(normPath(ev.path))
    if (ev.type === 'tool_call' && MUTATING_TOOLS.includes(ev.name) && ev.args?.path) {
      touched.add(normPath(ev.args.path))
    }
  }

  useEffect(() => {
    if (!projectId || touched.size === 0) return
    let alive = true
    fetchGitStatus(projectId)
      .then(st => {
        if (!alive) return
        const list = st?.repo && Array.isArray(st.changes)
          ? st.changes.filter(c => touched.has(normPath(c.path)))
          : []
        setChanges(list)
      })
      .catch(() => { if (alive) setChanges([]) })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  if (!projectId || touched.size === 0 || !changes?.length) return null
  const totalAdd = changes.reduce((s, c) => s + (c.additions || 0), 0)
  const totalDel = changes.reduce((s, c) => s + (c.deletions || 0), 0)
  return (
    <div className="turn-diff">
      <button className="td-head" onClick={() => setOpen(!open)}>
        <span className={`chevron ${open ? 'up' : ''}`} />
        <span className="td-title">{changes.length} 个文件已更改</span>
        <span className="td-add">+{totalAdd}</span>
        <span className="td-del">−{totalDel}</span>
      </button>
      {open && (
        <div className="td-body">
          {changes.map(c => (
            <button key={c.path} className="td-row" onClick={() => onOpenDiff && onOpenDiff(c)}
                    title="在右侧抽屉查看行级变更">
              <span className={`td-code ${codeClass(c.code)}`}>{c.label || c.code}</span>
              <span className="td-path">{c.path}</span>
              <span className="td-stat">
                <span className="td-add">+{c.additions || 0}</span>
                <span className="td-del">−{c.deletions || 0}</span>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

function codeClass(code) {
  const c = String(code || '')
  if (c === '??' || c.includes('A')) return 'add'
  if (c.includes('D')) return 'del'
  return 'mod'
}

/** 回放轮次的图片缩略图：image 事件的 path 相对默认工作区（/api/file 不带会话参数按默认根解析）；
 *  文件被清理/删除时 onError 显示空白占位。 */
function TurnImages({ events, onOpenFile }) {
  const imgs = events.filter(e => e.type === 'image' && e.path)
  if (!imgs.length) return null
  return (
    <div className="user-images">
      {imgs.map((ev, i) => (
        <EventImage key={`${ev.index}-${i}`} ev={ev} onOpenFile={onOpenFile} />
      ))}
    </div>
  )
}

function EventImage({ ev, onOpenFile }) {
  const [gone, setGone] = useState(false)
  if (gone) return <span className="user-img gone" title="图片文件已被清理" />
  const url = `/api/file?path=${encodeURIComponent(ev.path)}`
  return (
    <img
      src={url}
      alt={`图片 ${ev.index || 1}`}
      title={`图片 ${ev.index || 1}（点击放大）`}
      onClick={() => onOpenFile && onOpenFile(ev.path, null, { noConv: true })}
      onError={() => setGone(true)}
    />
  )
}

function isWarn(text) {
  return text.startsWith('⚠') || text.includes('异常')
}

/** 执行计划（可折叠 + 进度）。 */
function PlanView({ task }) {
  const [open, setOpen] = useState(task.running)
  const planEv = task.events.find(e => e.type === 'plan')
  if (!planEv) return null
  const done = new Set(
    task.events.filter(e => e.type === 'plan_step' && e.status === 'done').map(e => e.index))
  const failed = new Set(
    task.events.filter(e => e.type === 'plan_step' && e.status === 'failed').map(e => e.index))
  let runningAssigned = false
  const steps = planEv.steps.map(s => {
    if (done.has(s.index)) return { ...s, status: 'done' }
    if (failed.has(s.index)) return { ...s, status: 'failed' }
    if (task.running && !runningAssigned) {
      runningAssigned = true
      return { ...s, status: 'running' }
    }
    return { ...s, status: 'pending' }
  })
  const doneCount = steps.filter(s => s.status === 'done').length
  return (
    <div className="plan-card">
      <button className="plan-toggle" onClick={() => setOpen(!open)}>
        ✳ 执行计划
        <span className="plan-progress">{doneCount}/{steps.length}</span>
        <span className={`chevron ${open ? 'up' : ''}`} />
      </button>
      {open && (
        <ol className="plan-steps">
          {steps.map(s => (
            <li key={s.index} className={`plan-step ${s.status}`}>
              <span className="step-mark">
                {s.status === 'done' ? '✓' : s.status === 'failed' ? '✕' : s.status === 'running' ? '◉' : '○'}
              </span>
              <span className="step-text">{s.step}</span>
              {s.tool && <span className="tool-tag">{s.tool}</span>}
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

/** 思考过程折叠块（思考流式时自动展开、结束后收起）。 */
function ThinkingPanel({ thoughts, streaming, running }) {
  const [open, setOpen] = useState(false)
  const wasStreaming = useRef(false)
  useEffect(() => {
    if (streaming && !wasStreaming.current) {
      setOpen(true)
      wasStreaming.current = true
    } else if (!streaming && wasStreaming.current) {
      setOpen(false)
      wasStreaming.current = false
    }
  }, [streaming])
  // 空思考不再返回 null：运行中（思考模型首轮可达数十秒）必须给出可见的加载动画，否则 Agent 侧一片空白
  if (!thoughts.length) {
    return running ? (
      <div className="running-indicator" title="正在思考与执行，首个事件到达前显示加载动画">
        <span /><span /><span />
      </div>
    ) : null
  }
  return (
    <div className="thinking-panel">
      <button className="think-toggle" onClick={() => setOpen(!open)}>
        {streaming
          ? <span className="spin">✳</span>
          : <span>✳</span>}
        {streaming ? '思考中…' : `已思考 · ${thoughts.length} 段`}
        <span className={`chevron ${open ? 'up' : ''}`} />
      </button>
      {open && (
        <div className="thinking-body">
          {thoughts.map((t, i) => (
            <div key={i} className={`thought ${t.kind}`}>
              <div className="thought-text">{t.text}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function EventView({ ev, onOpenFile }) {
  switch (ev.type) {
    case 'plan':
    case 'plan_step':
    case 'thinking':
    case 'step_commentary':
    case 'approval_request': // 单独渲染为审批卡片
    case 'question_request': // 单独渲染为澄清问题卡（计划模式）
    case 'plan_mode': // 计划模式标记（结论区渲染「按计划实现」按钮）
    case 'agent_spawn': // M4：单独渲染为子 Agent 泳道
    case 'agent_done':
    case 'blackboard':
    case 'done':
    case 'task_metrics':
    case 'task_outcome':
      return null
    case 'skill': // 本次执行使用的技能（选中或自动命中）
      return (
        <div className="skill-used-chip" title={ev.id}>
          <span className="chip-icon"><IconLayers size={12} /></span>
          <span className="chip-name">/{ev.name || ev.id}</span>
        </div>
      )
    case 'context_compact': // 上下文压缩（对话级摘要 / 任务内工具输出占位）
      return (
        <div className="compact-row" title="为控制上下文长度，旧内容已压缩为摘要/占位符（落库消息不受影响）">
          🗜 上下文已压缩（{ev.scope === 'task' ? '任务内工具输出' : '对话历史'}）
          ：约 {ev.before} → {ev.after} tokens
        </div>
      )
    case 'vision': // 视觉模型对用户所附图片的转述（可展开查看）
      return (
        <details className="vision-block" key={ev.index}>
          <summary>📷 图片 {ev.index || 1} · 已理解</summary>
          <div className="vision-text">{ev.text}</div>
        </details>
      )
    case 'image': // 原图留存路径事件：缩略图渲染在用户消息区（TurnImages），事件流里不重复展示
      return null
    case 'tool_call':
      return <ToolRow ev={ev} />
    case 'reflect':
      return (
        <div className="reflect-row">{ev.reason}</div>
      )
    case 'hook': // P1 验证钩子：结论前必须通过的项目 verify 命令
    case 'verification': // Agent 主动运行的测试 / 构建 / lint / 复现
      return (
        <div className={`hook-row ${ev.ok ? 'pass' : 'fail'}`}
             title={ev.output ? `输出：${ev.output}` : (ev.ok ? '验证通过' : '验证失败')}>
          {ev.ok ? '✔' : '✘'} 验证: {ev.name || ev.kind || 'verify'}
          {typeof ev.durationMs === 'number' && <span className="hook-ms">（{ev.durationMs}ms）</span>}
        </div>
      )
    case 'guard':
      return (
        <div className={`guard-row ${ev.terminal ? 'terminal' : ''}`} title={ev.code || 'guard'}>
          ⚠ {ev.message || '交付条件尚未满足'}
        </div>
      )
    case 'file':
      return (
        <button className="file-chip" onClick={() => onOpenFile && onOpenFile(ev.path, ev.size)}>
          <FileIcon name={ev.path} size={14} />
          <span className="file-name">{ev.path}</span>
          {typeof ev.size === 'number' && ev.size >= 0 && (
            <span className="file-size">{humanSize(ev.size)}</span>
          )}
        </button>
      )
    default:
      return (
        <details className="unknown-row">
          <summary>❔ {ev.type || 'unknown'}</summary>
          <div className="tool-body">
            <pre>{pretty(ev)}</pre>
          </div>
        </details>
      )
  }
}

/**
 * 澄清问题卡（计划模式）：Agent 就关键分歧向用户提问——点选候选项（推荐项带徽标置顶）或自由输入；
 * 已答态由同 callId 的 tool_call 判定，答复文本取自其 result（历史回放同样还原）。
 */
function QuestionCard({ ev, resolvedToolCall, canAct, conversationId, onAnswer }) {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)
  const [free, setFree] = useState('')
  const options = Array.isArray(ev.options) ? ev.options : []
  const rec = typeof ev.recommended === 'number'
    && ev.recommended >= 0 && ev.recommended < options.length ? ev.recommended : -1

  async function answer(text) {
    if (!text || busy) return
    setBusy(true)
    setErr(null)
    try {
      await onAnswer(ev.callId, text)
      setFree('')
    } catch (e) {
      setErr(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={`question-card ${resolvedToolCall ? 'resolved' : 'pending'}`}>
      <div className="question-head">
        <span className="question-icon">{resolvedToolCall ? '💬' : '❓'}</span>
        <span className="question-title">{resolvedToolCall ? '已答复' : '需要你确认'}</span>
      </div>
      <div className="question-text">{ev.question}</div>
      {!resolvedToolCall && options.length > 0 && (
        <div className="question-opts">
          {options.map((opt, i) => (
            <button key={i} className={`q-opt ${i === rec ? 'rec' : ''}`}
                    disabled={!canAct || busy}
                    title={i === rec ? '推荐项' : ''}
                    onClick={() => answer(opt)}>
              {i === rec && <span className="q-rec">推荐</span>}
              {opt}
            </button>
          ))}
        </div>
      )}
      {!resolvedToolCall && (
        <div className="q-free">
          <input
            value={free}
            placeholder="或输入你自己的想法…"
            disabled={!canAct || busy}
            onChange={e => setFree(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') answer(free.trim()) }}
          />
          <button className="btn sm primary" disabled={!canAct || busy || !free.trim()}
                  onClick={() => answer(free.trim())}>答复</button>
        </div>
      )}
      {resolvedToolCall && <div className="q-answered">{resolvedToolCall.result}</div>}
      {err && <div className="form-error">{err}</div>}
    </div>
  )
}

/** 审批卡片（权限模式=请求审批）：写操作执行前请用户确认。 */function ApprovalCard({ ev, resolved, canAct, conversationId, onApprove }) {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)
  const verb = TOOL_VERBS[ev.name] || '执行'

  async function decide(approve) {
    setBusy(true)
    setErr(null)
    try {
      await onApprove(ev.callId, approve)
    } catch (e) {
      setErr(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={`approval-card ${resolved ? 'resolved' : 'pending'}`}>
      <div className="approval-head">
        <span className="approval-icon">{resolved ? '🛡' : '⏸'}</span>
        <span className="approval-title">
          {resolved ? '审批已完成' : '请求审批：' + verb + '文件'}
        </span>
      </div>
      <div className="approval-body">
        <code className="approval-path">{ev.path || '(未指定路径)'}</code>
        {ev.summary && <div className="approval-summary">{ev.summary}</div>}
        {err && <div className="approval-err">{err}</div>}
      </div>
      {!resolved && canAct && (
        <div className="approval-actions">
          <button className="btn sm" disabled={busy} onClick={() => decide(false)}>拒绝</button>
          <button className="btn sm primary" disabled={busy} onClick={() => decide(true)}>批准执行</button>
        </div>
      )}
    </div>
  )
}

/** 工具调用单行（动词 + 目标），点击展开参数与结果。 */
function ToolRow({ ev }) {
  const [open, setOpen] = useState(false)
  const verb = TOOL_VERBS[ev.name] || '执行'
  const target = toolTargetOf(ev)
  return (
    <div className={`tool-row ${ev.ok ? '' : 'fail'}`}>
      <button className="tool-head" onClick={() => setOpen(!open)}>
        <span className={`tool-dot ${ev.ok ? 'ok' : 'fail'}`} />
        <span className="tool-verb">{verb}</span>
        {target && <span className="tool-target">{target}</span>}
        {ev._count > 1 && <span className="tool-repeat">×{ev._count}</span>}
        <span className={`chevron ${open ? 'up' : ''}`} />
      </button>
      {open && (
        <div className="tool-body">
          <div className="detail-label">参数</div>
          <pre>{pretty(ev.args)}</pre>
          <div className="detail-label">结果</div>
          <pre className={ev.ok ? '' : 'fail-text'}>{ev.result || '(空)'}</pre>
        </div>
      )}
    </div>
  )
}

/** 单条文件产物 chip。 */
function FileChip({ ev, onOpenFile }) {
  return (
    <button className="file-chip" onClick={() => onOpenFile && onOpenFile(ev.path, ev.size)}>
      <FileIcon name={ev.path} size={14} />
      <span className="file-name">{ev.path}</span>
      {typeof ev.size === 'number' && ev.size >= 0 && (
        <span className="file-size">{humanSize(ev.size)}</span>
      )}
    </button>
  )
}

/** 文件 chip 云：横向换行排列；任务结束后超过 12 个收敛为「+N 个文件」，点击展开全部。 */
function FileChips({ events, onOpenFile }) {
  const [expanded, setExpanded] = useState(false)
  const MAX = 12
  const overflow = events.length > MAX && !expanded
  const visible = overflow ? events.slice(0, MAX) : events
  return (
    <div className="file-chips">
      {visible.map((ev, i) => <FileChip key={i} ev={ev} onOpenFile={onOpenFile} />)}
      {overflow && (
        <button className="file-chip more" onClick={() => setExpanded(true)}>
          +{events.length - MAX} 个文件
        </button>
      )}
    </div>
  )
}

/**
 * 事件流渲染：按时间序输出事件，连续的 file 事件归组为一个可换行的 chip 云
 * （文件多时不再一条占一行地刷屏），其余事件交给 EventView（plan 事件渲染为计划卡片）。
 */
function EventFlow({ events, task, onOpenFile }) {
  const out = []
  let run = []
  const flush = (k) => {
    if (run.length) {
      out.push(<FileChips key={'fc' + k} events={run} onOpenFile={onOpenFile} />)
      run = []
    }
  }
  events.forEach((ev, i) => {
    if (ev.type === 'file') {
      run.push(ev)
      return
    }
    flush(i)
    if (ev.type === 'plan' && task) {
      out.push(<PlanView key={'p' + i} task={task} />)
    } else {
      out.push(<EventView key={'e' + i} ev={ev} onOpenFile={onOpenFile} />)
    }
  })
  flush('end')
  return out
}

export function humanSize(bytes) {
  if (bytes == null) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function pretty(obj) {
  if (obj == null) return '(空)'
  try {
    return JSON.stringify(obj, null, 2)
  } catch {
    return String(obj)
  }
}

/** 从计划文档提取标题：首个 Markdown 标题行，退化取首个非空行。 */
function planHeading(text) {
  const lines = String(text || '').split('\n').map(s => s.trim()).filter(Boolean)
  for (const l of lines) {
    const m = l.match(/^#{1,4}\s+(.+)$/)
    if (m) return m[1].replace(/[*`]/g, '').trim()
  }
  return lines.length ? lines[0].replace(/[*`#]/g, '').trim().slice(0, 40) : '实施计划'
}

/** 计划文档卡片（计划模式轮次的结论形态）：标题 + 截断预览 + 「查看完整计划」打开右侧抽屉。 */
function PlanDocCard({ text, onOpen }) {
  return (
    <div className="plan-doc-card">
      <div className="plan-doc-head">
        <span className="plan-doc-icon">📋</span>
        <span className="plan-doc-title">{planHeading(text)}</span>
        <span className="plan-doc-tag">计划</span>
      </div>
      <div className="plan-doc-preview"><Md text={text} /></div>
      <div className="plan-doc-foot">
        <button className="btn sm" onClick={() => onOpen && onOpen(text)}>查看完整计划 →</button>
      </div>
    </div>
  )
}

/** 历史与实时气泡共用：文件引用显示在原文位置，不再丢到附件区。 */
function PromptContent({ text, onOpenFile }) {
  const parts = []
  let offset = 0
  for (const match of text.matchAll(/@\{([^{}\r\n]+)\}/g)) {
    if (match.index > offset) parts.push(text.slice(offset, match.index))
    const path = match[1]
    parts.push(
      <button type="button" className="user-file-ref" title={path} key={`${match.index}-${path}`}
              onClick={() => onOpenFile?.(path)} disabled={!onOpenFile}>
        <FileIcon name={path} size={13} /><span>{path}</span>
      </button>
    )
    offset = match.index + match[0].length
  }
  if (offset < text.length) parts.push(text.slice(offset))
  return parts.length ? parts : text
}

/**
 * 用户气泡悬停操作行（气泡下方显现）：复制（任何轮次都有）+ 编辑重发（仅最后一轮，onEdit 缺省不渲染）。
 * 编辑采用「撤回到输入框」形态：发送后由后端截断原轮消息并按编辑内容重新执行。
 */
function BubbleActions({ text, onEdit, editing, busy }) {
  const [copied, setCopied] = useState(false)
  const copyTimer = useRef(null)

  useEffect(() => () => clearTimeout(copyTimer.current), [])

  async function copy() {
    let ok = false
    try {
      await navigator.clipboard.writeText(text)
      ok = true
    } catch {
      // 剪贴板 API 不可用（非安全上下文等）：execCommand 兜底
      try {
        const ta = document.createElement('textarea')
        ta.value = text
        ta.style.position = 'fixed'
        ta.style.opacity = '0'
        document.body.appendChild(ta)
        ta.select()
        ok = document.execCommand('copy')
        ta.remove()
      } catch { ok = false }
    }
    if (ok) {
      setCopied(true)
      clearTimeout(copyTimer.current)
      copyTimer.current = setTimeout(() => setCopied(false), 1200)
    }
  }

  return (
    <div className={`bubble-actions${editing ? ' visible' : ''}`}>
      <button className="bubble-act" onClick={copy}
              title={copied ? '已复制' : '复制内容'}>
        {copied ? '✓' : '⧉'}
      </button>
      {onEdit && (
        <button className="bubble-act" onClick={onEdit} disabled={busy || editing}
                title={editing ? '正在编辑该消息' : '编辑并重新发送（将撤回本轮对话含回复）'}>
          {busy ? '…' : '✏'}
        </button>
      )}
    </div>
  )
}

/** 仅导出或复制当前轮显示的结论 Markdown，不附加执行报告数据。 */
function ConclusionActions({ text }) {
  const [copied, setCopied] = useState(false)
  const timer = useRef(null)
  useEffect(() => () => clearTimeout(timer.current), [])
  if (!text) return null

  const exportConclusion = () => {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = '结论.md'
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }

  const copy = async () => {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text)
      } else {
        const ta = document.createElement('textarea')
        ta.value = text
        document.body.appendChild(ta)
        ta.select()
        document.execCommand('copy')
        ta.remove()
      }
      setCopied(true)
      clearTimeout(timer.current)
      timer.current = setTimeout(() => setCopied(false), 1200)
    } catch {
      // 剪贴板不可用时仍可导出结论。
    }
  }
  return (
    <>
      <button className="btn sm" onClick={exportConclusion} title="导出结论 Markdown">⬇ 导出</button>
      <button className="btn sm" onClick={copy} title="复制结论内容">
        {copied ? '✓ 已复制' : '⧉ 复制'}
      </button>
    </>
  )
}
