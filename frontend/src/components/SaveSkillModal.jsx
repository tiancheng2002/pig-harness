import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { saveSkillFromTrace } from '../api.js'
import { IconLayers } from './icons.jsx'

/**
 * 存为技能弹窗：两步式——先起名生成草稿（名称可留空，由模型结合对话内容生成），
 * 成功后展示生成的描述/触发词，可直接跳转技能库启用。替代原先的 window.prompt。
 */
export default function SaveSkillModal({ traceId, taskPrompt, onClose }) {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null) // 生成的技能详情

  async function generate() {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      const detail = await saveSkillFromTrace(traceId, name.trim() || null)
      setResult(detail)
      window.dispatchEvent(new Event('skills-changed'))
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  function onKeyDown(e) {
    if (e.key === 'Escape') onClose()
    if (e.key === 'Enter' && result == null) {
      e.preventDefault()
      generate()
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose} onKeyDown={onKeyDown}>
      <div className="modal save-skill-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <span className="ssm-title"><IconLayers size={14} /> {result ? '草稿技能已生成' : '存为技能'}</span>
          <button className="icon-btn" onClick={onClose}>✕</button>
        </div>

        {!result && (
          <>
            <div className="modal-body">
              <div className="ssm-intro">
                将把本轮对话的执行过程抽象成可复用的草稿技能。名称留空时，会结合对话内容自动起名，
                描述与触发词也会一并生成。
              </div>
              {taskPrompt && <div className="ssm-task">本轮任务：{taskPrompt.slice(0, 60)}{taskPrompt.length > 60 ? '…' : ''}</div>}
              <label className="ssm-label">技能名称（可选）</label>
              <input
                className="ssm-name"
                placeholder="英文小写连字符，如 weekly-report；留空自动生成"
                value={name}
                autoFocus
                onChange={e => setName(e.target.value)}
              />
              {error && <div className="ssm-err">{error}</div>}
            </div>
            <div className="modal-foot">
              <span className="ssm-note">草稿默认禁用，不会影响自动路由</span>
              <div className="fp-foot-btns">
                <button className="btn" onClick={onClose}>取消</button>
                <button className="btn primary" disabled={busy} onClick={generate}>
                  {busy ? '正在生成…' : '生成草稿技能'}
                </button>
              </div>
            </div>
          </>
        )}

        {result && (
          <>
            <div className="modal-body">
              <div className="ssm-result">
                <div className="ssm-row">
                  <span className="ssm-key">名称</span>
                  <code className="ssm-val">/{result.name}</code>
                </div>
                <div className="ssm-row">
                  <span className="ssm-key">描述</span>
                  <span className="ssm-val">{result.description || '-'}</span>
                </div>
                <div className="ssm-row">
                  <span className="ssm-key">触发词</span>
                  <span className="ssm-val ssm-triggers">
                    {(result.triggers || []).map((t, i) => <span key={i} className="ssm-trigger">{t}</span>)}
                    {(result.triggers || []).length === 0 && '-'}
                  </span>
                </div>
                {result.whenToUse && (
                  <div className="ssm-row">
                    <span className="ssm-key">何时使用</span>
                    <span className="ssm-val">{result.whenToUse}</span>
                  </div>
                )}
              </div>
              <div className="ssm-tip">可在技能库中查看步骤、编辑描述，确认无误后启用，即可被自动路由命中。</div>
            </div>
            <div className="modal-foot">
              <div className="fp-foot-btns">
                <button className="btn" onClick={onClose}>完成</button>
                <button className="btn primary" onClick={() => navigate('/skills')}>前往技能库启用</button>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
