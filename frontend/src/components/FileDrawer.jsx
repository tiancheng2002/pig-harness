import { useEffect, useRef, useState } from 'react'
import Md from './Md.jsx'
import { humanSize } from './TaskCard.jsx'
import { fetchGitDiff } from '../api.js'
import { FileIcon } from './icons.jsx'

const IMAGE_EXT = ['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp']
const CODE_EXT = ['js', 'jsx', 'ts', 'tsx', 'css', 'json', 'py', 'java', 'txt', 'xml', 'yml', 'yaml', 'csv', 'log', 'sh', 'sql', 'toml', 'properties']

function kindOf(path) {
  const ext = (path || '').split('.').pop().toLowerCase()
  if (IMAGE_EXT.includes(ext)) return 'image'
  if (ext === 'md') return 'md'
  if (ext === 'html' || ext === 'htm') return 'html'
  if (ext === 'docx') return 'docx'
  return 'code'
}

/**
 * 文件预览抽屉：工作区右侧挤压式展开（推开对话内容，不遮挡），预览沙箱产出文件。
 * 文本/代码 → 源码；Markdown → 渲染；HTML → 预览/源码双 tab；图片 → 直接显示；docx → 下载。
 * 带 diff（项目会话点「本次变更」条目）时额外提供「变更」tab：行级增/删/改着色 + 双侧行号。
 * conversationId 用于项目会话：服务端按会话解析项目根目录。
 */
