import { expect, test, type Page } from '@playwright/test'

// Memora 2.0 orchestration, driven through the real dashboard against the real API.
const PASSWORD = process.env.MEMORA_DEMO_PASSWORD ?? 'memora-demo'
const SHOTS = process.env.MEMORA_SCREENSHOT_DIR

async function shot(page: Page, name: string) {
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true })
}

async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('Email').fill('alice@acme.test')
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible()
}

test.beforeEach(async ({ page }) => {
  page.on('pageerror', (e) => {
    throw e
  })
})

/** Creates a custom agent in Agent studio and a one-step workflow for it in Workflow studio. */
async function createAgentAndWorkflow(page: Page, suffix: string) {
  const agentKey = `e2e-writer-${suffix}`
  await page.getByRole('link', { name: 'Agent studio' }).click()
  await page.getByRole('button', { name: 'New agent' }).click()
  await page.getByLabel('Name', { exact: true }).fill(`E2E Writer ${suffix}`)
  await page.getByLabel(/^Role/).fill('Technical writer')
  await page.getByLabel(/^Key/).fill(agentKey)
  await page.getByRole('button', { name: 'Validate' }).click()
  await expect(page.getByText('No problems found.')).toBeVisible()
  await page.getByRole('button', { name: 'Create agent' }).click()
  await expect(page.getByText(`Edit ${agentKey}`)).toBeVisible()
  await shot(page, '10-agent-studio')

  await page.getByRole('link', { name: 'Workflow studio' }).click()
  await page.getByRole('button', { name: 'New workflow' }).click()
  await page.getByLabel('Name', { exact: true }).first().fill(`E2E workflow ${suffix}`)
  await page.getByRole('button', { name: 'Add step' }).click()
  await page.getByRole('combobox', { name: /^Agent/ }).selectOption(agentKey)
  await page.getByLabel(/^Inputs \(templates/).fill('{"topic": "${run.inputs.goal}"}')
  await expect(page.getByText('valid', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Create workflow' }).click()
  await expect(page.getByText(/Editing .* v1/)).toBeVisible()
  await shot(page, '11-workflow-studio')
}

test('create a custom agent and workflow, run it and inspect its artifact', async ({ page }) => {
  await login(page)
  await createAgentAndWorkflow(page, String(Date.now()))
  await page.getByRole('textbox', { name: 'Goal', exact: true }).fill('Release notes for the orchestration feature')
  await page.getByRole('button', { name: 'Start run' }).click()
  await expect(page).toHaveURL(/\/runs\//)
  await expect(page.getByRole('heading', { name: 'Completion criteria met', exact: true })).toBeVisible({ timeout: 60_000 })
  await page.getByRole('button', { name: 'notes.md' }).first().click()
  await expect(page.getByRole('dialog')).toContainText('Release notes for the orchestration feature')
  await expect(page.getByRole('dialog')).toContainText('sha256')
  await shot(page, '12-run')
})

test('a step that keeps failing can be retried from the step inspector', async ({ page }) => {
  await login(page)
  await createAgentAndWorkflow(page, 'r' + Date.now())
  await page.getByRole('textbox', { name: 'Goal', exact: true }).fill('Retry demonstration')
  await page.getByRole('textbox', { name: /^Fault injection/ }).fill('{"step-1": {"failAttempts": 3}}')
  await page.getByRole('button', { name: 'Start run' }).click()
  await expect(page.getByRole('heading', { name: 'Completion criteria not met', exact: true })).toBeVisible({ timeout: 60_000 })
  await page.getByRole('button', { name: 'Step 1, failed' }).click()
  await expect(page.getByText('Injected fault (demo)').first()).toBeVisible()
  await page.getByRole('button', { name: 'Retry step' }).click()
  await expect(page.getByRole('heading', { name: 'Completion criteria met', exact: true })).toBeVisible({ timeout: 60_000 })
  await shot(page, '13-retry')
})

test('the planner proposes an editable workflow for a goal', async ({ page }) => {
  await login(page)
  await page.getByRole('link', { name: 'Plan a workflow' }).click()
  await page.getByRole('button', { name: 'E-commerce website' }).click()
  await page.getByRole('button', { name: 'Propose a workflow' }).click()
  await expect(page.getByText('Requirements (7)')).toBeVisible()
  await expect(page.getByText(/They do not write or run application code/)).toBeVisible()
  await page.getByRole('button', { name: 'Edit in Workflow studio' }).click()
  await expect(page).toHaveURL(/\/workflows\/new/)
  await expect(page.getByText('Steps (13)')).toBeVisible()
  await shot(page, '14-plan')
})
