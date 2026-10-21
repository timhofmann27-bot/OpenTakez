import { useEffect, useState } from 'react'

type Health = { status: string; version: string; yt_dlp: string }

export default function App() {
  const [health, setHealth] = useState<Health | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    fetch('/api/health')
      .then((res) => res.json())
      .then(setHealth)
      .catch(() => setError(true))
  }, [])

  return (
    <main className="min-h-dvh bg-neutral-950 text-neutral-100 flex flex-col items-center justify-center gap-4 p-4">
      <img src="/logo.svg" alt="" className="size-16" />
      <h1 className="text-3xl font-bold">OpenTakez</h1>
      <p className="text-sm text-neutral-400">
        {error && 'Server nicht erreichbar'}
        {health && `Server ${health.version} · yt-dlp ${health.yt_dlp}`}
        {!error && !health && 'Verbinde …'}
      </p>
    </main>
  )
}
