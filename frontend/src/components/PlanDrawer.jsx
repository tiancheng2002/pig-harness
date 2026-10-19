import { useEffect } from 'react'
import Md from './Md.jsx'
import { IconBook } from './icons.jsx'

/**
 * 计划文档抽屉：工作区右侧挤压式展开（与文件抽屉同一壳样式），渲染计划模式产出的完整计划（Markdown）。
 * 由对话中「计划」卡片的「查看完整计划」按钮打开；Esc 或关闭按钮收起。
 */
export default function PlanDrawer({ text, onClose }) {
  useEffect(() => {
    const onKey = e => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <aside className="drawer">
      <div className="drawer-head">
        <span className="drawer-file-icon"><IconBook size={16} /></span>
        <div className="drawer-title">
          <span className="drawer-name">实施计划</span>
          <span className="drawer-sub">计划模式产出 · 未改动任何文件</span>
        </div>
        <div className="drawer-actions">
          <button className="drawer-btn" onClick={onClose} title="关闭（Esc）">✕ 关闭</button>
        </div>
      </div>
      <div className="drawer-body plan-drawer-body">
        <Md text={text || ''} />
      </div>
    </aside>
  )
}
