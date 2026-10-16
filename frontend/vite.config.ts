/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The dev server proxies /api to the local backend so the browser talks to one origin.
const backend = process.env.MEMORA_BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: { chunkSizeWarningLimit: 900 },
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/actuator': backend,
      '/v3': backend,
      '/swagger-ui': backend,
    },
  },
  preview: {
    port: 4173,
    proxy: {
      '/api': backend,
      '/actuator': backend,
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
