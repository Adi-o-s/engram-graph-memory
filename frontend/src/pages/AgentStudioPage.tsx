import { useEffect, useMemo, useState } from 'react'
import { Copy, Download, Save, ShieldCheck } from 'lucide-react'
import { orchestration } from '../api/orchestration'
import type { AgentDefinition, AgentDefinitionInput, AgentValidation, ExecutorDescriptor, ToolDescriptor } from '../api/orchestration'
import { useAuth } from '../auth/AuthContext'
import { Badge, Button, Card, Empty, ErrorBox, Field, Input, Mono, PageHeader, Select, Spinner, StatusBadge, Textarea } from '../components/ui'
import { JsonField } from '../components/JsonField'
import { useAsync } from '../lib/useAsync'
import { cx, timeAgo } from '../lib/format'

const SCOPES = ['RUN', 'PROJECT', 'WORKFLOW', 'AGENT', 'TENANT', 'USER'] as const
const SCOPE_HELP: Record<string, string> = {
  RUN: 'this run only', PROJECT: "the run's project", WORKFLOW: 'all runs of this workflow', AGENT: "this agent's own notes",
  TENANT: 'shared tenant knowledge', USER: 'personal memories of the person who started the run',
}

const BLANK: AgentDefinitionInput = {
  name: '', role: '', description: '', instructions: '', capabilities: ['document', 'artifact.write'], tools: ['document.compose', 'artifact.write'],
  executor: 'template', inputSchema: { type: 'object' }, outputSchema: { type: 'object' },
  memoryPolicy: { read: ['RUN', 'PROJECT', 'TENANT'], write: ['RUN', 'PROJECT'], maxItems: 10, maxChars: 3000 },
  contextPolicy: { includeArtifacts: true },
  behavior: {
    operations: [
      { id: 'doc', tool: 'document.compose', args: { title: '${input.topic}', sections: [{ heading: 'Notes', body: '${input.topic}' }] } },
      { id: 'save', tool: 'artifact.write', args: { name: 'notes.md', kind: 'markdown', content: '${ops.doc.markdown}' } },
    ],
    output: { artifact: '${ops.save.name}' },
  },
  timeoutSeconds: 60, maxAttempts: 3, backoffSeconds: 1,
}

export function AgentStudioPage() {
  const { me } = useAuth()
  const isAdmin = me?.tenant.role === 'ADMIN'
  const defs = useAsync(() => orchestration.agentDefinitions(), [])
  const tools = useAsync(() => orchestration.tools(), [])
  const executors = useAsync(() => orchestration.executors(), [])
  const [tab, setTab] = useState<'agents' | 'templates'>('agents')
  const [selectedId, setSelectedId] = useState<string | 'new'>()
  const [filter, setFilter] = useState('')
  const selected = defs.data?.find((d) => d.id === selectedId)

  useEffect(() => {
    if (!selectedId && defs.data && defs.data.length > 0) setSelectedId(defs.data[0].id)
  }, [defs.data, selectedId])

  const visible = (defs.data ?? []).filter((d) => !filter || (d.name + d.role + d.key).toLowerCase().includes(filter.toLowerCase()))

  return (
    <div className="space-y-6">
      <PageHeader title="Agent studio"
        description="Agents are configurations, not code: a role, instructions, the tools and capabilities they may use, memory scopes they may read and write, schemas, and an executor. The same engine runs every agent."
        actions={<div className="flex gap-2">
          <Button variant={tab === 'agents' ? 'primary' : 'secondary'} size="sm" onClick={() => setTab('agents')}>Agents ({defs.data?.length ?? 0})</Button>
          <Button variant={tab === 'templates' ? 'primary' : 'secondary'} size="sm" onClick={() => setTab('templates')}>Templates</Button>
          {isAdmin && <Button size="sm" variant="secondary" onClick={() => { setTab('agents'); setSelectedId('new') }}>New agent</Button>}
        </div>} />
      <ErrorBox error={defs.error ?? tools.error} />
      {tab === 'templates' ? <Templates isAdmin={isAdmin} onInstalled={async (d) => { await defs.reload(); setTab('agents'); setSelectedId(d.id) }} /> : (
        <div className="grid gap-6 xl:grid-cols-[18rem_minmax(0,1fr)]">
          <div className="space-y-2">
            <Input aria-label="Filter agents" placeholder="Filter by name or role" value={filter} onChange={(e) => setFilter(e.target.value)} />
            {!defs.data ? <Spinner /> : visible.map((d) => (
              <button key={d.id} type="button" onClick={() => setSelectedId(d.id)}
                className={cx('block w-full rounded-lg border px-3 py-2 text-left text-sm', selectedId === d.id ? 'border-brand-500 bg-brand-50' : 'border-slate-200 bg-white hover:bg-slate-50')}>
                <div className="flex items-center justify-between gap-2"><span className="font-medium">{d.name}</span>{d.status === 'INACTIVE' && <Badge>inactive</Badge>}</div>
                <div className="text-[11px] text-slate-500">{d.role} · {d.executor} · v{d.version}</div>
              </button>
            ))}
          </div>
          {!tools.data || !executors.data ? <Spinner /> : selectedId === 'new' ? (
            <AgentEditor key="new" initial={BLANK} tools={tools.data} executors={executors.data} isAdmin={isAdmin}
              onSaved={async (d) => { await defs.reload(); setSelectedId(d.id) }} />
          ) : selected ? (
            <AgentEditor key={selected.id + ':' + selected.lockVersion} definition={selected} initial={toInput(selected)} tools={tools.data}
              executors={executors.data} isAdmin={isAdmin} onSaved={async (d) => { await defs.reload(); setSelectedId(d.id) }} />
          ) : <Card><Empty>Select an agent.</Empty></Card>}
        </div>
      )}
    </div>
  )
}

