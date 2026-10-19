import { useEffect, useState } from 'react'
import { createProject, fsList } from '../api.js'
import { IconFolder } from './icons.jsx'

/**
 * 添加项目弹窗：逐级浏览本地文件夹选择项目根目录（不再手输路径）。
 * 项目名称可留空，默认取所选文件夹名。
 */
export default function FolderPickerModal({ onClose, onCreated }) {
  const [cur, setCur] = useState(null) // DirListing
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)

  function enter(path) {
    setLoading(true)
    setError(null)
    fsList(path)
      .then(setCur)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(() => { enter(null) }, [])

  async function bind() {
    if (!cur?.path || busy) return
    setBusy(true)
    setError(null)
    try {
      const p = await createProject(name.trim(), cur.path)
      window.dispatchEvent(new Event('conversations-changed'))
      onCreated?.(p)
      onClose()
    } catch (e) {
      setError(e.message)
      setBusy(false)
    }
  }

  function onKeyDown(e) {
    if (e.key === 'Escape') onClose()
  }

  return (
    <div className="modal-overlay" onClick={onClose} onKeyDown={onKeyDown}>
      <div className="modal folder-picker" onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <span className="fp-head-title"><IconFolder size={14} /> 选择项目文件夹</span>
          <button className="icon-btn" onClick={onClose}>✕</button>
        </div>
        <div className="modal-body">
          <div className="fp-toolbar">
            <button
              className="fp-up"
              disabled={!cur?.parent || loading}
              onClick={() => enter(cur.parent)}
              title="上一级"
            >↑ 上一级</button>
            <button className="fp-quick" disabled={loading} onClick={() => enter(cur?.home)} title="用户主目录">主目录</button>
            <button className="fp-quick" disabled={loading} onClick={() => enter(null)} title="磁盘根列表">此电脑</button>
          </div>
          <div className="fp-path" title={cur?.path || ''}>
            {cur?.path || '此电脑（选择磁盘）'}
          </div>

          <div className="fp-list">
            {loading && <div className="fp-empty">读取中…</div>}
            {!loading && error && <div className="fp-empty fp-err">{error}</div>}
            {!loading && !error && (cur?.dirs?.length || 0) === 0 && (
              <div className="fp-empty">此目录下没有子文件夹</div>
            )}
            {!loading && !error && cur?.dirs?.map(d => (
              <button key={d.path} className="fp-dir" onDoubleClick={() => enter(d.path)} onClick={() => enter(d.path)}>
                <span className="fp-dir-icon"><IconFolder size={14} /></span>
                <span className="fp-dir-name">{d.name}</span>
              </button>
            ))}
          </div>
          {cur?.truncated && <div className="fp-empty">（子文件夹过多，仅显示前 500 个）</div>}

          <input
            className="fp-name"
            placeholder="项目名称（可选，默认取文件夹名）"
            value={name}
            onChange={e => setName(e.target.value)}
          />
        </div>
        <div className="modal-foot">
          {cur?.path
            ? <span className="fp-selected">已选：<code>{cur.name}</code></span>
            : <span className="fp-selected">请选择一个文件夹（单击进入）</span>}
          <div className="fp-foot-btns">
            <button className="btn" onClick={onClose}>取消</button>
            <button className="btn primary" disabled={!cur?.path || busy} onClick={bind}>
              {busy ? '绑定中…' : '绑定此文件夹'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
