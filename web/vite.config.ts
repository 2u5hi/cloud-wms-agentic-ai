/// <reference types="vitest/config" />
import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const wmsCore = 'http://localhost:8080'
const opsAgent = 'http://localhost:8000'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    // The browser only talks to the dev server; these paths are forwarded to wms-core.
    proxy: {
      '/api': wmsCore,
      '/actuator': wmsCore,
      '/v3': wmsCore,
      // The agent is its own service; the console never calls Anthropic directly.
      '/agent': { target: opsAgent, rewrite: (path) => path.replace(/^\/agent/, '') },
    },
  },
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
