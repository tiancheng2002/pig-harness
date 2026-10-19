import { forwardRef, useEffect, useImperativeHandle, useLayoutEffect, useRef } from 'react'

const FILE_REF_RE = /@\{([^{}\r\n]+)\}/g

function fileName(path) {
  return path.replace(/\\/g, '/').split('/').pop() || path
}

function fileKind(name) {
  const ext = (name.match(/\.([a-z0-9]+)$/i)?.[1] || '').toLowerCase()
  if (['md', 'markdown', 'txt', 'doc', 'docx', 'pdf', 'rtf'].includes(ext)) return 'doc'
  if (['js', 'jsx', 'ts', 'tsx', 'java', 'py', 'go', 'rs', 'c', 'h', 'cpp', 'cs', 'rb', 'php', 'kt', 'sh', 'bat', 'cmd', 'sql'].includes(ext)) return 'code'
  if (['html', 'htm', 'css', 'scss', 'less', 'vue', 'xml', 'svg'].includes(ext)) return 'web'
  if (['json', 'yml', 'yaml', 'toml', 'ini', 'conf', 'properties', 'lock'].includes(ext)) return 'conf'
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'ico', 'bmp'].includes(ext)) return 'img'
  return 'other'
}

function isFileChip(node) {
  return node?.nodeType === Node.ELEMENT_NODE && node.classList.contains('composer-inline-file')
}

function rawText(node) {
  if (!node) return ''
  if (node.nodeType === Node.TEXT_NODE) return node.nodeValue || ''
  if (node.nodeType !== Node.ELEMENT_NODE && node.nodeType !== Node.DOCUMENT_FRAGMENT_NODE) return ''
  if (isFileChip(node)) return node.dataset.token || ''
  if (node.nodeType === Node.ELEMENT_NODE && node.tagName === 'BR') return '\n'
  return [...node.childNodes].map(rawText).join('')
}

function selectionOffset(root, container, offset) {
  if (!root || !container || (!root.contains(container) && root !== container)) return 0
  try {
    const range = document.createRange()
    range.setStart(root, 0)
    range.setEnd(container, offset)
    return rawText(range.cloneContents()).length
  } catch {
    return rawText(root).length
  }
}

function selectionRange(root) {
  const selection = window.getSelection()
  if (!root || !selection?.rangeCount) return { start: 0, end: 0, focus: 0 }
  const range = selection.getRangeAt(0)
  if (!root.contains(range.commonAncestorContainer) && root !== range.commonAncestorContainer) {
    return { start: 0, end: 0, focus: 0 }
  }
  const start = selectionOffset(root, range.startContainer, range.startOffset)
  const end = selectionOffset(root, range.endContainer, range.endOffset)
  const focus = selectionOffset(root, selection.focusNode, selection.focusOffset)
  return { start, end, focus }
}

function rawLength(node) {
  return rawText(node).length
}

function locateOffset(root, rawOffset) {
  let remaining = Math.max(0, Math.min(rawOffset, rawLength(root)))

  function locate(parent) {
    const children = [...parent.childNodes]
    for (let index = 0; index < children.length; index += 1) {
      const child = children[index]
      if (child.nodeType === Node.TEXT_NODE) {
        const length = child.nodeValue?.length || 0
        if (remaining <= length) return { node: child, offset: remaining }
        remaining -= length
        continue
      }
      if (isFileChip(child)) {
        const length = child.dataset.token?.length || 0
        if (remaining === 0) return { node: parent, offset: index }
        if (remaining < length) {
          return { node: parent, offset: remaining <= length / 2 ? index : index + 1 }
        }
        remaining -= length
        if (remaining === 0) return { node: parent, offset: index + 1 }
        continue
      }
      if (child.nodeType === Node.ELEMENT_NODE && child.tagName === 'BR') {
        if (remaining === 0) return { node: parent, offset: index }
        remaining -= 1
        if (remaining === 0) return { node: parent, offset: index + 1 }
        continue
      }
      const length = rawLength(child)
      if (remaining <= length) return locate(child)
      remaining -= length
    }
    return { node: parent, offset: parent.childNodes.length }
  }

  return locate(root)
}

function setDomSelection(root, start, end = start) {
  if (!root) return
  const from = locateOffset(root, start)
  const to = locateOffset(root, end)
  const range = document.createRange()
  range.setStart(from.node, from.offset)
  range.setEnd(to.node, to.offset)
  const selection = window.getSelection()
  selection.removeAllRanges()
  selection.addRange(range)
}

function createChip(path, projectRoot) {
  const token = `@{${path}}`
  const chip = document.createElement('span')
  chip.className = 'composer-inline-file'
  chip.contentEditable = 'false'
  chip.dataset.filePath = path
  chip.dataset.token = token
  chip.dataset.tooltip = projectRoot
    ? `${projectRoot.replace(/[\\/]+$/, '')}\\${path.replace(/\//g, '\\')}`
    : path
  chip.title = chip.dataset.tooltip

  const icon = document.createElement('span')
  icon.className = `composer-inline-file-icon f-ic-${fileKind(path)}`
  icon.textContent = '▧'

  const name = document.createElement('span')
  name.className = 'composer-inline-file-name'
  name.textContent = fileName(path)

  const close = document.createElement('button')
  close.type = 'button'
  close.className = 'composer-inline-file-close'
  close.title = '移除文件引用'
  close.tabIndex = -1
  close.dataset.removeFile = 'true'
  close.textContent = '×'

  chip.append(icon, name, close)
  return chip
}

