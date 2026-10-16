import { expect, test, type Page } from '@playwright/test'

const PASSWORD = process.env.MEMORA_DEMO_PASSWORD ?? 'memora-demo'
const SHOTS = process.env.MEMORA_SCREENSHOT_DIR

async function shot(page: Page, name: string) {
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true })
}

async function login(page: Page, email: string) {
  await page.goto('/')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible()
}

test.beforeEach(async ({ page }) => {
  page.on('pageerror', (e) => {
    throw e
  })
})

test('overview shows live tenant data and row-level security', async ({ page }) => {
  await login(page, 'alice@acme.test')
  await expect(page.getByText('Enforced')).toBeVisible()
  await expect(page.getByText('Memory by type and status')).toBeVisible()
  await shot(page, '01-overview')
})

test('guided demo runs all five steps against the backend', async ({ page }) => {
  await login(page, 'alice@acme.test')
  await page.getByRole('link', { name: 'Guided demo' }).click()
  const startButton = page.getByRole('button', { name: /Start (demo|a new run)/ })
  await startButton.click()
  await expect(page.getByText('We switched from MySQL to PostgreSQL.').first()).toBeVisible()
  await expect(page.getByText('Superseded Previous').first()).toBeVisible()

  const stepButton = (title: string) => page.locator('div.rounded-xl', { has: page.getByRole('heading', { name: title }) }).getByRole('button')
  await stepButton('2. Plan').click()
  await expect(page.getByText('PLANNED').or(page.getByText('HANDED_OFF')).first()).toBeVisible()
  await stepButton('3. Develop').click()
  await expect(page.getByText(/postgresql/i).first()).toBeVisible()
  await stepButton('4. Test (first attempt fails)').click()
  await expect(page.getByText('FAILED_RETRYABLE')).toBeVisible()
  await stepButton('5. Retry').click()
  await expect(page.getByText('Done', { exact: true }).first()).toBeVisible()
  await shot(page, '02-guided-demo')
})

test('memory explorer searches and shows history', async ({ page }) => {
  await login(page, 'alice@acme.test')
  await page.getByRole('link', { name: 'Memory explorer' }).click()
  await page.getByPlaceholder(/e\.g\. database/).fill('database')
  await page.getByLabel('include superseded').check()
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByText(/tsquery/)).toBeVisible()
  await page.locator('button', { hasText: 'PostgreSQL' }).first().click()
  await expect(page.getByText('Version history')).toBeVisible()
  await shot(page, '03-memory-explorer')
})

test('conversation message extracts a memory', async ({ page }) => {
  await login(page, 'alice@acme.test')
  await page.getByRole('link', { name: 'Conversations' }).click()
  const title = `E2E ${Date.now()}`
  await page.getByLabel('New conversation title').fill(title)
  await page.getByRole('button', { name: 'Add' }).click()
  await page.getByLabel('Message').fill('I prefer dark mode in every editor.')
  await page.getByRole('button', { name: 'Send' }).click()
  await expect(page.getByText(/Extraction result for message #1/)).toBeVisible()
  // A first run stores the preference; later runs recognise it as a duplicate of the same fact.
  await expect(page.getByText(/^(Created|Duplicate)$/).first()).toBeVisible()
  await expect(page.getByText(/prefers dark mode/i).first()).toBeVisible()
  await shot(page, '04-conversations')
})

test('tenant isolation probes all come back isolated', async ({ page }) => {
  await login(page, 'alice@acme.test')
  await page.getByRole('link', { name: 'Tenant isolation' }).click()
  await page.getByRole('button', { name: 'Sign in second user' }).click()
  await expect(page.getByText(/Second session: Bob/)).toBeVisible()
  await page.getByRole('button', { name: 'Run probes' }).click()
  const rows = page.locator('li', { has: page.getByText('Isolated', { exact: true }) })
  await expect(rows).toHaveCount(6)
  await expect(page.getByText('LEAK', { exact: true })).toHaveCount(0)
  await shot(page, '05-tenant-isolation')
})

test('task board shows a 409 for a stale write', async ({ page }) => {
  await login(page, 'alice@acme.test')
  await page.getByRole('link', { name: 'Tasks & handoffs' }).click()
  await page.locator('main button', { hasText: /\S/ }).filter({ has: page.getByText(/^v\d+$/) }).first().click()
  await page.getByRole('button', { name: 'Simulate stale write' }).click()
  await expect(page.getByText(/409 Conflict/)).toBeVisible()
  await shot(page, '06-task-board')
})

test('agent workspace builds a bounded context', async ({ page }) => {
  await login(page, 'alice@acme.test')
  await page.getByRole('link', { name: 'Agent workspace' }).click()
  await expect(page.getByText('Planner').first()).toBeVisible()
  await page.getByRole('button', { name: 'Build context' }).click()
  await expect(page.getByText(/chars$/).first()).toBeVisible()
  await shot(page, '07-agents')
})

test('concurrency lab run passes its invariants', async ({ page }) => {
  await login(page, 'alice@acme.test')
  await page.getByRole('link', { name: 'Concurrency lab' }).click()
  await page.getByLabel('Threads (1–64)').fill('8')
  await page.getByLabel('Operations').fill('120')
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(page.getByText('Latest run')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByText('All passed')).toBeVisible()
  await shot(page, '08-concurrency-lab')
})