function toInput(d: AgentDefinition): AgentDefinitionInput {
  return {
    key: d.key, name: d.name, role: d.role, description: d.description, instructions: d.instructions, capabilities: d.capabilities,
    tools: d.tools, inputSchema: d.inputSchema, outputSchema: d.outputSchema, memoryPolicy: d.memoryPolicy, contextPolicy: d.contextPolicy,
    executor: d.executor, behavior: d.behavior, timeoutSeconds: d.timeoutSeconds, maxAttempts: d.maxAttempts, backoffSeconds: d.backoffSeconds,
    expectedLockVersion: d.lockVersion,
  }
}

function AgentEditor({ definition, initial, tools, executors, isAdmin, onSaved }: {
  definition?: AgentDefinition; initial: AgentDefinitionInput; tools: ToolDescriptor[]; executors: ExecutorDescriptor[]; isAdmin: boolean
  onSaved: (d: AgentDefinition) => Promise<void>
}) {
  const [form, setForm] = useState<AgentDefinitionInput>(initial)
  const [report, setReport] = useState<AgentValidation>()
  const [busy, setBusy] = useState<string>()
  const [error, setError] = useState<unknown>()
  const versions = useAsync(() => (definition ? orchestration.agentVersions(definition.id) : Promise.resolve([])), [definition?.id, definition?.version])
  const executor = executors.find((e) => e.id === form.executor)
  const policy = (form.memoryPolicy ?? {}) as { read?: string[]; write?: string[]; maxItems?: number; maxChars?: number }
  const neededCaps = useMemo(() => Array.from(new Set(form.tools.map((t) => tools.find((x) => x.id === t)?.requiredCapability).filter(Boolean) as string[])), [form.tools, tools])
  const missingCaps = neededCaps.filter((c) => !form.capabilities.includes(c))
  const set = (patch: Partial<AgentDefinitionInput>) => { setForm({ ...form, ...patch }); setReport(undefined) }

  async function run(label: string, fn: () => Promise<void>) {
    setBusy(label)
    setError(undefined)
    try {
      await fn()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(undefined)
    }
  }

  const grouped = tools.reduce<Record<string, ToolDescriptor[]>>((acc, t) => ((acc[t.category] ??= []).push(t), acc), {})

  return (
    <div className="grid min-w-0 gap-6 2xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
      <div className="min-w-0 space-y-6">
        <Card title={definition ? <>Edit <Mono>{definition.key}</Mono> <span className="text-xs font-normal text-slate-500">v{definition.version}</span></> : 'New agent'}
          actions={definition && <StatusBadge value={definition.status} />}>
          <div className="grid gap-3 md:grid-cols-2">
            <Field label="Name"><Input value={form.name} onChange={(e) => set({ name: e.target.value })} /></Field>
            <Field label="Role" hint="Free text; describes the specialist. It does not change behaviour."><Input value={form.role} onChange={(e) => set({ role: e.target.value })} placeholder="e.g. Compliance reviewer" /></Field>
            {!definition && <Field label="Key" hint="Workflows refer to agents by key"><Input value={form.key ?? ''} onChange={(e) => set({ key: e.target.value })} placeholder="derived from the name" /></Field>}
            <Field label="Executor" hint={executor?.description}>
              <Select value={form.executor} onChange={(e) => set({ executor: e.target.value })}>
                {executors.map((x) => <option key={x.id} value={x.id} disabled={!x.available}>{x.title}{x.available ? '' : ' (not available)'}</option>)}
              </Select>
            </Field>
            <div className="md:col-span-2"><Field label="Description"><Input value={form.description ?? ''} onChange={(e) => set({ description: e.target.value })} /></Field></div>
            <div className="md:col-span-2"><Field label="Instructions" hint="Shown to people and to external agents. Instructions never grant permissions.">
              <Textarea rows={3} value={form.instructions ?? ''} onChange={(e) => set({ instructions: e.target.value })} /></Field></div>
          </div>
        </Card>
        <Card title="Tools and capabilities">
          {Object.entries(grouped).map(([category, list]) => (
            <fieldset key={category} className="mb-3">
              <legend className="text-xs font-semibold uppercase tracking-wide text-slate-500">{category}</legend>
              <div className="mt-1 grid gap-1 md:grid-cols-2">
                {list.map((t) => (
                  <label key={t.id} className={cx('flex items-start gap-2 rounded px-1 py-0.5 text-xs', !t.available && 'opacity-60')}>
                    <input type="checkbox" className="mt-0.5" checked={form.tools.includes(t.id)}
                      onChange={(e) => set({ tools: e.target.checked ? [...form.tools, t.id] : form.tools.filter((x) => x !== t.id) })} />
                    <span><Mono>{t.id}</Mono> {t.sideEffects && <Badge tone="amber">writes</Badge>} {t.requiresApproval && <Badge tone="violet">approval</Badge>} {!t.available && <Badge>unavailable</Badge>}
                      <span className="block text-slate-500">{t.title}{t.requiredCapability ? ` · needs ${t.requiredCapability}` : ''}</span></span>
                  </label>
                ))}
              </div>
            </fieldset>
          ))}
          <Field label="Capabilities" hint="Comma-separated. A tool can only be used if its required capability is declared.">
            <Input value={form.capabilities.join(', ')} onChange={(e) => set({ capabilities: e.target.value.split(',').map((s) => s.trim()).filter(Boolean) })} />
          </Field>
          {missingCaps.length > 0 && <div className="mt-2 flex items-center gap-2 text-xs text-amber-800">Selected tools need: {missingCaps.join(', ')}
            <Button size="sm" variant="secondary" onClick={() => set({ capabilities: [...form.capabilities, ...missingCaps] })}>Add them</Button></div>}
        </Card>
        <Card title="Memory and context">
          <div className="grid gap-4 md:grid-cols-2">
            {(['read', 'write'] as const).map((mode) => (
              <fieldset key={mode}>
                <legend className="text-sm font-medium text-slate-700">May {mode}</legend>
                {SCOPES.filter((s) => mode === 'read' || s !== 'USER').map((s) => (
                  <label key={s} className="flex items-center gap-2 text-xs">
                    <input type="checkbox" checked={(policy[mode] ?? []).includes(s)}
                      onChange={(e) => set({ memoryPolicy: { ...policy, [mode]: e.target.checked ? [...(policy[mode] ?? []), s] : (policy[mode] ?? []).filter((x) => x !== s) } })} />
                    <Mono>{s}</Mono> <span className="text-slate-500">{SCOPE_HELP[s]}</span>
                  </label>
                ))}
              </fieldset>
            ))}
            <Field label="Max memories per query"><Input type="number" min={1} max={50} value={policy.maxItems ?? 10} onChange={(e) => set({ memoryPolicy: { ...policy, maxItems: Number(e.target.value) } })} /></Field>
            <Field label="Context budget (chars)"><Input type="number" min={200} max={20000} value={policy.maxChars ?? 3000} onChange={(e) => set({ memoryPolicy: { ...policy, maxChars: Number(e.target.value) } })} /></Field>
            <label className="flex items-center gap-2 text-xs"><input type="checkbox" checked={(form.contextPolicy as { includeArtifacts?: boolean })?.includeArtifacts !== false}
              onChange={(e) => set({ contextPolicy: { ...form.contextPolicy, includeArtifacts: e.target.checked } })} /> Include the run's artifact list in context</label>
          </div>
        </Card>
        <Card title="Execution settings, schemas and behaviour">
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="Timeout (s)"><Input type="number" min={1} value={form.timeoutSeconds ?? 60} onChange={(e) => set({ timeoutSeconds: Number(e.target.value) })} /></Field>
            <Field label="Max attempts"><Input type="number" min={1} max={10} value={form.maxAttempts ?? 3} onChange={(e) => set({ maxAttempts: Number(e.target.value) })} /></Field>
            <Field label="Backoff (s)"><Input type="number" min={0} value={form.backoffSeconds ?? 1} onChange={(e) => set({ backoffSeconds: Number(e.target.value) })} /></Field>
          </div>
          <div className="mt-3 grid gap-3 md:grid-cols-2">
            <JsonField label="Input schema" rows={6} value={form.inputSchema} onChange={(v) => set({ inputSchema: v as Record<string, unknown> })} />
            <JsonField label="Output schema" rows={6} value={form.outputSchema} onChange={(v) => set({ outputSchema: v as Record<string, unknown> })} />
          </div>
          <div className="mt-3">
            <JsonField label="Behaviour (executor configuration)" rows={14} value={form.behavior} onChange={(v) => set({ behavior: v as Record<string, unknown> })}
              hint={form.executor === 'template' ? 'operations: [{id, tool, args, forEach?, when?, optional?}], output: {...}, failWhen?: {condition, reason}. Templates: ${input.x}, ${ops.id.field}, ${run.goal}, ${steps.key.output.field}.'
                : form.executor === 'external' ? 'brief: what the external agent should do. The agent completes its handoff with {"output": {...}}.' : undefined} />
          </div>
        </Card>
        <ErrorBox error={error} />
        {report && <ValidationReport report={report} />}
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" busy={busy === 'validate'} onClick={() => void run('validate', async () => setReport(await orchestration.validateAgent(form)))}><ShieldCheck className="h-4 w-4" aria-hidden /> Validate</Button>
          {isAdmin && <Button busy={busy === 'save'} onClick={() => void run('save', async () => {
            const saved = definition ? await orchestration.updateAgent(definition.id, { ...form, expectedLockVersion: definition.lockVersion }) : await orchestration.createAgent(form)
            await onSaved(saved)
          })}><Save className="h-4 w-4" aria-hidden /> {definition ? 'Save new version' : 'Create agent'}</Button>}
          {isAdmin && definition && <Button variant="secondary" busy={busy === 'dup'} onClick={() => void run('dup', async () => onSaved(await orchestration.duplicateAgent(definition.id)))}><Copy className="h-4 w-4" aria-hidden /> Duplicate</Button>}
          {isAdmin && definition && <Button variant="ghost" busy={busy === 'toggle'} onClick={() => void run('toggle', async () => onSaved(await orchestration.setAgentActive(definition.id, definition.status !== 'ACTIVE')))}>
            {definition.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}</Button>}
        </div>
      </div>
      <div className="min-w-0 space-y-6">
        <Card title="What this agent can do">
          <ul className="space-y-1.5 text-sm">
            <li><strong>Executor:</strong> {executor?.title} {executor?.deterministic && <Badge tone="green">deterministic</Badge>} {executor?.external && <Badge tone="violet">external</Badge>}</li>
            <li><strong>Tools:</strong> {form.tools.length ? form.tools.map((t) => <Badge key={t} className="mr-1">{t}</Badge>) : 'none'}</li>
            <li><strong>Reads memory from:</strong> {(policy.read ?? []).join(', ') || 'nothing'}</li>
            <li><strong>Writes memory to:</strong> {(policy.write ?? []).join(', ') || 'nothing'}</li>
            <li><strong>Side effects:</strong> {form.tools.filter((t) => tools.find((x) => x.id === t)?.sideEffects).join(', ') || 'none'}</li>
            <li className="text-xs text-slate-500">Everything else is denied by the tool gateway, whatever the instructions say. No language model is called.</li>
          </ul>
          {definition && <p className="mt-2 text-xs text-slate-500">Principal: {definition.agentName} {definition.agentHasApiKey ? '(has an API key)' : '(no API key; issue one in Agent workspace for external work)'}</p>}
        </Card>
        {definition && (
          <Card title="Versions">
            <ul className="space-y-1 text-xs">{versions.data?.map((v) => <li key={v.version}><Mono>v{v.version}</Mono> <span className="text-slate-500">{timeAgo(v.createdAt)}</span></li>)}</ul>
          </Card>
        )}
      </div>
    </div>
  )
}

