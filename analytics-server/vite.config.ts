import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

const backend = 'http://127.0.0.1:8786'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    host: '127.0.0.1',
    fs: {
      deny: ['.env', '.env.*', '*.{crt,pem}', '**/.git/**', '**/data/**', '**/adb-path.txt'],
    },
    proxy: {
      '/v1': {
        target: backend,
        changeOrigin: true,
        configure(proxy) {
          proxy.on('proxyReq', (outgoing, incoming) => {
            const host = incoming.headers.host
            // Preserve the USB endpoint's same-origin check when proxying the local dev site.
            if (host && /^127\.0\.0\.1:\d+$/.test(host) && incoming.headers.origin === `http://${host}`) {
              outgoing.setHeader('Origin', backend)
            }
          })
        },
      },
    },
  },
})
