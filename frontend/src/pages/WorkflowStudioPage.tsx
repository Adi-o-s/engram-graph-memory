import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { ArrowDown, ArrowUp, Copy, Play, Plus, Save, Trash2 } from 'lucide-react'
import { orchestration } from '../api/orchestration'
import type { AgentDefinition, InputSpec, StepSpec, Workflow, WorkflowSpec, WorkflowValidation } from '../api/orchestration'
import { api } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import { Badge, Button, Card, Empty, ErrorBox, Field, Input, Mono, PageHeader, Select, Spinner, StatusBadge, Textarea } from '../components/ui'
import { JsonField } from '../components/JsonField'
import { WorkflowGraph } from '../components/WorkflowGraph'
import { useAsync } from '../lib/useAsync'
import { cx, timeAgo } from '../lib/format'

interface Draft { id?: string; key?: string; name: string; description?: string; projectId?: string; spec: WorkflowSpec; lockVersion?: number; version?: number }

const EMPTY: Draft = { name: 'New workflow', spec: { inputs: [{ name: 'goal', type: 'text', required: true }], steps: [] } }

export function WorkflowStudioPage() {
  const { workflowId } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const { me } = useAuth()
  const isAdmin = me?.tenant.role === 'ADMIN'
  const workflows = useAsync(() => orchestration.workflows(), [])
  const agents = useAsync(() => orchestration.agentDefinitions(), [])
  const [draft, setDraft] = useState<Draft>()
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState<string>()
  const [jsonMode, setJsonMode] = useState(false)
  const [note, setNote] = useState('')

  useEffect(() => {
    setError(undefined)
    if (workflowId === 'new' || !workflowId) {
      const state = location.state as { spec?: WorkflowSpec; name?: string; description?: string } | null
      if (workflowId === 'new') setDraft(state?.spec ? { name: state.name ?? 'New workflow', description: state.description, spec: state.spec } : structuredClone(EMPTY))
      else setDraft(undefined)
      return
    }
    orchestration.workflow(workflowId).then((w) => setDraft(fromWorkflow(w))).catch(setError)
  }, [workflowId, location.state])

  const validation = useValidation(draft?.spec)

  async function save() {
    if (!draft) return
    setBusy('save')
    setError(undefined)
    try {
      const w = draft.id
        ? await orchestration.updateWorkflow(draft.id, { name: draft.name, description: draft.description, projectId: draft.projectId || undefined, spec: draft.spec, changeNote: note || undefined, expectedLockVersion: draft.lockVersion ?? 0 })
        : await orchestration.createWorkflow({ key: draft.key || undefined, name: draft.name, description: draft.description, projectId: draft.projectId || undefined, spec: draft.spec, changeNote: note || undefined })
      setNote('')
      await workflows.reload()
      setDraft(fromWorkflow(w))
      navigate(`/workflows/${w.id}`, { replace: true })
    } catch (e) {
      setError(e)
    } finally {
      setBusy(undefined)
    }
  }

  async function duplicate() {
    if (!draft?.id) return
    setBusy('dup')
    try {
      const w = await orchestration.duplicateWorkflow(draft.id)
      await workflows.reload()
      navigate(`/workflows/${w.id}`)
    } catch (e) {
      setError(e)
    } finally {
      setBusy(undefined)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader title="Workflow studio"
        description="Design workflows as a dependency graph of steps assigned to agent definitions. Every edit is validated (unknown agents, cycles, references to non-upstream outputs, unbounded loops) and saving creates a new immutable version."
        actions={isAdmin ? <Button onClick={() => navigate('/workflows/new', { state: null })}><Plus className="h-4 w-4" aria-hidden /> New workflow</Button> : undefined} />
      <ErrorBox error={error ?? workflows.error} />
      <div className="grid gap-6 xl:grid-cols-[16rem_minmax(0,1fr)]">
        <nav aria-label="Workflows" className="space-y-1">
          {!workflows.data ? <Spinner /> : workflows.data.map((w) => (
            <Link key={w.id} to={`/workflows/${w.id}`} className={cx('block rounded-lg border px-3 py-2 text-sm', draft?.id === w.id ? 'border-brand-500 bg-brand-50' : 'border-slate-200 bg-white hover:bg-slate-50')}>
              <div className="font-medium">{w.name}</div>
              <div className="flex items-center gap-1 text-[11px] text-slate-500">v{w.version} · {w.stepCount} steps {!w.validation.valid && <Badge tone="red">invalid</Badge>}{w.status === 'ARCHIVED' && <Badge>archived</Badge>}</div>
            </Link>
          ))}
        </nav>
        {!draft ? <Card><Empty>Select a workflow or create a new one. Try <Link className="text-brand-700 underline" to="/plan">Plan a workflow</Link> to start from a goal.</Empty></Card> : (
          <div className="grid min-w-0 gap-6 2xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
            <div className="min-w-0 space-y-6">
              <Card title={draft.id ? <>Editing <Mono>{draft.key}</Mono> v{draft.version}</> : 'New workflow'}
                actions={<label className="flex items-center gap-1 text-xs text-slate-600"><input type="checkbox" checked={jsonMode} onChange={(e) => setJsonMode(e.target.checked)} /> Edit as JSON</label>}>
                <div className="grid gap-3 md:grid-cols-2">
                  <Field label="Name"><Input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></Field>
                  {!draft.id && <Field label="Key" hint="Optional; derived from the name"><Input value={draft.key ?? ''} onChange={(e) => setDraft({ ...draft, key: e.target.value })} placeholder="my-workflow" /></Field>}
                  <div className="md:col-span-2"><Field label="Description"><Textarea rows={2} value={draft.description ?? ''} onChange={(e) => setDraft({ ...draft, description: e.target.value })} /></Field></div>
                </div>
              </Card>
              {jsonMode ? (
                <Card title="Specification (JSON)"><JsonField label="spec" rows={30} value={draft.spec} onChange={(v) => setDraft({ ...draft, spec: v as WorkflowSpec })} /></Card>
              ) : (
                <>
                  <InputsEditor inputs={draft.spec.inputs ?? []} onChange={(inputs) => setDraft({ ...draft, spec: { ...draft.spec, inputs } })} />
                  <StepsEditor steps={draft.spec.steps} agents={agents.data ?? []} onChange={(steps) => setDraft({ ...draft, spec: { ...draft.spec, steps } })} />
                  <CompletionEditor spec={draft.spec} onChange={(spec) => setDraft({ ...draft, spec })} />
                </>
              )}
              {isAdmin && (
                <Card>
                  <div className="flex flex-wrap items-end gap-3">
                    <div className="min-w-64 flex-1"><Field label="Change note"><Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="What changed in this version?" /></Field></div>
                    <Button busy={busy === 'save'} disabled={validation.data ? !validation.data.valid : false} onClick={() => void save()}><Save className="h-4 w-4" aria-hidden /> {draft.id ? 'Save new version' : 'Create workflow'}</Button>
                    {draft.id && <Button variant="secondary" busy={busy === 'dup'} onClick={() => void duplicate()}><Copy className="h-4 w-4" aria-hidden /> Duplicate</Button>}
                  </div>
                </Card>
              )}
            </div>
            <div className="min-w-0 space-y-6">
              <Card title="Dependency graph">
                <WorkflowGraph nodes={draft.spec.steps.map((s) => ({
                  key: s.key, label: s.name || s.key, sublabel: agents.data?.find((a) => a.key === s.agent)?.name ?? s.agent, deps: s.dependsOn ?? [],
                  remediationOf: s.remediation ? draft.spec.steps.find((o) => o.loop?.remediationStep === s.key)?.key : undefined,
                  badges: [s.approval?.required ? 'approval' : '', s.when ? 'conditional' : '', s.loop ? 'loop' : ''].filter(Boolean),
                }))} />
              </Card>
              <ValidationPanel validation={validation.data} loading={validation.loading} />
              {draft.id && <RunPanel workflowId={draft.id} spec={draft.spec} isAdmin={isAdmin} />}
              {draft.id && <VersionsPanel workflowId={draft.id} />}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function fromWorkflow(w: Workflow): Draft {
  return { id: w.id, key: w.key, name: w.name, description: w.description, projectId: w.projectId, spec: w.spec, lockVersion: w.lockVersion, version: w.version }
}

function useValidation(spec?: WorkflowSpec) {
  const [state, setState] = useState<{ data?: WorkflowValidation; loading: boolean }>({ loading: false })
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined)
  const json = JSON.stringify(spec ?? null)
  useEffect(() => {
    if (!spec) return
    setState((s) => ({ ...s, loading: true }))
    clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      orchestration.validateWorkflow(spec).then((data) => setState({ data, loading: false }))
        .catch(() => setState((s) => ({ ...s, loading: false })))
    }, 350)
    return () => clearTimeout(timer.current)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [json])
  return state
}

function ValidationPanel({ validation, loading }: { validation?: WorkflowValidation; loading: boolean }) {
  return (
    <Card title="Validation" actions={loading ? <span className="text-xs text-slate-500">checking…</span> : validation ? <Badge tone={validation.valid ? 'green' : 'red'}>{validation.valid ? 'valid' : `${validation.errors.length} error(s)`}</Badge> : null}>
      {!validation ? <p className="text-xs text-slate-500">Validating…</p> : (
        <div className="space-y-2 text-sm">
          {validation.errors.map((e, i) => <div key={i} role="alert" className="rounded bg-rose-50 px-2 py-1 text-xs text-rose-800"><Mono>{e.path}</Mono> {e.message}</div>)}
          {validation.warnings.map((w) => <div key={w} className="rounded bg-amber-50 px-2 py-1 text-xs text-amber-800">{w}</div>)}
          {validation.analysis && (
            <div className="text-xs text-slate-600">
              <div><strong>Execution layers:</strong> {validation.analysis.layers.map((l) => l.join(' ∥ ')).join(' → ') || '—'}</div>
              <div><strong>Can run in parallel:</strong> {validation.analysis.parallelGroups.length ? validation.analysis.parallelGroups.map((g) => g.join(', ')).join(' · ') : 'none'}</div>
              {validation.analysis.remediationSteps.length > 0 && <div><strong>Remediation (loop-only) steps:</strong> {validation.analysis.remediationSteps.join(', ')}</div>}
            </div>
          )}
        </div>
      )}
    </Card>
  )
}

function InputsEditor({ inputs, onChange }: { inputs: InputSpec[]; onChange: (v: InputSpec[]) => void }) {
  return (
    <Card title="Workflow inputs" actions={<Button size="sm" variant="secondary" onClick={() => onChange([...inputs, { name: 'input' + (inputs.length + 1), type: 'string' }])}><Plus className="h-3.5 w-3.5" aria-hidden /> Add input</Button>}>
      {inputs.length === 0 ? <p className="text-xs text-slate-500">No inputs. Steps can still use <Mono>{'${run.goal}'}</Mono>.</p> : (
        <div className="space-y-2">
          {inputs.map((inp, i) => (
            <div key={i} className="grid grid-cols-[minmax(0,1fr)_8rem_6rem_minmax(0,2fr)_auto] items-end gap-2">
              <Field label="Name"><Input value={inp.name} onChange={(e) => onChange(inputs.map((x, j) => j === i ? { ...x, name: e.target.value } : x))} /></Field>
              <Field label="Type"><Select value={inp.type ?? 'string'} onChange={(e) => onChange(inputs.map((x, j) => j === i ? { ...x, type: e.target.value } : x))}>
                {['string', 'text', 'number', 'boolean', 'object', 'array'].map((t) => <option key={t}>{t}</option>)}</Select></Field>
              <label className="flex items-center gap-1 pb-2 text-xs"><input type="checkbox" checked={!!inp.required} onChange={(e) => onChange(inputs.map((x, j) => j === i ? { ...x, required: e.target.checked } : x))} /> required</label>
              <Field label="Description"><Input value={inp.description ?? ''} onChange={(e) => onChange(inputs.map((x, j) => j === i ? { ...x, description: e.target.value } : x))} /></Field>
              <Button size="sm" variant="ghost" aria-label={`Remove input ${inp.name}`} onClick={() => onChange(inputs.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" aria-hidden /></Button>
            </div>
          ))}
        </div>
      )}
    </Card>
  )
}

function StepsEditor({ steps, agents, onChange }: { steps: StepSpec[]; agents: AgentDefinition[]; onChange: (v: StepSpec[]) => void }) {
  const [open, setOpen] = useState<string | null>(steps[0]?.key ?? null)
  const update = (i: number, s: StepSpec) => onChange(steps.map((x, j) => (j === i ? s : x)))
  const move = (i: number, d: number) => {
    const next = [...steps]
    const [s] = next.splice(i, 1)
    next.splice(i + d, 0, s)
    onChange(next)
  }
  const addStep = () => {
    let n = steps.length + 1
    while (steps.some((s) => s.key === 'step-' + n)) n++
    const key = 'step-' + n
    onChange([...steps, { key, name: 'Step ' + n, agent: agents[0]?.key ?? '', dependsOn: steps.length ? [steps[steps.length - 1].key] : [], inputs: {} }])
    setOpen(key)
  }
  return (
    <Card title={`Steps (${steps.length})`} actions={<Button size="sm" variant="secondary" onClick={addStep}><Plus className="h-3.5 w-3.5" aria-hidden /> Add step</Button>}>
      {steps.length === 0 && <Empty>No steps yet. Add one, or start from <Link className="text-brand-700 underline" to="/plan">a plan</Link>.</Empty>}
      <ol className="space-y-2">
        {steps.map((s, i) => (
          <li key={i} className="rounded-lg border border-slate-200">
            <div className="flex items-center justify-between gap-2 px-3 py-2">
              <button type="button" className="min-w-0 flex-1 text-left" aria-expanded={open === s.key} onClick={() => setOpen(open === s.key ? null : s.key)}>
                <span className="font-medium">{s.name || s.key}</span> <Mono>{s.key}</Mono>{' '}
                <span className="text-xs text-slate-500">{agents.find((a) => a.key === s.agent)?.name ?? s.agent}{s.dependsOn?.length ? ` · after ${s.dependsOn.join(', ')}` : ' · root'}</span>
                {s.remediation && <Badge tone="amber" className="ml-1">remediation</Badge>}{s.loop && <Badge tone="amber" className="ml-1">loop</Badge>}
                {s.approval?.required && <Badge tone="violet" className="ml-1">approval</Badge>}{s.when && <Badge className="ml-1">conditional</Badge>}
              </button>
              <span className="flex shrink-0 gap-1">
                <Button size="sm" variant="ghost" aria-label={`Move ${s.key} up`} disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp className="h-3.5 w-3.5" aria-hidden /></Button>
                <Button size="sm" variant="ghost" aria-label={`Move ${s.key} down`} disabled={i === steps.length - 1} onClick={() => move(i, 1)}><ArrowDown className="h-3.5 w-3.5" aria-hidden /></Button>
                <Button size="sm" variant="ghost" aria-label={`Remove ${s.key}`} onClick={() => onChange(steps.filter((_, j) => j !== i).map((x) => ({ ...x, dependsOn: (x.dependsOn ?? []).filter((d) => d !== s.key) })))}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
              </span>
            </div>
            {open === s.key && <StepEditor step={s} steps={steps} agents={agents} onChange={(n) => { update(i, n); if (n.key !== s.key) setOpen(n.key) }} />}
          </li>
        ))}
      </ol>
    </Card>
  )
}

function StepEditor({ step: s, steps, agents, onChange }: { step: StepSpec; steps: StepSpec[]; agents: AgentDefinition[]; onChange: (s: StepSpec) => void }) {
  const agent = agents.find((a) => a.key === s.agent)
  const others = steps.filter((o) => o.key !== s.key && !o.remediation)
  const remediations = steps.filter((o) => o.remediation && o.key !== s.key)
  return (
    <div className="space-y-3 border-t border-slate-100 p-3">
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="Key"><Input value={s.key} onChange={(e) => onChange({ ...s, key: e.target.value })} /></Field>
        <Field label="Name"><Input value={s.name ?? ''} onChange={(e) => onChange({ ...s, name: e.target.value })} /></Field>
        <Field label="Agent" hint={agent ? `${agent.role} · ${agent.executor}${agent.status === 'INACTIVE' ? ' · inactive' : ''}` : 'Pick an agent definition'}>
          <Select value={s.agent} onChange={(e) => onChange({ ...s, agent: e.target.value })}>
            {!agent && <option value={s.agent}>{s.agent || 'choose…'}</option>}
            {agents.map((a) => <option key={a.key} value={a.key}>{a.name} ({a.key})</option>)}
          </Select>
        </Field>
      </div>
      {!s.remediation && (
        <fieldset className="text-sm">
          <legend className="mb-1 font-medium text-slate-700">Depends on</legend>
          {others.length === 0 ? <span className="text-xs text-slate-500">No other steps.</span> : (
            <div className="flex flex-wrap gap-3">
              {others.map((o) => (
                <label key={o.key} className="flex items-center gap-1 text-xs">
                  <input type="checkbox" checked={(s.dependsOn ?? []).includes(o.key)}
                    onChange={(e) => onChange({ ...s, dependsOn: e.target.checked ? [...(s.dependsOn ?? []), o.key] : (s.dependsOn ?? []).filter((d) => d !== o.key) })} />
                  {o.name || o.key}
                </label>
              ))}
            </div>
          )}
        </fieldset>
      )}
      <JsonField label="Inputs (templates such as ${run.inputs.goal} or ${steps.<key>.output.field})" value={s.inputs ?? {}} rows={5}
        onChange={(v) => onChange({ ...s, inputs: v as Record<string, unknown> })} hint={agent ? `Agent input schema: ${JSON.stringify(agent.inputSchema)}` : undefined} />
      <div className="grid gap-3 md:grid-cols-4">
        <Field label="Max attempts"><Input type="number" min={1} max={10} value={s.retry?.maxAttempts ?? ''} placeholder={String(agent?.maxAttempts ?? 3)}
          onChange={(e) => onChange({ ...s, retry: { ...s.retry, maxAttempts: e.target.value ? Number(e.target.value) : undefined } })} /></Field>
        <Field label="Backoff (s)"><Input type="number" min={0} max={3600} value={s.retry?.backoffSeconds ?? ''} placeholder={String(agent?.backoffSeconds ?? 1)}
          onChange={(e) => onChange({ ...s, retry: { ...s.retry, backoffSeconds: e.target.value ? Number(e.target.value) : undefined } })} /></Field>
        <Field label="Timeout (s)"><Input type="number" min={1} value={s.timeoutSeconds ?? ''} placeholder={String(agent?.timeoutSeconds ?? 60)}
          onChange={(e) => onChange({ ...s, timeoutSeconds: e.target.value ? Number(e.target.value) : undefined })} /></Field>
        <div className="space-y-1 pt-5 text-xs">
          <label className="flex items-center gap-1"><input type="checkbox" checked={s.mandatory !== false} onChange={(e) => onChange({ ...s, mandatory: e.target.checked })} /> mandatory</label>
          <label className="flex items-center gap-1"><input type="checkbox" checked={!!s.remediation} onChange={(e) => onChange({ ...s, remediation: e.target.checked, dependsOn: e.target.checked ? [] : s.dependsOn })} /> remediation step</label>
        </div>
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <div className="space-y-2">
          <label className="flex items-center gap-1 text-sm font-medium"><input type="checkbox" checked={!!s.approval?.required}
            onChange={(e) => onChange({ ...s, approval: e.target.checked ? { required: true, reason: s.approval?.reason } : undefined })} /> Requires human approval</label>
          {s.approval?.required && <Input value={s.approval.reason ?? ''} placeholder="Why approval is needed" onChange={(e) => onChange({ ...s, approval: { required: true, reason: e.target.value } })} />}
          <label className="flex items-center gap-1 text-sm font-medium"><input type="checkbox" checked={!!s.when}
            onChange={(e) => onChange({ ...s, when: e.target.checked ? { path: 'steps.' + (s.dependsOn?.[0] ?? 'x') + '.output.passed', op: 'eq', value: true } : undefined })} /> Run only when…</label>
          {s.when && <JsonField label="Condition" rows={4} value={s.when} onChange={(v) => onChange({ ...s, when: v as Record<string, unknown> })} hint="{path, op, value} or {all|any: [...]}, {not: {...}}" />}
        </div>
        {!s.remediation && (
          <div className="space-y-2">
            <label className="flex items-center gap-1 text-sm font-medium"><input type="checkbox" checked={!!s.loop}
              onChange={(e) => onChange({ ...s, loop: e.target.checked ? { until: { path: 'output.passed', op: 'eq', value: true }, maxIterations: 3, remediationStep: remediations[0]?.key } : undefined })} /> Repeat until… (bounded loop)</label>
            {s.loop && <>
              <JsonField label="Exit condition (on this step's output)" rows={3} value={s.loop.until ?? {}} onChange={(v) => onChange({ ...s, loop: { ...s.loop, until: v as Record<string, unknown> } })} />
              <div className="grid grid-cols-2 gap-2">
                <Field label="Max iterations"><Input type="number" min={1} max={5} value={s.loop.maxIterations ?? 3} onChange={(e) => onChange({ ...s, loop: { ...s.loop, maxIterations: Number(e.target.value) } })} /></Field>
                <Field label="Remediation step"><Select value={s.loop.remediationStep ?? ''} onChange={(e) => onChange({ ...s, loop: { ...s.loop, remediationStep: e.target.value } })}>
                  <option value="">choose…</option>{remediations.map((r) => <option key={r.key} value={r.key}>{r.name || r.key}</option>)}</Select></Field>
              </div>
            </>}
          </div>
        )}
      </div>
    </div>
  )
}

function CompletionEditor({ spec, onChange }: { spec: WorkflowSpec; onChange: (s: WorkflowSpec) => void }) {
  const completion = spec.completion ?? {}
  return (
    <Card title="Completion criteria" actions={<span className="text-xs text-slate-500">A run succeeds only if these hold on the actual outputs</span>}>
      <fieldset className="text-sm">
        <legend className="mb-1 font-medium text-slate-700">Required steps (must SUCCEED, not just be skipped)</legend>
        <div className="flex flex-wrap gap-3">
          {spec.steps.filter((s) => !s.remediation).map((s) => (
            <label key={s.key} className="flex items-center gap-1 text-xs"><input type="checkbox" checked={(completion.requiredSteps ?? []).includes(s.key)}
              onChange={(e) => onChange({ ...spec, completion: { ...completion, requiredSteps: e.target.checked ? [...(completion.requiredSteps ?? []), s.key] : (completion.requiredSteps ?? []).filter((k) => k !== s.key) } })} />{s.name || s.key}</label>
          ))}
        </div>
      </fieldset>
      <div className="mt-3"><JsonField label="Criteria" rows={6} value={completion.criteria ?? []} onChange={(v) => onChange({ ...spec, completion: { ...completion, criteria: v as never } })}
        hint='[{"name": "...", "condition": {"path": "steps.review.output.passed", "op": "eq", "value": true}}]' /></div>
      <div className="mt-3"><JsonField label="Run outputs" rows={3} value={spec.outputs ?? {}} onChange={(v) => onChange({ ...spec, outputs: v as Record<string, unknown> })} /></div>
    </Card>
  )
}

function RunPanel({ workflowId, spec, isAdmin }: { workflowId: string; spec: WorkflowSpec; isAdmin: boolean }) {
  const navigate = useNavigate()
  const projects = useAsync(() => api.projects(), [])
  const hasGoal = spec.inputs?.some((i) => i.name === 'goal')
  const [goal, setGoal] = useState('')
  const [inputs, setInputs] = useState<Record<string, unknown>>({})
  const [projectId, setProjectId] = useState('')
  const [pacing, setPacing] = useState(400)
  const [faults, setFaults] = useState<Record<string, unknown>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const otherInputs = useMemo(() => (spec.inputs ?? []).filter((i) => i.name !== 'goal'), [spec.inputs])
  return (
    <Card title="Run this workflow">
      <div className="space-y-3">
        {hasGoal && <Field label="Goal"><Textarea rows={3} value={goal} onChange={(e) => setGoal(e.target.value)} /></Field>}
        {otherInputs.length > 0 && <JsonField label={`Inputs (${otherInputs.map((i) => i.name).join(', ')})`} rows={4} value={inputs} onChange={(v) => setInputs(v as Record<string, unknown>)} />}
        <Field label="Project"><Select value={projectId} onChange={(e) => setProjectId(e.target.value)}>
          <option value="">New project for this run</option>{projects.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</Select></Field>
        <Field label={`Pacing: ${pacing} ms per step`} hint="A visible delay so you can watch parallel steps; 0 runs at full speed">
          <input type="range" min={0} max={3000} step={100} value={pacing} onChange={(e) => setPacing(Number(e.target.value))} className="w-full" /></Field>
        {isAdmin && <JsonField label="Fault injection (demo, admins)" rows={3} value={faults} onChange={(v) => setFaults(v as Record<string, unknown>)}
          hint='e.g. {"backend": {"failAttempts": 1}} makes the first attempt of backend fail so you can watch a retry' />}
        <ErrorBox error={error} />
        <Button busy={busy} onClick={async () => {
          setBusy(true)
          setError(undefined)
          try {
            const { runId } = await orchestration.startRun(workflowId, {
              goal: hasGoal ? goal : undefined, inputs: Object.keys(inputs).length ? inputs : undefined, projectId: projectId || undefined,
              options: { pacingMs: pacing, ...(Object.keys(faults).length ? { faults } : {}) },
            })
            navigate(`/runs/${runId}`)
          } catch (e) {
            setError(e)
          } finally {
            setBusy(false)
          }
        }}><Play className="h-4 w-4" aria-hidden /> Start run</Button>
      </div>
    </Card>
  )
}

function VersionsPanel({ workflowId }: { workflowId: string }) {
  const versions = useAsync(() => orchestration.workflowVersions(workflowId), [workflowId])
  const runs = useAsync(() => orchestration.runs({ workflowId }), [workflowId])
  return (
    <Card title="Versions and recent runs">
      <ul className="space-y-1 text-xs">
        {versions.data?.map((v) => <li key={v.version}><Mono>v{v.version}</Mono> {v.changeNote ?? ''} <span className="text-slate-400">{timeAgo(v.createdAt)}</span></li>)}
      </ul>
      <ul className="mt-3 space-y-1 text-xs">
        {runs.data?.items.slice(0, 8).map((r) => <li key={r.id} className="flex items-center justify-between gap-2"><Link className="truncate text-brand-700 hover:underline" to={`/runs/${r.id}`}>{r.name}</Link><StatusBadge value={r.status} /></li>)}
      </ul>
    </Card>
  )
}
