import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      registerType: 'autoUpdate',
      pwaAssets: { image: 'public/logo.svg' },
      manifest: {
        name: 'OpenTakez',
        short_name: 'OpenTakez',
        description: 'Self-hosted media downloader',
        theme_color: '#7c3aed',
        background_color: '#0b0b10',
        display: 'standalone',
        start_url: '/',
        // Lets the installed PWA appear in Android's "Share" sheet.
        share_target: {
          action: '/share',
          method: 'GET',
          params: { title: 'title', text: 'text', url: 'url' },
        },
      },
      workbox: {
        navigateFallbackDenylist: [/^\/api\//],
      },
    }),
  ],
  server: {
    // Backend for `pnpm dev`; override with OPENTAKEZ_API=http://localhost:8010 pnpm dev
    proxy: { '/api': process.env.OPENTAKEZ_API ?? 'http://localhost:8000' },
  },
})
