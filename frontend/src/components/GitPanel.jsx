import { useCallback, useEffect, useState } from 'react'
import { fetchGitDiff, fetchGitStatus, gitCommit, gitPush } from '../api.js'
import { FileIcon, IconGitBranch } from './icons.jsx'

/**
 * 项目 Git 变更悬浮面板（右侧）：
 * 当前分支 / ahead-behind / 变更文件清单（+增删行数）/ 单文件 diff / 提交 / 推送。
 * 每轮对话结束（conversations-changed 事件）自动刷新。
 */
export default function GitPanel({ projectId, projectName }) {
  const [open, setOpen] = useState(false)
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [expanded, setExpanded] = useState(null)   // 展开 diff 的文件路径
  const [diffText, setDiffText] = useState('')
  const [diffLoading, setDiffLoading] = useState(false)
  const [commitMsg, setCommitMsg] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState(null)       // {ok, message}

  const refresh = useCallback(() => {
    if (!projectId) return
    setLoading(true)
    fetchGitStatus(projectId)
      .then(s => { setStatus(s); setError(null); setExpanded(null); setDiffText('') })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [projectId])

  useEffect(() => {
    refresh()
    const handler = () => refresh()
    window.addEventListener('conversations-changed', handler)
    return () => window.removeEventListener('conversations-changed', handler)
  }, [refresh])

  async function toggleFile(path) {
    if (expanded === path) {
      setExpanded(null)
      setDiffText('')
      return
    }
    setExpanded(path)
    setDiffText('')
    setDiffLoading(true)
    try {
      const d = await fetchGitDiff(projectId, path)
      setDiffText(d.diff || '（无变更）')
    } catch (e) {
      setDiffText('（diff 获取失败: ' + e.message + '）')
    } finally {
      setDiffLoading(false)
    }
  }

  async function doCommit() {
    if (!commitMsg.trim() || busy) return
    setBusy(true)
    setNotice(null)
    try {
      const r = await gitCommit(projectId, commitMsg.trim())
      setNotice(r)
      if (r.ok) setCommitMsg('')
      refresh()
    } catch (e) {
      setNotice({ ok: false, message: e.message })
    } finally {
      setBusy(false)
    }
  }

  async function doPush() {
    if (busy) return
    setBusy(true)
    setNotice(null)
    try {
      const r = await gitPush(projectId)
      setNotice(r)
      refresh()
    } catch (e) {
      setNotice({ ok: false, message: e.message })
    } finally {
      setBusy(false)
    }
  }

  const s = status
  const hasChanges = s?.repo && s.changes?.length > 0

  return (
    <>
      {/* 悬浮入口：显示分支 + 变更概览 */}
      {!open && projectId && (
        <button
          className={`git-fab ${hasChanges ? 'dirty' : ''}`}
          onClick={() => { setOpen(true); refresh() }}
          title={s?.repo
            ? `分支 ${s.branch} · ${s.changes?.length || 0} 个文件变更（+${s.totalAdditions} −${s.totalDeletions}）`
            : (s?.error || '读取 Git 状态…')}
        >
          <span className="git-fab-icon"><IconGitBranch size={13} /></span>
          {loading && !s ? (
            <span className="git-fab-text">…</span>
          ) : s?.repo ? (
            <>
              <span className="git-fab-text">{s.branch}</span>
              {hasChanges && (
                <span className="git-fab-nums">
                  <em className="add">+{s.totalAdditions}</em>
                  <em className="del">−{s.totalDeletions}</em>
                </span>
              )}
            </>
          ) : (
            <span className="git-fab-text">Git</span>
          )}
        </button>
      )}

      {open && (
        <div className="git-panel">
          <div className="git-panel-head">
            <div className="git-panel-title">
              <span className="git-panel-icon"><IconGitBranch size={15} /></span>
              <div>
                <div className="git-branch-line">
                  <span className="git-branch">{s?.repo ? s.branch : '非 Git 仓库'}</span>
                  {s?.repo && (s.ahead > 0 || s.behind > 0) && (
                    <span className="git-ab">
                      {s.ahead > 0 && <em>↑{s.ahead}</em>}
                      {s.behind > 0 && <em>↓{s.behind}</em>}
                    </span>
                  )}
                </div>
                <div className="git-panel-sub" title={projectName}>{projectName}</div>
              </div>
            </div>
            <div className="git-panel-actions">
              <button className="icon-btn" title="刷新" onClick={refresh}>↻</button>
              <button className="icon-btn" title="收起" onClick={() => setOpen(false)}>✕</button>
            </div>
          </div>

          <div className="git-panel-body">
            {loading && !s && <div className="git-loading">读取 Git 状态…</div>}
            {error && <div className="git-error">⚠ {error}</div>}
            {s && !s.repo && (
              <div className="git-empty">
                <span style={{ fontSize: 30 }}>🌿</span>
                <span>{s.error || '该文件夹不是 Git 仓库'}</span>
                <span className="git-hint">在项目目录执行 git init 后即可使用提交 / 推送</span>
              </div>
            )}
            {s?.repo && (
              <>
                <div className="git-summary">
                  {hasChanges
                    ? <><b>{s.changes.length}</b> 个文件变更<em className="add">+{s.totalAdditions}</em><em className="del">−{s.totalDeletions}</em></>
                    : <span className="git-clean">✓ 工作区干净，没有未提交的改动</span>}
                </div>
                <div className="git-files">
                  {s.changes.map(c => (
                    <div key={c.path} className={`git-file ${expanded === c.path ? 'open' : ''}`}>
                      <div className="git-file-row" onClick={() => toggleFile(c.path)}
                           title={`${c.label || c.code} · ${c.path}`}>
                        <FileIcon name={c.path} size={15} />
                        <span className="git-path">{c.path}</span>
                        <span className="git-nums">
                          {c.additions > 0 && <em className="add">+{c.additions}</em>}
                          {c.deletions > 0 && <em className="del">−{c.deletions}</em>}
                        </span>
                      </div>
                      {expanded === c.path && (
                        <pre className="git-diff">
                          {diffLoading ? '加载 diff…' : diffText.split('\n').map((ln, i) => (
                            <span key={i} className={
                              ln.startsWith('+++') || ln.startsWith('---') ? 'l-meta'
                                : ln.startsWith('@@') ? 'l-hunk'
                                : ln.startsWith('+') ? 'l-add'
                                : ln.startsWith('-') ? 'l-del' : 'l-ctx'
                            }>{ln + '\n'}</span>
                          ))}
                        </pre>
                      )}
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>

          {s?.repo && (
            <div className="git-panel-foot">
              {notice && <div className={`git-notice ${notice.ok ? 'ok' : 'err'}`}>{notice.message}</div>}
              <textarea
                className="git-commit-msg"
                placeholder="提交信息，如：修复登录页校验逻辑"
                rows={2}
                value={commitMsg}
                onChange={e => setCommitMsg(e.target.value)}
              />
              <div className="git-foot-btns">
                <button className="btn primary" disabled={busy || !commitMsg.trim() || !hasChanges} onClick={doCommit}>
                  {busy ? '处理中…' : '✓ 提交'}
                </button>
                <button className="btn" disabled={busy} onClick={doPush} title="推送当前分支到远端">↑ 推送</button>
              </div>
            </div>
          )}
        </div>
      )}
    </>
  )
}
