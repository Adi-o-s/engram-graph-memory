import { defineConfig } from '@playwright/test'

// End-to-end tests drive the real dashboard against a real backend; nothing is mocked.
// Start PostgreSQL and the backend first, then `npm run build && npm run preview`, then `npm run e2e`.
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.MEMORA_UI_URL ?? 'http://localhost:4173',
    viewport: { width: 1440, height: 1000 },
    screenshot: 'only-on-failure',
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH } : {},
  },
})
