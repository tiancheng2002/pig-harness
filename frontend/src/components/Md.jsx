import { useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

/** pre 代码块 + 右上角复制按钮（避免手动从页头拖选到页尾）。 */
function CopyPre({ children, ...rest }) {
  const ref = useRef(null)
  const [copied, setCopied] = useState(false)

  async function copy() {
    const text = ref.current?.innerText || ''
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      // 剪贴板 API 不可用时的兜底
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      try { document.execCommand('copy') } catch { /* 忽略 */ }
      document.body.removeChild(ta)
    }
    setCopied(true)
    setTimeout(() => setCopied(false), 1600)
  }

  return (
    <div className="pre-block">
      <button type="button" className={`pre-copy ${copied ? 'ok' : ''}`} onClick={copy}>
        {copied ? '✓ 已复制' : '复制'}
      </button>
      <pre ref={ref} {...rest}>{children}</pre>
    </div>
  )
}

/** 共享 Markdown 渲染（GFM 表格/删除线等，代码块自带复制按钮）。 */
export default function Md({ text }) {
  return (
    <div className="md">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={{ pre: CopyPre }}>{text}</ReactMarkdown>
    </div>
  )
}
