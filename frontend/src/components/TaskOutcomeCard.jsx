import Md from './Md.jsx'

export default function TaskOutcomeCard({ outcome, conclusion, planMode, children }) {
  if (!outcome) return null
  const elapsedMs = outcome.metrics?.wallDurationMs

  return (
    <>
      {planMode ? children : <div className="answer"><Md text={conclusion || outcome.summary || ''} /></div>}
      {typeof elapsedMs === 'number' && Number.isFinite(elapsedMs) && elapsedMs >= 0 && (
        <div className="turn-duration">本轮耗时 {formatDuration(elapsedMs)}</div>
      )}
    </>
  )
}

function formatDuration(ms) {
  if (ms < 1000) return '不足 1 秒'
  const seconds = Math.round(ms / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)
  if (hours) return `${hours} 小时 ${minutes % 60} 分 ${seconds % 60} 秒`
  if (minutes) return `${minutes} 分 ${seconds % 60} 秒`
  return `${seconds} 秒`
}
