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
      pwaAssets: {
        image: 'public/logo.svg',
        // logo.svg already carries the dark icon background, so nothing is padded or
        // re-coloured; the default preset would put the maskable icon on white with a margin.
        preset: {
          transparent: { sizes: [64, 192, 512], padding: 0, favicons: [[64, 'favicon.ico']] },
          maskable: { sizes: [512], padding: 0 },
          apple: { sizes: [180], padding: 0 },
        },
      },
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