function renderValue(root, value, projectRoot) {
  const fragment = document.createDocumentFragment()
  let offset = 0
  for (const match of value.matchAll(FILE_REF_RE)) {
    if (match.index > offset) fragment.append(document.createTextNode(value.slice(offset, match.index)))
    fragment.append(createChip(match[1].trim(), projectRoot))
    offset = match.index + match[0].length
  }
  if (offset < value.length) fragment.append(document.createTextNode(value.slice(offset)))
  root.replaceChildren(fragment)
}

function insertText(root, text) {
  const selection = window.getSelection()
  if (!selection?.rangeCount || !root.contains(selection.anchorNode)) {
    root.focus()
    setDomSelection(root, rawLength(root))
  }
  const activeSelection = window.getSelection()
  const range = activeSelection.getRangeAt(0)
  range.deleteContents()
  const node = document.createTextNode(text)
  range.insertNode(node)
  range.setStartAfter(node)
  range.collapse(true)
  activeSelection.removeAllRanges()
  activeSelection.addRange(range)
}

/**
 * 原生 contenteditable 输入器：文件引用在 DOM 中是真正的不可编辑原子节点，
 * 因而浏览器的光标命中、双击、拖选和复制均与用户看到的宽度一致。
 */
const PromptEditor = forwardRef(function PromptEditor({
  value,
  projectRoot,
  armedStart,
  placeholder,
  disabled,
  onChange,
  onSelectionChange,
  onKeyDown,
  onPaste,
  onOpenFile,
  onRemoveFile
}, ref) {
  const rootRef = useRef(null)
  const projectRootRef = useRef(projectRoot)

  const emitChange = () => {
    const root = rootRef.current
    if (!root) return
    const next = rawText(root)
    const caret = selectionRange(root).focus
    const hasPlainFileToken = [...root.childNodes].some(node =>
      node.nodeType === Node.TEXT_NODE && /@\{[^{}\r\n]+\}/.test(node.nodeValue || ''))
    if (hasPlainFileToken) {
      renderValue(root, next, projectRootRef.current)
      setDomSelection(root, caret)
    }
    onChange?.(next, caret)
  }

  useImperativeHandle(ref, () => ({
    focus: () => rootRef.current?.focus(),
    setSelectionRange: (start, end = start) => setDomSelection(rootRef.current, start, end),
    get selectionStart() { return selectionRange(rootRef.current).start },
    get selectionEnd() { return selectionRange(rootRef.current).end },
    get value() { return rawText(rootRef.current) }
  }), [])

  useLayoutEffect(() => {
    const root = rootRef.current
    if (!root) return
    const rootChanged = projectRootRef.current !== projectRoot
    projectRootRef.current = projectRoot
    if (!rootChanged && rawText(root) === value) return
    const focused = document.activeElement === root
    const previous = focused ? selectionRange(root) : null
    renderValue(root, value, projectRoot)
    if (focused && previous) {
      setDomSelection(root, Math.min(previous.start, value.length), Math.min(previous.end, value.length))
    }
  }, [value, projectRoot])

  useLayoutEffect(() => {
    const root = rootRef.current
    if (!root) return
    let rawOffset = 0
    for (const child of root.childNodes) {
      if (isFileChip(child)) child.classList.toggle('armed', rawOffset === armedStart)
      rawOffset += rawLength(child)
    }
  }, [value, armedStart])

  useEffect(() => {
    const handleSelection = () => {
      if (document.activeElement === rootRef.current) {
        onSelectionChange?.(selectionRange(rootRef.current).focus)
      }
    }
    document.addEventListener('selectionchange', handleSelection)
    return () => document.removeEventListener('selectionchange', handleSelection)
  }, [onSelectionChange])

  const handleClick = event => {
    const root = rootRef.current
    const chip = event.target.closest?.('.composer-inline-file')
    if (!root || !chip || !root.contains(chip)) return
    const start = selectionOffset(root, root, [...root.childNodes].indexOf(chip))
    const token = chip.dataset.token || ''
    if (event.target.closest?.('[data-remove-file]')) {
      event.preventDefault()
      event.stopPropagation()
      onRemoveFile?.(start, start + token.length)
      return
    }
    onOpenFile?.(chip.dataset.filePath)
  }

  const handlePaste = event => {
    onPaste?.(event)
    if (event.defaultPrevented) return
    event.preventDefault()
    const text = event.clipboardData?.getData('text/plain') || ''
    insertText(rootRef.current, text)
    const next = rawText(rootRef.current)
    const caret = selectionRange(rootRef.current).focus
    if (/@\{[^{}\r\n]+\}/.test(text)) {
      renderValue(rootRef.current, next, projectRoot)
      setDomSelection(rootRef.current, caret)
    }
    onChange?.(next, caret)
  }

  const handleCopy = event => {
    const selection = window.getSelection()
    if (!selection?.rangeCount || !rootRef.current?.contains(selection.anchorNode)) return
    event.preventDefault()
    event.clipboardData.setData('text/plain', rawText(selection.getRangeAt(0).cloneContents()))
  }

  const handleKeyDown = event => {
    onKeyDown?.(event)
    if (event.defaultPrevented) return
    if (event.key === 'Enter') {
      event.preventDefault()
      insertText(rootRef.current, '\n')
      emitChange()
    }
  }

  return (
    <div
      ref={rootRef}
      className="composer-editor-input"
      contentEditable={!disabled}
      role="textbox"
      aria-multiline="true"
      aria-disabled={disabled || undefined}
      data-placeholder={placeholder}
      suppressContentEditableWarning
      spellCheck={false}
      onInput={emitChange}
      onKeyDown={handleKeyDown}
      onPaste={handlePaste}
      onCopy={handleCopy}
      onClick={handleClick}
    />
  )
})

export default PromptEditor
