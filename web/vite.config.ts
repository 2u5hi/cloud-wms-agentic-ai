/// <reference types="vitest/config" />
import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const wmsCore = 'http://localhost:8080'

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
    },
  },
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
