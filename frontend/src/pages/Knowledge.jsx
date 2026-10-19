import { useEffect, useRef, useState } from 'react'
import {
  fetchKnowledge,
  createKnowledge,
  updateKnowledge,
  deleteKnowledge,
  extractKnowledge,
  fetchProjects
} from '../api.js'
import Select from '../components/Select.jsx'
import { confirmDialog } from '../components/ConfirmDialog.jsx'

const PRESET_CATEGORIES = ['用户偏好', '项目事实', '工作习惯', '其他']
const VIEWS = [['active', '已采用'], ['pending', '待审核'], ['archived', '已归档']]

/**
 * 知识页：Harness 的长期记忆（自进化）。
 * 自动提取先进入待审核；已启用知识按当前任务与项目检索后使用。
 */
export default function Knowledge() {
  const [data, setData] = useState(null)
  const [category, setCategory] = useState('全部')
  const [status, setStatus] = useState('active')
  const [projects, setProjects] = useState([])
  const [selectedProjects, setSelectedProjects] = useState({})
  const [search, setSearch] = useState('')
  const [selectedIds, setSelectedIds] = useState([])
  const [adding, setAdding] = useState(false)
  const [extracting, setExtracting] = useState(false)
  const [message, setMessage] = useState(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [editingId, setEditingId] = useState(null)
  const [editDraft, setEditDraft] = useState(null)
  const [draft, setDraft] = useState({ category: '用户偏好', key: '', content: '', projectId: '' })
  const requestId = useRef(0)

  function refresh(cat = category, state = status) {
    const current = ++requestId.current
    setLoading(true)
    setData(null)
    fetchKnowledge(cat, state)
      .then(result => { if (current === requestId.current) { setData(result); setLoadError(null) } })
      .catch(e => { if (current === requestId.current) setLoadError(e.message) })
      .finally(() => { if (current === requestId.current) setLoading(false) })
  }

  useEffect(() => {
    refresh(category, status)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [category, status])

  useEffect(() => {
    fetchProjects().then(setProjects).catch(() => setProjects([]))
  }, [])

  useEffect(() => setSelectedIds([]), [category, status, search])

  async function add() {
    if (!draft.key.trim() || !draft.content.trim()) return
    if (draft.category === '项目事实' && !draft.projectId) {
      setMessage('项目事实需要选择所属项目')
      return
    }
    try {
      await createKnowledge(draft)
      setDraft({ category: '用户偏好', key: '', content: '', projectId: '' })
      setAdding(false)
      setMessage('已添加到“已采用”')
      setStatus('active')
      setCategory('全部')
    } catch (e) {
      setMessage('保存失败：' + e.message)
    }
  }

  /** 收起手动添加表单并重置草稿（取消/成功后不残留内容）。 */
  function closeAdd() {
    setAdding(false)
    setDraft({ category: '用户偏好', key: '', content: '', projectId: '' })
  }

  /** 收起编辑表单并清空草稿（下次 ✎ 由 startEdit 重新预填）。 */
  function closeEdit() {
    setEditingId(null)
    setEditDraft(null)
  }

  async function extract() {
    setExtracting(true)
    setMessage(null)
    try {
      const r = await extractKnowledge()
      setMessage(r.added ? `已从最近会话提出 ${r.added} 条待审核知识` : '最近会话没有提取出新的候选')
      refresh(category, 'pending')
    } catch (e) {
      setMessage('提取失败：' + e.message)
    } finally {
      setExtracting(false)
    }
  }

  async function remove(k) {
    if (!(await confirmDialog({
      title: '删除记忆',
      message: `确定删除记忆「${k.key}」？该操作不可恢复。`,
      confirmText: '删除',
      danger: true
    }))) return
    try {
      if (!await deleteKnowledge(k.id)) throw new Error('删除失败')
      refresh()
    } catch (e) { setMessage(e.message) }
  }

  async function togglePin(k) {
    try {
      await updateKnowledge(k.id, { pinned: !k.pinned })
      refresh()
    } catch (e) { setMessage(e.message) }
  }

  async function changeStatus(k, nextStatus) {
    const projectId = k.projectId || selectedProjects[k.id]
    if (nextStatus === 'active' && k.category === '项目事实' && !projectId) {
      setMessage('请先为这条项目事实选择所属项目')
      return
    }
    try {
      await updateKnowledge(k.id, { status: nextStatus, ...(projectId ? { projectId } : {}) })
      refresh()
    } catch (e) { setMessage(e.message) }
  }

  async function ignoreSelected() {
    if (!selectedIds.length || !(await confirmDialog({
      title: '忽略选中候选',
      message: `将永久删除选中的 ${selectedIds.length} 条候选，无法恢复。`,
      confirmText: '删除候选',
      danger: true
    }))) return
    try {
      for (const id of selectedIds) {
        if (!await deleteKnowledge(id)) throw new Error(`候选 ${id} 删除失败`)
      }
      setSelectedIds([])
      refresh()
    } catch (e) { setMessage(e.message); refresh() }
  }

  async function ignore(k) {
    if (!(await confirmDialog({
      title: '忽略这条候选？',
      message: `「${k.key}」会从数据库中永久删除，无法恢复。`,
      confirmText: '忽略并删除',
      danger: true
    }))) return
    try {
      if (!await deleteKnowledge(k.id)) throw new Error('删除失败')
      refresh()
    } catch (e) { setMessage(e.message) }
  }

  function startEdit(k) {
    setEditingId(k.id)
    setEditDraft({ category: k.category, key: k.key, content: k.content, projectId: k.projectId || '' })
  }

  async function commitEdit() {
    if (!editDraft.key.trim() || !editDraft.content.trim()) return
    try {
      await updateKnowledge(editingId, editDraft)
      setEditingId(null)
      setEditDraft(null)
      refresh()
    } catch (e) { setMessage(e.message) }
  }

  const categories = ['全部', ...PRESET_CATEGORIES]
  const items = data?.items || []
  const visibleItems = items.filter(k => `${k.key} ${k.content}`.toLowerCase().includes(search.trim().toLowerCase()))
  const projectOptions = [{ value: '', label: '选择所属项目' }, ...projects.map(p => ({ value: p.id, label: p.name }))]
  const projectName = id => projects.find(p => p.id === id)?.name || '未知项目'

  return (
    <main className="page knowledge-page">
      <header className="knowledge-page-head">
        <div>
          <h1>知识</h1>
          <p>只保留对以后任务有用的信息。已采用的知识会按当前任务和项目少量引用。</p>
        </div>
      </header>

      <nav className="knowledge-view-tabs" aria-label="知识视图">
        {VIEWS.map(([value, label]) => (
          <button key={value} type="button" className={status === value ? 'active' : ''}
                  aria-current={status === value ? 'page' : undefined}
                  onClick={() => { setStatus(value); setCategory('全部'); setSearch(''); setMessage(null); closeAdd(); closeEdit() }}>
            {label}<span className="knowledge-tab-count">{data?.byStatus?.[value] ?? '—'}</span>
          </button>
        ))}
      </nav>

      <section className="knowledge-section-intro">
        <div>
          <h2>{status === 'active' ? '正在使用的知识' : status === 'pending' ? '审核候选知识' : '已归档的知识'}</h2>
          <p>{status === 'active'
            ? '这里的内容可能用于后续任务。过时的知识可以归档，暂时不再引用。'
            : status === 'pending'
              ? '核对用户原话，只采用长期有效的偏好、习惯或项目约定；忽略会永久删除。'
              : '归档内容不会进入提示词。需要时可以移回待审核，重新确认后采用。'}</p>
        </div>
        {status === 'pending' && (
          <div className="knowledge-entry-actions">
            <button className="btn" onClick={() => setAdding(!adding)} aria-expanded={adding}>
              {adding ? '收起表单' : '＋ 手动添加'}
            </button>
            <button className="btn primary" onClick={extract} disabled={extracting}>
              {extracting ? '提取中…' : '从最近会话提取'}
            </button>
          </div>
        )}
      </section>
      {message && <div className="knowledge-notice" role="status">{message}</div>}

      {adding && (
        <div className="add-knowledge">
          <p className="knowledge-form-hint">手动添加的知识保存后会直接采用。</p>
          <div className="add-row">
            <div>
              <label className="form-label">分类</label>
              <Select fill value={draft.category}
                      onChange={v => setDraft({ ...draft, category: v })}
                      options={PRESET_CATEGORIES.map(c => ({ value: c, label: c }))} />
            </div>
            <div style={{ flex: 2 }}>
              <label className="form-label">标题</label>
              <input placeholder="如：偏好使用 Vue3" value={draft.key}
                     onChange={e => setDraft({ ...draft, key: e.target.value })} />
            </div>
          </div>
          {draft.category === '项目事实' && (
            <div>
              <label className="form-label">所属项目</label>
              <Select fill value={draft.projectId}
                      onChange={v => setDraft({ ...draft, projectId: v })} options={projectOptions} />
            </div>
          )}
          <div>
            <label className="form-label">内容</label>
            <textarea placeholder="具体描述这条记忆…" value={draft.content}
                      onChange={e => setDraft({ ...draft, content: e.target.value })} />
          </div>
          <div className="form-actions">
            <button className="btn" onClick={closeAdd}>取消</button>
            <button className="btn primary" onClick={add}
                    disabled={!draft.key.trim() || !draft.content.trim()}>保存</button>
          </div>
        </div>
      )}

      <div className="filter-tabs knowledge-category-tabs" aria-label="知识分类">
        {categories.map(c => (
          <button key={c}
                  className={`filter-tab ${category === c ? 'active' : ''}`}
                  onClick={() => setCategory(c)}>
            {c}
          </button>
        ))}
      </div>

      <div className="knowledge-review-toolbar">
        <input aria-label="搜索知识" placeholder="搜索标题或内容" value={search} onChange={e => setSearch(e.target.value)} />
        {status === 'pending' && visibleItems.length > 0 && (
          <>
            <button className="btn sm" onClick={() => setSelectedIds(
              selectedIds.length === visibleItems.length ? [] : visibleItems.map(k => k.id))}>
              {selectedIds.length === visibleItems.length ? '取消全选' : '全选当前结果'}
            </button>
            {selectedIds.length > 0 && <button className="btn sm danger" onClick={ignoreSelected}>忽略并删除 {selectedIds.length} 条</button>}
          </>
        )}
      </div>

      {loadError && <div className="knowledge-notice error" role="alert">{loadError} <button className="btn sm" onClick={() => refresh()}>重试</button></div>}
      <div className={`knowledge-grid ${status === 'pending' ? 'review-list' : ''}`}>
        {loading && !data && <div className="knowledge-empty">正在读取知识…</div>}
        {!loading && !loadError && visibleItems.length === 0 && (
          <div className="knowledge-empty">
            {search ? '没有匹配的知识。试试其他关键词。' : status === 'active'
              ? '还没有已采用的知识。去“待审核”核对候选，或手动添加。'
              : status === 'pending' ? '待审核已清空。新提取的候选会出现在这里。' : '还没有归档知识。'}
          </div>
        )}
        {visibleItems.map(k => (
          <div key={k.id} className={`knowledge-card ${k.pinned ? 'pinned' : ''}`}>
            {editingId === k.id ? (
              // 编辑态：结构与「手动添加」同款（分类下拉 + 标题 + 内容，带 form-label），样式经 .kc-edit 对齐
              <div className="kc-edit">
                <div className="add-row">
                  <div>
                    <label className="form-label">分类</label>
                    <Select fill value={editDraft.category}
                            onChange={v => setEditDraft({ ...editDraft, category: v })}
                            options={PRESET_CATEGORIES.map(c => ({ value: c, label: c }))} />
                  </div>
                  <div style={{ flex: 2 }}>
                    <label className="form-label">标题</label>
                    <input value={editDraft.key}
                           onChange={e => setEditDraft({ ...editDraft, key: e.target.value })} />
                  </div>
                </div>
                {editDraft.category === '项目事实' && (
                  <div>
                    <label className="form-label">所属项目</label>
                    <Select fill value={editDraft.projectId}
                            onChange={v => setEditDraft({ ...editDraft, projectId: v })} options={projectOptions} />
                  </div>
                )}
                <div>
                  <label className="form-label">内容</label>
                  <textarea value={editDraft.content} rows={3}
                            onChange={e => setEditDraft({ ...editDraft, content: e.target.value })} />
                </div>
                <div className="form-actions">
                  <button className="btn sm" onClick={closeEdit}>取消</button>
                  <button className="btn sm primary" onClick={commitEdit}>保存</button>
                </div>
              </div>
            ) : (
              <>
                <div className="kc-head">
                  {status === 'pending' && <input type="checkbox" aria-label={`选择 ${k.key}`}
                    checked={selectedIds.includes(k.id)} onChange={e => setSelectedIds(ids =>
                      e.target.checked ? [...ids, k.id] : ids.filter(id => id !== k.id))} />}
                  <span className={`kc-category ${k.category}`}>{k.category}</span>
                  <span className="kc-key" title={k.key}>{k.key}</span>
                </div>
                <div className="kc-content">{k.content}</div>
                {k.projectId && <div className="kc-evidence">所属项目：{projectName(k.projectId)}</div>}
                {status === 'pending' && (
                  <div className="kc-evidence">{k.evidence ? `用户原文：${k.evidence}` : '旧数据无原文证据，请核对后再采用。'}</div>
                )}
                {status === 'pending' && k.category === '项目事实' && !k.projectId && (
                  <Select fill value={selectedProjects[k.id] || ''}
                          onChange={v => setSelectedProjects({ ...selectedProjects, [k.id]: v })}
                          options={projectOptions} />
                )}
                {status === 'pending' && (
                  <div className="kc-review-actions">
                    <button className="btn sm primary" onClick={() => changeStatus(k, 'active')}>采用</button>
                    <button className="btn sm" onClick={() => startEdit(k)}>先编辑</button>
                    <button className="btn sm danger" onClick={() => ignore(k)}>忽略</button>
                  </div>
                )}
                <div className="kc-foot">
                  <span className={`kc-source ${k.source}`}>
                    {k.source === 'auto' ? '自动提取' : '手动添加'}
                  </span>
                  <span className="kc-time">{formatDate(k.updatedAt)}</span>
                  <div className="kc-actions">
                    {status === 'active' && <button className="icon-btn" title={k.pinned ? '取消置顶' : '置顶（优先参考）'}
                            onClick={() => togglePin(k)}>{k.pinned ? '★' : '☆'}</button>}
                    {status === 'active' && <button className="icon-btn" title="归档（停止使用）"
                            onClick={() => changeStatus(k, 'archived')}>◇</button>}
                    {status === 'archived' && (
                      <button className="icon-btn" title="移回待审核" onClick={() => changeStatus(k, 'pending')}>↩</button>
                    )}
                    {status !== 'pending' && <button className="icon-btn" title="编辑" onClick={() => startEdit(k)}>✎</button>}
                    {status !== 'pending' && <button className="icon-btn danger" title="删除" onClick={() => remove(k)}>✕</button>}
                  </div>
                </div>
              </>
            )}
          </div>
        ))}
      </div>
    </main>
  )
}

function formatDate(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d)) return iso.slice(0, 10)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
