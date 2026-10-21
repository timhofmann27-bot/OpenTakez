import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import { consumeShareTarget } from './lib/pending'
import { settingsStore } from './lib/settings'
import './index.css'

document.documentElement.lang = settingsStore.get().lang

// Before the first render so Home picks up the shared link immediately.
consumeShareTarget()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
