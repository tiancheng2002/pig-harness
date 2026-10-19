import { useEffect, useState } from 'react'

let push = null // ConfirmHost 挂载后的弹出函数（App 全局唯一实例）

/**
 * 程序化确认框：await confirmDialog({ title, message, confirmText, danger }) → boolean。
 * 替代 window.confirm 原生弹窗（主题内样式、Esc/点遮罩=取消）。Host 未挂载时兜底原生 confirm。
 */
export function confirmDialog(opts = {}) {
  if (!push) return Promise.resolve(window.confirm(opts.message || '确认执行该操作？'))
  return push(opts)
}

/**
 * 确认弹窗宿主：App.jsx 挂载一次；各页面经 confirmDialog() 程序化调用。
 * danger=true 时确认按钮为红色警示态（删除类操作）。
 */
export default function ConfirmHost() {
  const [state, setState] = useState(null) // {title, message, confirmText, danger, resolve}

  useEffect(() => {
    push = opts => new Promise(resolve => setState({ ...opts, resolve }))
    return () => { push = null }
  }, [])

  function finish(v) {
    state?.resolve(v)
    setState(null)
  }

  useEffect(() => {
    if (!state) return
    const onKey = e => { if (e.key === 'Escape') finish(false) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state])

  if (!state) return null
  return (
    <div className="modal-overlay" onClick={() => finish(false)}>
      <div className="modal confirm-modal" onClick={e => e.stopPropagation()}>
        <div className="confirm-title">{state.title || '确认操作'}</div>
        <div className="confirm-message">{state.message}</div>
        <div className="form-actions">
          <button className="btn" onClick={() => finish(false)}>取消</button>
          <button className={`btn ${state.danger ? 'danger' : 'primary'}`}
                  onClick={() => finish(true)}>
            {state.confirmText || '确认'}
          </button>
        </div>
      </div>
    </div>
  )
}
