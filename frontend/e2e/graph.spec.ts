import { expect, test, type Page } from '@playwright/test'

// The FalkorDB graph walkthrough end to end: needs the backend with FalkorDB running (docker compose up).
const PASSWORD = process.env.MEMORA_DEMO_PASSWORD ?? 'memora-demo'
const SHOTS = process.env.MEMORA_SCREENSHOT_DIR

async function shot(page: Page, name: string) {
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true })
}

test.beforeEach(async ({ page }) => {
  page.on('pageerror', (e) => {
    throw e
  })
})

test('graph walkthrough shows context paths, a stale-knowledge alert and separate tenant graphs', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Email').fill('alice@acme.test')
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible()
  await page.getByRole('link', { name: 'Guided demo' }).click()

  const walk = page.getByTestId('graph-walkthrough')
  const step = (title: string) => walk.getByRole('button', { name: `Graph step ${title}` })

  await step('A. Ingest a conversation').click()
  await expect(walk.getByText('billing-service deploys through ArgoCD.').last()).toBeVisible()

  await step('B. See the entities appear').click()
  await expect(walk.getByTestId('walkthrough-entities')).toContainText('ArgoCD')

  await step('C. Coder asks for context').click()
  await expect(walk.getByText('retrieval: graph').first()).toBeVisible()
  // The Kubernetes fact is reached through billing-service -RELATES-> argocd -ABOUT-> memory.
  await expect(walk.getByTestId('graph-path').filter({ hasText: 'RELATES' }).first()).toBeVisible()
  await expect(walk.getByText('2 hops', { exact: false }).first()).toBeVisible()

  await step('D. Change a fact').click()
  const alerts = walk.getByTestId('stale-alerts')
  await expect(alerts).toHaveCount(1)
  await expect(alerts).toContainText('billing-service uses MySQL')
  await expect(alerts).toContainText('PostgreSQL')
  await expect(walk.getByTestId('reviewer-alerts')).toContainText('No alerts')
  await shot(page, '20-graph-walkthrough')

  await step('E. Switch tenants').click()
  const tenants = walk.getByTestId('tenant-graphs')
  await expect(tenants.locator('tbody tr').nth(0)).toContainText('yes')
  await expect(tenants.locator('tbody tr').nth(1)).toContainText('no')

  await page.getByRole('navigation').getByRole('link', { name: 'Memory graph' }).click()
  await expect(page.getByTestId('graph-counts')).toContainText('Entity')
  await expect(page.locator('[data-testid=graph-canvas] canvas')).toBeVisible()
  await expect(page.getByTestId('key-entities')).toContainText('links')
  await shot(page, '21-memory-graph')
})