function ValidationReport({ report }: { report: AgentValidation }) {
  return (
    <Card title={report.valid ? 'Valid' : 'Not valid'} className={report.valid ? 'border-emerald-300' : 'border-rose-300'}>
      {report.errors.map((e, i) => <div key={i} role="alert" className="mb-1 rounded bg-rose-50 px-2 py-1 text-xs text-rose-800"><Mono>{e.field}</Mono> {e.message}</div>)}
      {report.warnings.map((w) => <div key={w} className="mb-1 rounded bg-amber-50 px-2 py-1 text-xs text-amber-800">{w}</div>)}
      {report.valid && report.warnings.length === 0 && <p className="text-sm text-emerald-700">No problems found.</p>}
    </Card>
  )
}

function Templates({ isAdmin, onInstalled }: { isAdmin: boolean; onInstalled: (d: AgentDefinition) => Promise<void> }) {
  const templates = useAsync(() => orchestration.templates(), [])
  const [busy, setBusy] = useState<string>()
  const [error, setError] = useState<unknown>()
  if (!templates.data) return <Spinner />
  const categories = Array.from(new Set(templates.data.map((t) => t.category)))
  return (
    <div className="space-y-6">
      <ErrorBox error={error} />
      {categories.map((c) => (
        <section key={c}>
          <h2 className="mb-2 text-sm font-semibold capitalize text-slate-700">{c}</h2>
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {templates.data!.filter((t) => t.category === c).map((t) => (
              <div key={t.key} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
                <div className="flex items-start justify-between gap-2"><div><div className="font-semibold">{t.name}</div><div className="text-xs text-slate-500">{t.role}</div></div>
                  {t.installed ? <Badge tone="green">installed</Badge> : <Badge>template</Badge>}</div>
                <p className="mt-2 text-xs text-slate-600">{t.description}</p>
                <div className="mt-2 flex flex-wrap gap-1">{t.tools.map((x) => <Badge key={x}>{x}</Badge>)}</div>
                {isAdmin && !t.installed && <Button className="mt-3" size="sm" variant="secondary" busy={busy === t.key}
                  onClick={async () => { setBusy(t.key); setError(undefined); try { await onInstalled(await orchestration.installTemplate(t.key)) } catch (e) { setError(e) } finally { setBusy(undefined) } }}>
                  <Download className="h-3.5 w-3.5" aria-hidden /> Install</Button>}
              </div>
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}
