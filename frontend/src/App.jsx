import { Routes, Route } from 'react-router-dom'
import { useEffect, useState } from 'react'
import Sidebar from './Sidebar.jsx'
import SettingsModal from './SettingsModal.jsx'
import ConfirmHost from './components/ConfirmDialog.jsx'
import Chat from './pages/Chat.jsx'
import Knowledge from './pages/Knowledge.jsx'
import Skills from './pages/Skills.jsx'
import { fetchConfig } from './api.js'

export default function App() {
  const [config, setConfig] = useState(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [theme, setTheme] = useState(() => localStorage.getItem('harness.theme') || 'dark')

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('harness.theme', theme)
  }, [theme])

  useEffect(() => {
    fetchConfig().then(setConfig).catch(() => setConfig(null))
  }, [])

  // 模型下拉的「管理模型」入口（Chat 页 composer 处）唤起设置弹窗
  useEffect(() => {
    const open = () => setSettingsOpen(true)
    window.addEventListener('open-settings', open)
    return () => window.removeEventListener('open-settings', open)
  }, [])

  return (
    <div className="app-shell">
      <Sidebar
        config={config}
        theme={theme}
        onToggleTheme={() => setTheme(t => t === 'dark' ? 'light' : 'dark')}
        onOpenSettings={() => setSettingsOpen(true)}
      />
      <main className="workspace">
        <Routes>
          <Route path="/" element={<Chat />} />
          <Route path="/chat" element={<Chat />} />
          <Route path="/c/:conversationId" element={<Chat />} />
          <Route path="/knowledge" element={<Knowledge />} />
          <Route path="/skills" element={<Skills />} />
        </Routes>
      </main>
      {settingsOpen && (
        <SettingsModal config={config} onClose={() => setSettingsOpen(false)} />
      )}
      <ConfirmHost />
    </div>
  )
}
