import { confirmDialog } from '../components/ConfirmDialog.jsx'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  fetchSkills, createSkill, updateSkill, deleteSkill, reindexSkill,
  fetchDrafts, validateDraft, commitDraft, rejectDraft, deleteDraft, fetchEvolution
} from '../api.js'
import { IconLayers } from '../components/icons.jsx'

const DRAFT_STATUS = {
  pending: { label: '待校验', cls: '' },
  validating: { label: '校验中', cls: 'acc' },
  validated: { label: '已通过', cls: 'ok' },
  rejected: { label: '已拒绝', cls: 'err' },
  committed: { label: '已入库', cls: 'ok sys' }
}

/**
 * 技能库页面：「技能 | 进化」两个 Tab。
 * 技能 = 系统/个人两区卡片列表（原 M2 能力 + P0 复用次数）；
 * 进化 = 自动合成流水线（草稿管理 + 统计卡 + 知识周增长 + 复用 TOP）。
 */
export default function Skills() {
  const [tab, setTab] = useState(() => localStorage.getItem('harness.skills.tab') || 'skills')
  const [skills, setSkills] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [creating, setCreating] = useState(false)
  const [newContent, setNewContent] = useState('')
  const [detailId, setDetailId] = useState(null)
  const [importing, setImporting] = useState(false)
  const fileRef = useRef(null)
  const detailRef = useRef(null)
  const navigate = useNavigate()

  // 进化 Tab 状态
  const [evo, setEvo] = useState(null)
  const [drafts, setDrafts] = useState([])
  const [evoLoading, setEvoLoading] = useState(false)
  const [evoError, setEvoError] = useState(null)
  const [expandedDraft, setExpandedDraft] = useState(null)
  const [validatingId, setValidatingId] = useState(null)

  function pickTab(id) {
    setTab(id)
    localStorage.setItem('harness.skills.tab', id)
  }

  async function refresh() {
    setError(null)
    try {
      const list = await fetchSkills()
      setSkills(list)
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  async function refreshEvolution() {
    setEvoError(null)
    setEvoLoading(true)
    try {
      const [stats, ds] = await Promise.all([fetchEvolution(), fetchDrafts()])
      setEvo(stats)
      setDrafts(ds)
    } catch (e) {
      setEvoError(e.message)
    } finally {
      setEvoLoading(false)
    }
  }

  useEffect(() => {
    refresh()
    if ((localStorage.getItem('harness.skills.tab') || 'skills') === 'evo') refreshEvolution()
    const onChange = () => {
      refresh()
      if ((localStorage.getItem('harness.skills.tab') || 'skills') === 'evo') refreshEvolution()
    }
    window.addEventListener('skills-changed', onChange)
    return () => window.removeEventListener('skills-changed', onChange)
  }, [])

  // 切到进化 Tab 时按需加载
  useEffect(() => {
    if (tab === 'evo' && !evo && !evoLoading) refreshEvolution()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab])

  // 详情面板位于列表下方（三列网格布局）：打开时滚动定位到面板
  useEffect(() => {
    if (detailId && detailRef.current) {
      detailRef.current.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    }
  }, [detailId])

  async function toggleEnabled(s) {
    try {
      await updateSkill(s.id, { enabled: !s.enabled })
      refresh()
    } catch (e) {
      window.alert('更新失败：' + e.message)
    }
  }

  async function remove(s) {
    if (!(await confirmDialog({
      title: '删除技能',
      message: `确定删除技能「${s.name || s.id}」？该操作不可恢复。`,
      confirmText: '删除',
      danger: true
    }))) return
    if (await deleteSkill(s.id)) {
      if (detailId === s.id) setDetailId(null)
      refresh()
    } else {
      window.alert('删除失败')
    }
  }

  async function submitNew() {
    if (!newContent.trim()) return
    try {
      await createSkill(null, newContent)
      setNewContent('')
      setCreating(false)
      refresh()
    } catch (e) {
      window.alert('创建失败：' + e.message)
    }
  }

  /** 导入 .md 文件：读文件内容直接创建个人技能（名称取文件名）。 */
  async function onImportFile(e) {
    const file = e.target.files && e.target.files[0]
    e.target.value = ''
    if (!file) return
    if (!/\.(md|markdown)$/i.test(file.name)) {
      window.alert('请选择 .md / .markdown 文件')
      return
    }
    if (file.size > 512 * 1024) {
      window.alert('文件过大（超过 512KB），请检查内容')
      return
    }
    setImporting(true)
    try {
      const content = await file.text()
      const baseName = file.name.replace(/\.(md|markdown)$/i, '')
      await createSkill(baseName, content)
      refresh()
    } catch (err) {
      window.alert('导入失败：' + err.message)
    } finally {
      setImporting(false)
    }
  }

  async function reindex(s) {
    if (await reindexSkill(s.id)) {
      window.alert('索引已重建')
    } else {
      window.alert('重建失败')
    }
  }

  // ---- 进化：草稿操作 ----

  async function doValidate(d) {
    setValidatingId(d.id)
    try {
      const after = await validateDraft(d.id)
      await refreshEvolution()
      const status = DRAFT_STATUS[after.status]?.label || after.status
      if (after.status === 'validated') {
        window.alert(`草稿「${d.name}」校验通过，可提交入库`)
      } else if (after.status === 'rejected') {
        const issues = after.report?.issues || []
        window.alert(`草稿「${d.name}」校验未通过（${status}）：\n${issues.slice(0, 4).join('\n')}`)
      } else {
        window.alert(`草稿状态：${status}`)
      }
    } catch (e) {
      window.alert('校验失败：' + e.message)
    } finally {
      setValidatingId(null)
    }
  }

  async function doCommit(d) {
    if (!(await confirmDialog({
      title: '提交技能入库',
      message: `把草稿「${d.name}」写入技能库并启用？入库后将参与意图路由（自动复用）。`,
      confirmText: '提交入库'
    }))) return
    try {
      const r = await commitDraft(d.id)
      window.alert(`已入库为技能「${r.skillName}」`)
      await refreshEvolution()
      refresh()
    } catch (e) {
      window.alert('提交失败：' + e.message)
    }
  }

  async function doReject(d) {
    if (!(await confirmDialog({
      title: '拒绝草稿',
      message: `确定拒绝草稿「${d.name}」？拒绝后不再参与提交。`,
      confirmText: '拒绝',
      danger: true
    }))) return
    if (await rejectDraft(d.id)) refreshEvolution()
    else window.alert('操作失败')
  }

  async function doDeleteDraft(d) {
    if (!(await confirmDialog({
      title: '删除草稿记录',
      message: `删除草稿「${d.name}」的记录？不影响已入库的技能。`,
      confirmText: '删除',
      danger: true
    }))) return
    if (await deleteDraft(d.id)) {
      if (expandedDraft === d.id) setExpandedDraft(null)
      refreshEvolution()
    } else {
      window.alert('删除失败')
    }
  }

  const systemSkills = skills.filter(s => s.origin === 'system')
  const userSkills = skills.filter(s => s.origin !== 'system')
  const detail = skills.find(s => s.id === detailId)

  function renderCard(s) {
    return (
      <div
        key={s.id}
        className={`skill-item ${detailId === s.id ? 'active' : ''} ${s.enabled ? '' : 'disabled'}`}
        onClick={() => setDetailId(s.id)}
      >
        <div className="skill-main">
          <div className="skill-name">
            {s.origin === 'system' && <span className="badge sys">系统</span>}
            {s.synth && <span className="badge tip" data-tip="由自进化流水线自动合成">合成</span>}
            {s.mode === 'prompt'
              ? <span className="badge tip" data-tip="散文指南：注入 Agent 由 AI 按说明执行">提示词</span>
              : <span className="badge tip" data-tip="步骤脚本：按 N. tool(...) 顺序执行">步骤</span>}
            {s.name || s.id}
          </div>
          <div className="skill-meta">
            {s.enabled ? <span className="badge ok">已启用</span> : <span className="badge">已禁用</span>}
            <span>v{s.version}</span>
            {s.hitCount > 0 && (
              <span className={`${s.failCount > 0 ? 'evo-hit warn' : 'evo-hit'} tip`}
                data-tip={s.failCount > 0 ? `命中 ${s.hitCount} 次，其中失败 ${s.failCount} 次` : `被路由/显式命中 ${s.hitCount} 次`}>
                复用 {s.hitCount} 次{s.failCount > 0 ? ` · 失败 ${s.failCount}` : ''}
              </span>
            )}
            {s.triggers && s.triggers.length > 0 && <span>触发词: {s.triggers.slice(0, 3).join(', ')}</span>}
          </div>
          <div className="skill-desc">{s.description}</div>
        </div>
        <div className="skill-actions" onClick={e => e.stopPropagation()}>
          <button className="icon-btn tip" data-tip={s.enabled ? '禁用' : '启用'} onClick={() => toggleEnabled(s)}>
            {s.enabled ? '⏸' : '▶'}
          </button>
          <button className="icon-btn tip" data-tip="重建索引" onClick={() => reindex(s)}>↻</button>
          {s.origin !== 'system' && (
            <button className="icon-btn danger tip" data-tip="删除" onClick={() => remove(s)}>🗑</button>
          )}
        </div>
      </div>
    )
  }

  function renderReport(report) {
    if (!report) return <div className="hint">（尚未校验）</div>
    const checks = [
      ['executesWithoutError', '步骤可完整执行'],
      ['producesExpectedFiles', '按预期产出文件'],
      ['outputStructurallyValid', '产出结构合法'],
      ['noDangerousOps', '无危险操作'],
      ['parameterizedNotHardcoded', '无写死绝对路径']
    ]
    return (
      <div className="evo-report">
        <div className="evo-checks">
          {checks.map(([k, label]) => (
            <span key={k} className={`evo-check ${report[k] ? 'pass' : 'fail'}`}>
              {report[k] ? '✓' : '✗'} {label}
            </span>
          ))}
        </div>
        {report.producedFiles?.length > 0 && (
          <div className="evo-report-line">产出：{report.producedFiles.join('、')}</div>
        )}
        {report.durationMs != null && (
          <div className="evo-report-line">耗时 {report.durationMs} ms</div>
        )}
        {report.issues?.length > 0 && (
          <pre className="evo-issues">{report.issues.join('\n')}</pre>
        )}
      </div>
    )
  }

  function renderDraft(d) {
    const st = DRAFT_STATUS[d.status] || { label: d.status, cls: '' }
    const open = expandedDraft === d.id
    return (
      <div key={d.id} className={`skill-item draft-item ${open ? 'active' : ''}`} onClick={() => setExpandedDraft(open ? null : d.id)}>
        <div className="skill-main">
          <div className="skill-name">
            <span className={`badge ${st.cls}`}>{st.label}</span>
            {d.name || `draft-${d.id}`}
          </div>
          <div className="skill-meta">
            <span>簇 {d.clusterId ?? '-'}</span>
            <span>校验输入: {d.sampleTestInput || '-'}</span>
            <span>{(d.updatedAt || '').slice(0, 16).replace('T', ' ')}</span>
          </div>
          <div className="skill-desc">{(d.content || '').split('\n').find(l => l.startsWith('description:'))?.replace('description: ', '') || ''}</div>
        </div>
        <div className="skill-actions" onClick={e => e.stopPropagation()}>
          {d.status !== 'committed' && (
            <button className="icon-btn tip" data-tip="重新校验（隔离沙箱试跑）" disabled={validatingId === d.id}
              onClick={() => doValidate(d)}>
              {validatingId === d.id ? '…' : '⟳'}
            </button>
          )}
          {d.status === 'validated' && (
            <button className="icon-btn tip" data-tip="提交入库并启用" onClick={() => doCommit(d)}>✓</button>
          )}
          {(d.status === 'pending' || d.status === 'validating' || d.status === 'validated') && (
            <button className="icon-btn danger tip" data-tip="拒绝" onClick={() => doReject(d)}>✕</button>
          )}
          <button className="icon-btn danger tip" data-tip="删除记录" onClick={() => doDeleteDraft(d)}>🗑</button>
        </div>
        {open && (
          <div className="draft-detail" onClick={e => e.stopPropagation()}>
            <div className="detail-label">校验报告（隔离沙箱试跑）</div>
            {renderReport(d.report)}
            <div className="detail-label">SKILL.md</div>
            <pre className="skill-source">{d.content}</pre>
          </div>
        )}
      </div>
    )
  }

  function renderEvolution() {
    if (evoLoading && !evo) return <div className="hint">加载中…</div>
    if (evoError) return <div className="event reflect">加载失败：{evoError}</div>
    if (!evo) return null
    const reuse = evo.reuse || {}
    const top = reuse.topSkills || []
    const maxHit = Math.max(1, ...top.map(t => t.hitCount || 0))
    const weekly = evo.knowledge?.weekly || []
    const maxWeek = Math.max(1, ...weekly.map(w => w.count || 0))
    const activeDrafts = drafts.filter(d => d.status !== 'committed').length
    return (
      <div className="evo-panel">
        <div className="knowledge-stats">
          <div className="stat-chip">
            <div className="stat-num">{reuse.totalHits || 0}</div>
            <div className="stat-label">技能复用次数</div>
          </div>
          <div className="stat-chip">
            <div className="stat-num">{reuse.hitRate != null ? `${Math.round(reuse.hitRate * 100)}%` : '-'}</div>
            <div className="stat-label">任务复用率</div>
          </div>
          <div className="stat-chip">
            <div className="stat-num">{activeDrafts}</div>
            <div className="stat-label">待处理草稿</div>
          </div>
          <div className="stat-chip">
            <div className="stat-num">{evo.knowledge?.total ?? 0}</div>
            <div className="stat-label">知识条目</div>
          </div>
          <div className="stat-chip">
            <div className="stat-num">{evo.savedSteps?.available ? `~${evo.savedSteps.estimateSteps}` : '-'}</div>
            <div className="stat-label">预计节省工具步</div>
          </div>
        </div>

        <div className="evo-grid">
          <div className="evo-card">
            <div className="detail-label">知识周增长（近 8 周）</div>
            {weekly.length === 0
              ? <div className="hint">暂无知识沉淀</div>
              : (
                <div className="evo-chart">
                  {weekly.map(w => (
                    <div key={w.start} className="evo-chart-col tip" data-tip={`${w.start} ~ ${w.end}：${w.count} 条`}>
                      <div className="evo-chart-num">{w.count || ''}</div>
                      <div className="evo-chart-bar" style={{ height: `${Math.round((w.count / maxWeek) * 64) + 2}px` }} />
                      <div className="evo-chart-label">{w.start.slice(5)}</div>
                    </div>
                  ))}
                </div>
              )}
          </div>

          <div className="evo-card">
            <div className="detail-label">技能复用 TOP5</div>
            {top.length === 0 || top.every(t => !t.hitCount)
              ? <div className="hint">还没有技能被复用：技能命中后这里会显示排行</div>
              : top.map(t => (
                <div key={t.skillId} className="evo-top-row">
                  <span className="tip evo-top-name" data-tip={t.name || t.skillId}>{t.name || t.skillId}</span>
                  <div className="evo-top-bar">
                    <div style={{ width: `${Math.round(((t.hitCount || 0) / maxHit) * 100)}%` }} />
                  </div>
                  <span className="evo-top-num">{t.hitCount}</span>
                </div>
              ))}
          </div>
        </div>

        {evo.savedSteps?.available && (
          <div className="hint evo-caliber">
            节省估算口径：{evo.savedSteps.caliber}（基线 {evo.savedSteps.baselineSteps} 步/任务 × 复用 {reuse.totalHits} 次，样本 {evo.savedSteps.sampleCount} 个任务）
          </div>
        )}

        <div className="skill-sec-head gap">
          <span className="skill-sec-icon"><IconLayers size={13} /></span>
          合成草稿（自动合成流水线）
          <span className="skill-sec-count">{drafts.length}</span>
          <span className="skill-sec-tip">同类任务成功 ≥3 次自动合成 → 隔离沙箱校验 → 人工确认入库</span>
        </div>
        {drafts.length === 0 && !evoLoading && (
          <div className="hint">
            还没有合成草稿：多完成几次同类任务（不命中技能），系统会自动合成技能草稿并在沙箱中验证。
          </div>
        )}
        {drafts.map(renderDraft)}
      </div>
    )
  }

  return (
    <div className="skills-page">
      <div className="skills-header">
        <h1>技能库</h1>
        <div className="skills-header-btns">
          {tab === 'evo' && (
            <button className="btn" onClick={refreshEvolution}>↻ 刷新</button>
          )}
          {tab === 'skills' && (
            <>
              <button className="btn" disabled={importing} onClick={() => fileRef.current?.click()}>
                {importing ? '导入中…' : '⬆ 导入 .md'}
              </button>
              <button className="btn primary" onClick={() => setCreating(true)}>＋ 新建技能</button>
              <input
                ref={fileRef}
                type="file"
                accept=".md,.markdown,text/markdown"
                style={{ display: 'none' }}
                onChange={onImportFile}
              />
            </>
          )}
        </div>
      </div>

      <div className="filter-tabs skills-tabs">
        <button className={`filter-tab ${tab === 'skills' ? 'active' : ''}`} onClick={() => pickTab('skills')}>技能</button>
        <button className={`filter-tab ${tab === 'evo' ? 'active' : ''}`} onClick={() => pickTab('evo')}>进化</button>
      </div>

      {creating && (
        <div className="skill-form">
          <h3>粘贴 SKILL.md 内容</h3>
          <textarea
            value={newContent}
            onChange={e => setNewContent(e.target.value)}
            placeholder={'---\nname: create_login_page\ndescription: ...\ntriggers:\n  - ...\nwhen_to_use: ...\n---\n1. fileWrite(path="{{page}}.html", content="...")'}
            rows={12}
          />
          <div className="form-actions">
            <button className="btn" onClick={() => { setNewContent(''); setCreating(false) }}>取消</button>
            <button className="btn primary" onClick={submitNew} disabled={!newContent.trim()}>创建</button>
          </div>
        </div>
      )}

      {tab === 'skills' && (
        <>
          {loading && <div className="hint">加载中…</div>}
          {error && <div className="event reflect">加载失败：{error}</div>}

          <div className="skills-layout">
            <div className="skills-list">
              <div className="skill-sec-head">
                <span className="skill-sec-icon"><IconLayers size={13} /></span>
                系统技能
                <span className="skill-sec-count">{systemSkills.length}</span>
                <span className="skill-sec-tip">内置技能，可禁用；删除后重启会自动恢复</span>
              </div>
              {systemSkills.length === 0 && !loading && <div className="hint">系统技能为空（重启服务可重新播种）</div>}
              {systemSkills.map(renderCard)}

              <div className="skill-sec-head gap">
                <span className="skill-sec-icon"><IconLayers size={13} /></span>
                个人技能
                <span className="skill-sec-count">{userSkills.length}</span>
                <span className="skill-sec-tip">新建 / 轨迹合成 / 导入的技能</span>
              </div>
              {userSkills.length === 0 && !loading && (
                <div className="hint">还没有个人技能：对话结束后点「存为技能」，或右上角新建 / 导入 .md</div>
              )}
              {userSkills.map(renderCard)}
            </div>

            {detail && (
              <div className="skill-detail" ref={detailRef}>
                <div className="detail-header">
                  <h3>{detail.name}</h3>
                  <button className="icon-btn" onClick={() => setDetailId(null)}>✕</button>
                </div>
                <div className="detail-meta">
                  {detail.origin === 'system' && <span className="badge sys">系统</span>}
                  <span className="badge">{detail.mode === 'prompt' ? '提示词技能' : '步骤技能'}</span>
                  <span className={detail.enabled ? 'ok' : ''}>{detail.enabled ? '已启用' : '已禁用'}</span>
                  <span>版本 {detail.version}</span>
                  {detail.hitCount > 0 && <span>复用 {detail.hitCount} 次</span>}
                  <span>更新于 {detail.updatedAt ? detail.updatedAt.slice(0, 16).replace('T', ' ') : '-'}</span>
                </div>
                <div className="detail-section">
                  <div className="detail-label">描述</div>
                  <div>{detail.description || '-'}</div>
                </div>
                <div className="detail-section">
                  <div className="detail-label">触发词</div>
                  <div>{detail.triggers?.join(', ') || '-'}</div>
                </div>
                <div className="detail-section">
                  <div className="detail-label">SKILL.md</div>
                  <pre className="skill-source">{detail.content}</pre>
                </div>
              </div>
            )}
          </div>
        </>
      )}

      {tab === 'evo' && renderEvolution()}

      <div className="skills-footer">
        <button className="btn" onClick={() => navigate('/chat')}>返回对话</button>
      </div>
    </div>
  )
}
