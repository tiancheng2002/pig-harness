import { useEffect, useState } from 'react'
import { IconCheck, IconChevronDown } from './icons.jsx'

/**
 * 通用下拉选择：视觉与 composer 的模型切换菜单同款（surface-2 药丸 trigger + 圆角面板 + 选中勾勾）。
 * options: [{ value, label, icon?, hint? }]；direction: 'down' 向下弹（默认）/ 'up' 向上弹（贴视口底部时）；
 * fill: true 时 trigger 撑满父容器宽度（表单字段用）。
 */
export default function Select({ value, onChange, options, disabled, title, direction = 'down', fill = false }) {
  const [open, setOpen] = useState(false)
  const current = options.find(o => o.value === value)

  useEffect(() => {
    if (!open) return
    const onKey = e => { if (e.key === 'Escape') setOpen(false) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  return (
    <div className={`nsel ${fill ? 'nsel-fill' : ''}`}>
      <button
        type="button"
        className="model-trigger"
        disabled={disabled}
        title={title || current?.label}
        onClick={() => setOpen(o => !o)}
      >
        <span className="model-trigger-name">{current?.label || '请选择'}</span>
        <span className={`chev ${open ? 'open' : ''}`}><IconChevronDown size={11} /></span>
      </button>
      {open && (
        <>
          <div className="menu-overlay" onClick={() => setOpen(false)} />
          <div className={`model-pop nsel-pop-${direction}`}>
            {options.map(o => {
              const sel = o.value === value
              return (
                <button
                  type="button"
                  key={o.value}
                  className="model-opt"
                  title={o.hint}
                  onClick={() => { onChange(o.value); setOpen(false) }}
                >
                  <span className="nsel-opt-label">{o.icon}{o.label}</span>
                  {sel && <span className="opt-check"><IconCheck size={14} /></span>}
                </button>
              )
            })}
          </div>
        </>
      )}
    </div>
  )
}
