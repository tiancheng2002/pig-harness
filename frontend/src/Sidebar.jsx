import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  createConversation,
  deleteConversation,
  deleteProject,
  fetchConversations,
  fetchProjects,
  renameConversation
} from './api.js'
import FolderPickerModal from './components/FolderPickerModal.jsx'
import { confirmDialog } from './components/ConfirmDialog.jsx'
import { IconBook, IconChevronDown, IconFolder, IconLayers, IconSliders } from './components/icons.jsx'

/**
 * 左侧导航栏：
 * 上块：新对话 / 技能 / 知识 / 设置；
 * 下块：「项目」「对话」两个可折叠分组 —— 项目分组按项目归组会话，悬停项目行可快捷新建项目会话；
 * 底部：沙箱与主题。
 * 「新对话」跟随上下文：当前在项目会话中 → 新会话归属当前项目；否则为普通会话。
 */
export default function Sidebar({ config, theme, onToggleTheme, onOpenSettings }) {
  const [convs, setConvs] = useState([])
  const [projects, setProjects] = useState([])
  const [editingId, setEditingId] = useState(null)
  const [editTitle, setEditTitle] = useState('')
  const [pickerOpen, setPickerOpen] = useState(false)
  const [openSec, setOpenSec] = useState(() => ({
    projects: localStorage.getItem('harness.sec.projects') !== '0',
    chats: localStorage.getItem('harness.sec.chats') !== '0'
  }))
  // 每个项目的对话列表折叠状态（持久化；undefined 视为展开，当前会话所在项目强制展开）
  const [projOpen, setProjOpen] = useState(() => {
    try { return JSON.parse(localStorage.getItem('harness.proj.open') || '{}') } catch { return {} }
  })
  const navigate = useNavigate()
  const location = useLocation()

  const currentId = location.pathname.startsWith('/c/')
    ? location.pathname.slice(3) : null
  const activeNav = location.pathname.startsWith('/skills') ? 'skills'
    : location.pathname.startsWith('/knowledge') ? 'knowledge' : ''

  // 按 projectId 归组；未绑定项目的会话进「对话」分组
  const projConvs = {}
  const plainConvs = []
  for (const c of convs) {
    if (c.projectId) {
      (projConvs[c.projectId] ||= []).push(c)
    } else {
      plainConvs.push(c)
    }
  }

  function refresh() {
    fetchConversations().then(setConvs).catch(() => setConvs([]))
    fetchProjects().then(setProjects).catch(() => setProjects([]))
  }

  // 轻量刷新：只拉会话列表（轮询用，运行中会话显示转圈）
  function refreshConvs() {
    fetchConversations().then(setConvs).catch(() => {})
  }

  useEffect(() => {
    refresh()
    const handler = () => refresh()
    window.addEventListener('conversations-changed', handler)
    return () => window.removeEventListener('conversations-changed', handler)
  }, [])

  // P2-F3：空态示例卡「绑定本地项目」经全局事件打开文件夹选择器（Sidebar 持有 picker 状态）
  useEffect(() => {
    const handler = () => setPickerOpen(true)
    window.addEventListener('harness:bind-project', handler)
    return () => window.removeEventListener('harness:bind-project', handler)
  }, [])

  // 运行中会话轮询（体验升级七）：侧栏实时显示转圈；本机单用户，轻量接口 3s 轮询足够
  useEffect(() => {
    const t = setInterval(() => {
      if (document.visibilityState === 'visible') refreshConvs()
    }, 3000)
    return () => clearInterval(t)
  }, [])

  function toggleSec(key) {
    setOpenSec(prev => {
      const next = { ...prev, [key]: !prev[key] }
      localStorage.setItem(key === 'projects' ? 'harness.sec.projects' : 'harness.sec.chats', next[key] ? '1' : '0')
      return next
    })
  }

  /** 折叠/展开某个项目下的对话列表（localStorage 持久化）。手动切换始终生效（含当前会话所在项目）。 */
  function toggleProj(id, e) {
    e?.stopPropagation()
    setProjOpen(prev => {
      const next = { ...prev, [id]: prev[id] === false } // undefined=展开 → 折叠；false=折叠 → 展开
      localStorage.setItem('harness.proj.open', JSON.stringify(next))
      return next
    })
  }

  // 进入项目会话时一次性自动展开所在项目（避免正看的会话被藏起来）；
  // 每个会话 id 只处理一次——之后用户手动折叠不再被轮询刷新抢回展开态
  const autoExpandedFor = useRef(null)
  useEffect(() => {
    if (!currentId || autoExpandedFor.current === currentId) return
    const cur = convs.find(c => c.id === currentId)
    if (!cur) return // 会话列表未加载完，等下一次
    autoExpandedFor.current = currentId
    if (cur.projectId && projOpen[cur.projectId] === false) {
      setProjOpen(prev => {
        const next = { ...prev, [cur.projectId]: true }
        localStorage.setItem('harness.proj.open', JSON.stringify(next))
        return next
      })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentId, convs])

  /** 新对话跟随上下文：项目会话中 → 归属当前项目；普通会话/其他页面 → 普通会话（懒创建）。 */
  async function newChat() {
    setEditingId(null)
    const cur = convs.find(c => c.id === currentId)
    if (cur?.projectId) {
      try {
        const conv = await createConversation(cur.projectId)
        window.dispatchEvent(new Event('conversations-changed'))
        navigate(`/c/${conv.id}`)
        return
      } catch { /* 创建失败则退回普通会话 */ }
    }
    navigate('/chat')
  }

  /** 在指定项目下新建会话。 */
  async function newProjectChat(p, e) {
    e?.stopPropagation()
    setEditingId(null)
    try {
      const conv = await createConversation(p.id)
      window.dispatchEvent(new Event('conversations-changed'))
      navigate(`/c/${conv.id}`)
    } catch (err) {
      window.alert('创建项目会话失败：' + err.message)
    }
  }

  /** 点击项目行：打开该项目最近一条会话；项目处于折叠态时顺带展开（否则点了没反馈）。 */
  function openProject(p) {
    const list = projConvs[p.id]
    if (list && list.length > 0) {
      setEditingId(null)
      if (projOpen[p.id] === false) toggleProj(p.id)
      navigate(`/c/${list[0].id}`)
    }
  }

  function openConv(id) {
    setEditingId(null)
    navigate(`/c/${id}`)
  }

  function startRename(c, e) {
    e?.stopPropagation()
    setEditingId(c.id)
    setEditTitle(c.title || '')
  }

  function commitRename(e) {
    if (e) e.preventDefault()
    const id = editingId
    const title = editTitle.trim()
    setEditingId(null)
    if (!id || !title) return
    renameConversation(id, title).then(ok => {
      if (ok) refresh()
    })
  }

  function removeConv(c, e) {
    e?.stopPropagation()
    const label = c.title || '未命名会话'
    confirmDialog({
      title: '删除会话',
      message: `确定删除会话「${label}」？该会话及全部消息将一并删除，不可恢复。`,
      confirmText: '删除',
      danger: true
    }).then(ok => {
      if (!ok) return
      deleteConversation(c.id).then(ok2 => {
        if (!ok2) {
          window.alert('删除失败')
          return
        }
        if (currentId === c.id) navigate('/chat')
        refresh()
      })
    })
  }

  /** 解除项目绑定：会话保留并回到「对话」分组。 */
  function removeProject(p, e) {
    e?.stopPropagation()
    confirmDialog({
      title: '解除项目绑定',
      message: `确定解除项目「${p.name}」？其会话将保留并回到普通对话，本地文件不受影响。`,
      confirmText: '解除绑定',
      danger: true
    }).then(async ok => {
      if (!ok) return
      const ok2 = await deleteProject(p.id)
      if (!ok2) {
        window.alert('解除失败')
        return
      }
      const cur = convs.find(c => c.id === currentId)
      if (cur?.projectId === p.id) navigate('/chat')
      refresh()
    })
  }

  function renderConvItem(c) {
    return editingId === c.id ? (
      <div className="conv-item">
        <form className="conv-rename" onSubmit={commitRename} onClick={e => e.stopPropagation()}>
          <input
            autoFocus
            value={editTitle}
            onChange={e => setEditTitle(e.target.value)}
            onBlur={commitRename}
            onKeyDown={e => { if (e.key === 'Escape') setEditingId(null) }}
          />
        </form>
      </div>
    ) : (
      <div
        className={`conv-item ${currentId === c.id ? 'active' : ''}`}
        onClick={() => openConv(c.id)}
      >
        {/* 转圈固定在行最左（项目/普通会话一致），扫一眼就知道哪个会话在跑任务 */}
        {c.running && <span className="conv-spin" title="任务执行中…进入可看实时输出" />}
        <div className="conv-main">
          <div className="conv-title">
            {c.title || '未命名会话'}
          </div>
          <div className="conv-meta">
            <span>{formatTime(c.updatedAt)}</span>
            {c.messageCount > 0 && <span>{c.messageCount} 条</span>}
          </div>
        </div>
        <div className="conv-actions">
          <button className="icon-btn" title="重命名" onClick={e => startRename(c, e)}>✎</button>
          <button className="icon-btn danger" title="删除" onClick={e => removeConv(c, e)}>✕</button>
        </div>
      </div>
    )
  }

  return (
    <aside className="sidebar">
      <div className="side-top">
        <div className="brand">
          <span className="logo">&gt;_</span>
          <div className="brand-text">
            <span className="brand-name">Harness</span>
            <span className="brand-sub">自进化 Agent</span>
          </div>
        </div>
        <button className="side-btn primary" onClick={newChat}>
          <span className="nav-icon">＋</span><span className="nav-text">新对话</span>
        </button>
        <button className={`side-btn ${activeNav === 'skills' ? 'active' : ''}`} onClick={() => navigate('/skills')}>
          <span className="nav-icon"><IconLayers /></span><span className="nav-text">技能</span>
        </button>
        <button className={`side-btn ${activeNav === 'knowledge' ? 'active' : ''}`} onClick={() => navigate('/knowledge')}>
          <span className="nav-icon"><IconBook /></span><span className="nav-text">知识</span>
        </button>
        <button className="side-btn" onClick={onOpenSettings}>
          <span className="nav-icon"><IconSliders /></span><span className="nav-text">设置</span>
        </button>
      </div>

      <div className="side-bottom">
        {/* 项目分组 */}
        <div className="side-sec">
          <div className="side-sec-head" onClick={() => toggleSec('projects')}>
            <span className={`sec-chev ${openSec.projects ? '' : 'closed'}`}><IconChevronDown /></span>
            <span className="sec-title">项目</span>
            <span className="sec-count">{projects.length}</span>
            <button
              className="sec-add"
              title="添加项目（选择本地文件夹）"
              onClick={e => { e.stopPropagation(); setPickerOpen(true) }}
            >＋</button>
          </div>
          {openSec.projects && (
            <div className="sec-body">
              {projects.length === 0 && <div className="conv-empty">悬停标题点「＋」添加项目</div>}
              {projects.map(p => {
                const convs = projConvs[p.id] || []
                const activeInside = convs.some(c => c.id === currentId)
                const expanded = projOpen[p.id] !== false // 纯手动控制：undefined=展开，false=折叠
                return (
                  <div key={p.id} className="proj-block">
                    <div
                      className={`proj-item ${activeInside ? 'active' : ''}`}
                      title={p.rootPath}
                      onClick={() => openProject(p)}
                    >
                      <button
                        className={`proj-chev ${expanded ? 'open' : ''}`}
                        title={expanded ? '折叠该项目对话' : '展开该项目对话'}
                        onClick={e => toggleProj(p.id, e)}
                      >▶</button>
                      <span className="proj-icon"><IconFolder size={14} /></span>
                      <span className="proj-name">{p.name}</span>
                      <span className="proj-count">{convs.length}</span>
                      <div className="proj-actions">
                        <button className="icon-btn" title="在此项目中新建对话" onClick={e => newProjectChat(p, e)}>＋</button>
                        <button className="icon-btn danger" title="解除绑定" onClick={e => removeProject(p, e)}>✕</button>
                      </div>
                    </div>
                    {expanded && convs.map(c => (
                      <div key={c.id} className="proj-children">{renderConvItem(c)}</div>
                    ))}
                  </div>
                )
              })}
            </div>
          )}
        </div>

        {/* 对话分组（未绑定项目的会话） */}
        <div className="side-sec">
          <div className="side-sec-head" onClick={() => toggleSec('chats')}>
            <span className={`sec-chev ${openSec.chats ? '' : 'closed'}`}><IconChevronDown /></span>
            <span className="sec-title">对话</span>
            <span className="sec-count">{plainConvs.length}</span>
          </div>
          {openSec.chats && (
            <div className="sec-body">
              {plainConvs.length === 0 && <div className="conv-empty">暂无会话</div>}
              {plainConvs.map(c => <div key={c.id}>{renderConvItem(c)}</div>)}
            </div>
          )}
        </div>
      </div>

      <div className="side-footer">
        {config && (
          <>
            <div className="footer-line" title={config.root}>
              <span className="footer-path">
                <IconFolder size={12} /> {shorten(config.root, 22)}
              </span>
              <button className="theme-toggle" onClick={onToggleTheme}
                      title={theme === 'dark' ? '切换到浅色' : '切换到深色'}>
                {theme === 'dark' ? '☀' : '☾'}
              </button>
            </div>
            <div className={`footer-line ${config.mockMode ? 'warn' : 'ok'}`}>
              <span>{config.mockMode ? '⚠ Mock 演示模式' : '✓ LLM 已连接'}</span>
            </div>
          </>
        )}
      </div>

      {pickerOpen && (
        <FolderPickerModal
          onClose={() => setPickerOpen(false)}
          onCreated={() => refresh()}
        />
      )}
    </aside>
  )
}

function formatTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d)) return iso.slice(0, 16).replace('T', ' ')
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const pad = n => String(n).padStart(2, '0')
  if (sameDay) return `今天 ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function shorten(p, max) {
  if (!p) return ''
  return p.length > max ? '…' + p.slice(-max - 1) : p
}
