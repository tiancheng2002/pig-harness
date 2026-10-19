/**
 * 极简单色图标（内联 SVG，统一 1.6 线宽，随 currentColor 着色）。
 * 仅用于导航 / 状态等少数位置，避免 emoji 带来的杂色。
 */

function Svg({ size = 15, strokeWidth = 1.6, children }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  )
}

/** 技能：层叠模块 */
export function IconLayers({ size }) {
  return (
    <Svg size={size}>
      <path d="M12 3l9 5-9 5-9-5 9-5z" />
      <path d="M3 12.5l9 5 9-5" />
      <path d="M3 17l9 5 9-5" />
    </Svg>
  )
}

/** 知识：书 */
export function IconBook({ size }) {
  return (
    <Svg size={size}>
      <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
      <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
    </Svg>
  )
}

/** 设置：滑杆 */
export function IconSliders({ size }) {
  return (
    <Svg size={size}>
      <line x1="4" y1="21" x2="4" y2="14" />
      <line x1="4" y1="10" x2="4" y2="3" />
      <line x1="12" y1="21" x2="12" y2="12" />
      <line x1="12" y1="8" x2="12" y2="3" />
      <line x1="20" y1="21" x2="20" y2="16" />
      <line x1="20" y1="12" x2="20" y2="3" />
      <line x1="1" y1="14" x2="7" y2="14" />
      <line x1="9" y1="8" x2="15" y2="8" />
      <line x1="17" y1="16" x2="23" y2="16" />
    </Svg>
  )
}

/** 文件夹 */
export function IconFolder({ size }) {
  return (
    <Svg size={size}>
      <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
    </Svg>
  )
}

/** Git 分支 */
export function IconGitBranch({ size }) {
  return (
    <Svg size={size}>
      <line x1="6" y1="3" x2="6" y2="15" />
      <circle cx="18" cy="6" r="3" />
      <circle cx="6" cy="18" r="3" />
      <path d="M18 9a9 9 0 0 1-9 9" />
    </Svg>
  )
}

/** 文档 */
export function IconFile({ size }) {
  return (
    <Svg size={size}>
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <path d="M14 2v6h6" />
    </Svg>
  )
}

/**
 * 按扩展名分类的文件图标（Git 面板 / 文件 chip 共用）：
 * 同一枚文件字形，按 doc/code/web/conf/img/other 六类着色，明暗主题下均可辨识。
 */
const FILE_KIND_EXTS = {
  doc: ['md', 'markdown', 'txt', 'doc', 'docx', 'pdf', 'rtf'],
  code: ['js', 'jsx', 'ts', 'tsx', 'java', 'py', 'go', 'rs', 'c', 'h', 'cpp', 'cs', 'rb', 'php', 'kt', 'sh', 'bat', 'cmd', 'sql'],
  web: ['html', 'htm', 'css', 'scss', 'less', 'vue', 'xml', 'svg'],
  conf: ['json', 'yml', 'yaml', 'toml', 'ini', 'conf', 'properties', 'lock', 'gitignore', 'example'],
  img: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'ico', 'bmp'],
}

export function FileIcon({ name, size = 14 }) {
  const ext = (String(name || '').match(/\.([a-z0-9]+)$/i)?.[1] || '').toLowerCase()
  const base = ext.startsWith('.') ? ext.slice(1) : ext
  let kind = 'other'
  for (const [k, exts] of Object.entries(FILE_KIND_EXTS)) {
    if (exts.includes(base)) { kind = k; break }
  }
  // 无扩展名 / 特殊文件名（.gitignore、.env.example）按整名匹配兜底
  if (kind === 'other') {
    const lower = String(name || '').toLowerCase()
    if (lower.endsWith('.env.example') || lower.includes('dockerfile') || lower.startsWith('.git')) kind = 'conf'
  }
  return (
    <span className={`file-ico fi-${kind}`}>
      <IconFile size={size} />
    </span>
  )
}

/** 下箭头（折叠分组用，随容器旋转） */
export function IconChevronDown({ size = 12 }) {
  return (
    <Svg size={size}>
      <path d="M6 9l6 6 6-6" />
    </Svg>
  )
}

/** 勾（下拉选中态） */
export function IconCheck({ size = 14 }) {
  return (
    <Svg size={size} strokeWidth="1.8">
      <path d="M20 6L9 17l-5-5" />
    </Svg>
  )
}

/** 盾牌（替我审批 / 自动执行） */
export function IconShield({ size }) {
  return (
    <Svg size={size}>
      <path d="M12 3l7 3v5c0 4.6-3 8.2-7 10-4-1.8-7-5.4-7-10V6l7-3z" />
    </Svg>
  )
}

/** 问号圈（请求审批 / 每次确认） */
export function IconHelp({ size }) {
  return (
    <Svg size={size}>
      <circle cx="12" cy="12" r="9" />
      <path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 2.6-3 2.6" />
      <path d="M12 17h.01" />
    </Svg>
  )
}

/** 开锁（完全访问） */
export function IconUnlock({ size }) {
  return (
    <Svg size={size}>
      <rect width="18" height="11" x="3" y="11" rx="2" />
      <path d="M7 11V7a5 5 0 0 1 9.9-1" />
    </Svg>
  )
}