export default function FileDrawer({ path, size, diff, projectId, conversationId, noConv, onClose }) {
  const [text, setText] = useState(null)
  const [patch, setPatch] = useState(null)
  const [patchErr, setPatchErr] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [tab, setTab] = useState(diff ? 'diff' : 'preview')
  const kind = kindOf(path)
  const bodyRef = useRef(null)
  // noConv：附件等存于默认工作区的文件，强制按服务端默认沙箱根解析（不走会话项目根）
  const rootQs = !noConv
    ? `${conversationId ? `&conversationId=${encodeURIComponent(conversationId)}` : ''}${projectId ? `&projectId=${encodeURIComponent(projectId)}` : ''}`
    : ''

  useEffect(() => {
    setText(null)
    setError(null)
    setPatch(null)
    setPatchErr(null)
    setLoading(true)
    setTab(diff ? 'diff' : 'preview')
    if (kind === 'image' || kind === 'docx') {
      setLoading(false)
      return
    }
    let alive = true
    fetch(`/api/file?path=${encodeURIComponent(path)}${rootQs}`)
      .then(res => {
        if (!res.ok) throw new Error('HTTP ' + res.status)
        return res.text()
      })
      .then(t => { if (alive) { setText(t); setLoading(false) } })
      .catch(e => { if (alive) { setError(e.message); setLoading(false) } })
    return () => { alive = false }
  }, [path, kind, rootQs]) // eslint-disable-line react-hooks/exhaustive-deps

  // 变更 tab：懒加载行级 diff（打开时才请求）
  useEffect(() => {
    if (tab !== 'diff' || !diff || !projectId || patch || patchErr) return
    let alive = true
    fetchGitDiff(projectId, diff.path)
      .then(d => { if (alive) setPatch(d.diff ?? '') })
      .catch(e => { if (alive) setPatchErr(e.message) })
    return () => { alive = false }
  }, [tab, diff, projectId, patch, patchErr])

  useEffect(() => {
    const onKey = e => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const url = `/api/file?path=${encodeURIComponent(path)}${rootQs}`
  const name = (path || '').split('/').pop()
  const showTabs = !!diff || kind === 'html'
  const tabs = []
  if (diff) tabs.push({ id: 'diff', label: '变更' })
  if (kind === 'html') {
    tabs.push({ id: 'preview', label: '预览' }, { id: 'code', label: '源码' })
  } else {
    tabs.push({ id: 'preview', label: '内容' })
  }

  return (
    <aside className="drawer">
      <div className="drawer-head">
        <span className="drawer-file-icon"><FileIcon name={path} size={16} /></span>
        <div className="drawer-title">
          <span className="drawer-name">{name}</span>
          <span className="drawer-sub">
            {path}
            {typeof size === 'number' && size >= 0 ? ` · ${humanSize(size)}` : ''}
            {diff && diff.additions != null ? ` · +${diff.additions} −${diff.deletions}` : ''}
          </span>
        </div>
        <div className="drawer-actions">
          {showTabs && (
            <div className="drawer-tabs">
              {tabs.map(t => (
                <button key={t.id} className={`drawer-tab ${tab === t.id ? 'active' : ''}`}
                        onClick={() => setTab(t.id)}>{t.label}</button>
              ))}
            </div>
          )}
          <a className="drawer-btn" href={url} download={name} title="下载">⬇ 下载</a>
          <a className="drawer-btn" href={url} target="_blank" rel="noreferrer" title="新窗口打开">↗</a>
          <button className="icon-btn" onClick={onClose} title="关闭 (Esc)">✕</button>
        </div>
      </div>

      <div className="drawer-body" ref={bodyRef}>
        {loading && tab !== 'diff' && <div className="drawer-loading">加载中…</div>}
        {!loading && tab !== 'diff' && error && (
          <div className="drawer-empty">⚠ 无法加载文件：{error}</div>
        )}
        {!loading && tab !== 'diff' && error == null && kind === 'image' && (
          <div className="img-view"><img src={url} alt={name} /></div>
        )}
        {!loading && tab !== 'diff' && error == null && kind === 'docx' && (
          <div className="drawer-empty">
            <div style={{ fontSize: 40 }}><FileIcon name={name} size={40} /></div>
            <span>Word 文档不支持在线预览</span>
            <a className="btn primary" href={url} download={name}>⬇ 下载查看</a>
          </div>
        )}
        {!loading && tab !== 'diff' && error == null && kind === 'md' && <Md text={text || ''} />}
        {!loading && tab !== 'diff' && error == null && kind === 'html' && tab === 'preview' && (
          <iframe title={name} srcDoc={text || ''} sandbox="allow-scripts" />
        )}
        {!loading && tab !== 'diff' && error == null && (kind === 'code' || (kind === 'html' && tab === 'code')) && (
          <pre className="code-view">{text}</pre>
        )}

        {tab === 'diff' && (
          <>
            {!diff && <div className="drawer-empty">无变更信息</div>}
            {diff && patch == null && !patchErr && <div className="drawer-loading">加载变更…</div>}
            {diff && patchErr && <div className="drawer-empty">⚠ 无法加载变更：{patchErr}</div>}
            {diff && patch != null && <DiffView patch={patch} />}
          </>
        )}
      </div>
    </aside>
  )
}

/** 统一 diff 渲染：双侧行号 + 新增绿 / 删除红 / 修改由相邻 -+ 行对表达；hunk 头与文件头弱化显示。 */
function DiffView({ patch }) {
  const rows = diffRows(patch)
  return (
    <div className="diff-view">
      {rows.map((r, i) => (
        <div key={i} className={`diff-line ${r.kind}`}>
          <span className="dl-old">{r.old ?? ''}</span>
          <span className="dl-new">{r.new ?? ''}</span>
          <span className="dl-text">{r.text === '' ? ' ' : r.text}</span>
        </div>
      ))}
    </div>
  )
}

/** 解析统一 diff 文本为带行号的行序列（简单单文件 diff，git 输出）。 */
function diffRows(patch) {
  let oldNo = 0
  let newNo = 0
  const rows = []
  for (const raw of patch.split('\n')) {
    if (raw.startsWith('diff --git') || raw.startsWith('index ')) continue
    const hm = raw.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/)
    if (hm) {
      oldNo = parseInt(hm[1], 10)
      newNo = parseInt(hm[2], 10)
      rows.push({ kind: 'hunk', text: raw })
      continue
    }
    if (raw.startsWith('+++ ') || raw.startsWith('--- ')) {
      rows.push({ kind: 'meta', text: raw })
      continue
    }
    if (raw.startsWith('+')) {
      rows.push({ kind: 'add', old: '', new: newNo, text: raw.slice(1) })
      newNo++
      continue
    }
    if (raw.startsWith('-')) {
      rows.push({ kind: 'del', old: oldNo, new: '', text: raw.slice(1) })
      oldNo++
      continue
    }
    if (raw.startsWith('\\')) { // "\ No newline at end of file"
      rows.push({ kind: 'meta', text: raw })
      continue
    }
    if (raw === '' && rows.length && rows[rows.length - 1].kind === 'hunk') continue
    rows.push({ kind: 'ctx', old: oldNo, new: newNo, text: raw.startsWith(' ') ? raw.slice(1) : raw })
    oldNo++
    newNo++
  }
  return rows
}
