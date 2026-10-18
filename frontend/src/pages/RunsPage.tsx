import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { CheckCircle2, CircleSlash, Pause, Play, RotateCcw, Square, XCircle } from 'lucide-react'
import { orchestration } from '../api/orchestration'
import type { Artifact, RunView, StepRunView } from '../api/orchestration'
import { useAuth } from '../auth/AuthContext'
import { Badge, Button, Card, Empty, ErrorBox, JsonView, Mono, PageHeader, Spinner, StatusBadge } from '../components/ui'
import { WorkflowGraph, type GraphNode } from '../components/WorkflowGraph'
import { RunTimeline } from '../components/RunTimeline'
import { useAsync, useInterval } from '../lib/useAsync'
import { dateTime, shortId, timeAgo } from '../lib/format'

const ACTIVE = new Set(['PENDING', 'RUNNING', 'WAITING_APPROVAL', 'BLOCKED', 'PAUSED'])

export function RunsPage() {
  const { runId } = useParams()
  return runId ? <RunDetail runId={runId} /> : <RunList />
}

function RunList() {
  const runs = useAsync(() => orchestration.runs(), [])
  const workflows = useAsync(() => orchestration.workflows(), [])
  const names = new Map((workflows.data ?? []).map((w) => [w.id, w.name]))
  useInterval(() => void runs.reload(), runs.data?.items.some((r) => ACTIVE.has(r.status)) ? 2000 : null)
  return (
    <div className="space-y-6">
      <PageHeader title="Workflow runs" description="Every run is persisted step by step. Open a run to watch its graph, timeline, inputs, outputs, tool calls and artifacts."
        actions={<Link to="/plan"><Button>Plan a new workflow</Button></Link>} />
      <ErrorBox error={runs.error} />
      {!runs.data ? <Spinner /> : runs.data.items.length === 0 ? (
        <Card><Empty>No runs yet. Start one from <Link className="text-brand-700 underline" to="/workflows">Workflow studio</Link> or <Link className="text-brand-700 underline" to="/plan">Plan a workflow</Link>.</Empty></Card>
      ) : (
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
              <tr><th className="px-4 py-2">Run</th><th className="px-4 py-2">Workflow</th><th className="px-4 py-2">Status</th><th className="px-4 py-2">Steps</th><th className="px-4 py-2">Started</th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {runs.data.items.map((r) => (
                <tr key={r.id} className="hover:bg-slate-50">
                  <td className="max-w-md px-4 py-2"><Link className="font-medium text-brand-700 hover:underline" to={`/runs/${r.id}`}>{r.name}</Link></td>
                  <td className="px-4 py-2 text-xs text-slate-600">{names.get(r.workflowId) ?? shortId(r.workflowId)} v{r.workflowVersion}</td>
                  <td className="px-4 py-2"><StatusBadge value={r.status} /></td>
                  <td className="px-4 py-2"><div className="flex flex-wrap gap-1">{Object.entries(r.stepCounts).map(([k, v]) => <Badge key={k} tone={toneOf(k)}>{v} {k.toLowerCase().replace('_', ' ')}</Badge>)}</div></td>
                  <td className="px-4 py-2 text-xs text-slate-500">{timeAgo(r.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function toneOf(status: string) {
  return status === 'SUCCEEDED' ? 'green' : status === 'FAILED' ? 'red' : status === 'RUNNING' || status === 'READY' ? 'blue'
    : status === 'WAITING_APPROVAL' || status === 'BLOCKED' ? 'amber' : 'gray'
}

function RunDetail({ runId }: { runId: string }) {
  const { me } = useAuth()
  const isAdmin = me?.tenant.role === 'ADMIN'
  const navigate = useNavigate()
  const run = useAsync(() => orchestration.run(runId), [runId])
  const artifacts = useAsync(() => orchestration.runArtifacts(runId), [runId])
  const events = useAsync(() => orchestration.runEvents(runId), [runId])
  const [selected, setSelected] = useState<string>()
  const [busy, setBusy] = useState<string>()
  const [error, setError] = useState<unknown>()
  const [viewing, setViewing] = useState<Artifact>()
  const active = run.data ? ACTIVE.has(run.data.status) : true
  useInterval(() => { void run.reload(); void artifacts.reload(); void events.reload() }, active ? 1000 : null)

  const r = run.data
  const latest = useMemo(() => latestSteps(r), [r])
  const selectedStep = r?.steps.find((s) => s.id === selected)

  async function act(label: string, fn: () => Promise<unknown>) {
    setBusy(label)
    setError(undefined)
    try {
      await fn()
      await Promise.all([run.reload(), events.reload(), artifacts.reload()])
    } catch (e) {
      setError(e)
    } finally {
      setBusy(undefined)
    }
  }

  if (run.error) return <ErrorBox error={run.error} />
  if (!r) return <Spinner />

  const nodes: GraphNode[] = r.spec.steps.map((s) => {
    const its = r.steps.filter((x) => x.key === s.key)
    const last = latest.get(s.key)
    const agent = r.agents.find((a) => a.key === s.agent)
    const badges: string[] = []
    if (s.approval?.required) badges.push('approval')
    if (agent?.executor === 'external') badges.push('external')
    if (s.when) badges.push('conditional')
    return {
      key: s.key, label: s.name ?? s.key, sublabel: agent?.name ?? s.agent, status: last?.status ?? (s.remediation ? undefined : 'PENDING'),
      deps: s.dependsOn ?? [], iterations: its.length, badges,
      remediationOf: s.remediation ? r.spec.steps.find((o) => o.loop?.remediationStep === s.key)?.key : undefined,
    }
  })
  const pending = r.steps.filter((s) => s.status === 'WAITING_APPROVAL')

  return (
    <div className="space-y-6">
      <PageHeader
        title={r.name}
        description={<>Workflow <Mono>{r.workflowKey}</Mono> v{r.workflowVersion} · started {dateTime(r.createdAt)} · project <Link className="text-brand-700 hover:underline" to={`/workspace?project=${r.projectId}`}>workspace</Link></>}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge value={r.status} />
            {['RUNNING', 'WAITING_APPROVAL', 'BLOCKED'].includes(r.status) && (
              <Button size="sm" variant="secondary" busy={busy === 'pause'} onClick={() => void act('pause', () => orchestration.runAction(runId, 'pause'))}>
                <Pause className="h-3.5 w-3.5" aria-hidden /> Pause</Button>)}
            {['PAUSED', 'BLOCKED', 'FAILED'].includes(r.status) && (
              <Button size="sm" variant="secondary" busy={busy === 'resume'} onClick={() => void act('resume', () => orchestration.runAction(runId, 'resume'))}>
                <Play className="h-3.5 w-3.5" aria-hidden /> Resume</Button>)}
            {ACTIVE.has(r.status) && (
              <Button size="sm" variant="danger" busy={busy === 'cancel'} onClick={() => void act('cancel', () => orchestration.runAction(runId, 'cancel'))}>
                <Square className="h-3.5 w-3.5" aria-hidden /> Cancel</Button>)}
            <Button size="sm" variant="ghost" onClick={() => navigate('/runs')}>All runs</Button>
          </div>
        }
      />
      <ErrorBox error={error} />
      <ExecutionNote run={r} />

      {pending.length > 0 && (
        <Card title="Waiting for approval" className="border-amber-300">
          <ul className="space-y-2">
            {pending.map((s) => (
              <li key={s.id} className="flex flex-wrap items-center justify-between gap-2 text-sm">
                <span><strong>{s.name}</strong> · {s.approval?.reason ?? 'Approval required'}</span>
                {isAdmin ? (
                  <span className="flex gap-2">
                    <Button size="sm" busy={busy === 'approve' + s.id} onClick={() => void act('approve' + s.id, () => orchestration.stepAction(runId, s.id, 'approve', 'Approved in the dashboard'))}>Approve</Button>
                    <Button size="sm" variant="secondary" busy={busy === 'reject' + s.id} onClick={() => void act('reject' + s.id, () => orchestration.stepAction(runId, s.id, 'reject', 'Rejected in the dashboard'))}>Reject</Button>
                  </span>
                ) : <span className="text-xs text-slate-500">An admin must approve this step.</span>}
              </li>
            ))}
          </ul>
        </Card>
      )}

      {r.completion && <CompletionCard run={r} />}

      <div className="grid gap-6 2xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <div className="space-y-6 min-w-0">
          <Card title="Execution graph" actions={<span className="text-xs text-slate-500">Click a step to inspect it</span>}>
            <WorkflowGraph nodes={nodes} selected={selectedStep?.key} onSelect={(k) => setSelected(latest.get(k)?.id)} />
            <div className="mt-3 flex flex-wrap gap-2 text-[11px] text-slate-500">
              {['PENDING', 'READY', 'RUNNING', 'WAITING_APPROVAL', 'BLOCKED', 'SUCCEEDED', 'FAILED', 'SKIPPED'].map((s) => <StatusBadge key={s} value={s} />)}
              <span>· dashed amber arrow = bounded remediation loop · ×n = iterations</span>
            </div>
          </Card>
          <Card title="Timeline (actual start and finish times)">
            <RunTimeline steps={r.steps} onSelect={(s) => setSelected(s.id)} selected={selected} />
            <p className="mt-2 text-xs text-slate-500">Overlapping bars ran in parallel on separate workers.</p>
          </Card>
          <Card title="Steps (accessible list)">
            <ul className="divide-y divide-slate-100 text-sm">
              {r.steps.map((s) => (
                <li key={s.id}>
                  <button type="button" onClick={() => setSelected(s.id)} className="flex w-full items-center justify-between gap-2 px-1 py-1.5 text-left hover:bg-slate-50">
                    <span>{s.name}{s.iteration > 1 || s.remediation ? ` (iteration ${s.iteration})` : ''} <span className="text-xs text-slate-500">· {s.agentName}</span></span>
                    <span className="flex items-center gap-2"><Mono>{s.attempts}/{s.maxAttempts}</Mono><StatusBadge value={s.status} /></span>
                  </button>
                </li>
              ))}
            </ul>
          </Card>
        </div>
        <div className="space-y-6 min-w-0">
          {selectedStep ? <StepInspector runId={runId} step={selectedStep} run={r} isAdmin={isAdmin} onAction={act} busy={busy} onView={setViewing} />
            : <Card title="Step inspector"><Empty>Select a step in the graph, the timeline or the list.</Empty></Card>}
          <Card title={`Artifacts (${artifacts.data?.length ?? 0})`}>
            {!artifacts.data ? <Spinner /> : artifacts.data.length === 0 ? <Empty>No artifacts yet.</Empty> : (
              <ul className="divide-y divide-slate-100 text-sm">
                {artifacts.data.map((a) => (
                  <li key={a.id} className="flex items-center justify-between gap-2 py-1.5">
                    <button type="button" className="truncate text-left font-medium text-brand-700 hover:underline" onClick={() => void orchestration.artifact(a.id).then(setViewing)}>{a.name}</button>
                    <span className="flex shrink-0 items-center gap-2 text-xs text-slate-500">v{a.version} · {a.stepKey} · {(a.sizeBytes / 1024).toFixed(1)} KB</span>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card title="Events">
            <ol className="max-h-96 space-y-1 overflow-y-auto text-xs">
              {(events.data ?? []).slice().reverse().map((e) => (
                <li key={e.id} className="rounded bg-slate-50 px-2 py-1">
                  <span className="font-mono text-[10px] text-slate-400">{new Date(e.createdAt).toLocaleTimeString()}</span>{' '}
                  <Badge tone={e.type.includes('fail') || e.type.includes('expired') ? 'red' : e.type.includes('retry') || e.type.includes('loop') ? 'amber' : 'gray'}>{e.type}</Badge>{' '}
                  {e.message}
                </li>
              ))}
            </ol>
          </Card>
        </div>
      </div>
      {viewing && <ArtifactViewer artifact={viewing} onClose={() => setViewing(undefined)} />}
    </div>
  )
}

function latestSteps(r?: RunView) {
  const m = new Map<string, StepRunView>()
  r?.steps.forEach((s) => {
    const cur = m.get(s.key)
    if (!cur || s.iteration >= cur.iteration) m.set(s.key, s)
  })
  return m
}

function ExecutionNote({ run }: { run: RunView }) {
  const executors = new Set(run.agents.map((a) => a.executor))
  const faults = run.options?.faults ? Object.keys(run.options.faults as object) : []
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-3 text-xs text-slate-600 shadow-sm">
      <strong className="text-slate-800">How this run executes: </strong>
      {executors.has('template') && <span>Memora's agents use the <Badge tone="indigo">deterministic template</Badge> executor: configured tool calls, no language model. </span>}
      {executors.has('external') && <span><Badge tone="violet">external</Badge> steps wait for their agent to report back through its inbox. </span>}
      <span>Every tool call is authorised against the agent's pinned definition and recorded.</span>
      {run.options?.pacingMs ? <span> Pacing of {String(run.options.pacingMs)} ms per step is on so parallel work is visible.</span> : null}
      {faults.length > 0 && <span> <Badge tone="amber">Injected faults</Badge> on {faults.join(', ')} demonstrate retries; they are labelled in the events.</span>}
    </div>
  )
}

function CompletionCard({ run }: { run: RunView }) {
  const c = run.completion!
  return (
    <Card title={c.passed ? 'Completion criteria met' : 'Completion criteria not met'} className={c.passed ? 'border-emerald-300' : 'border-rose-300'}>
      <p className="text-sm text-slate-700">{c.summary}</p>
      {c.criteria && c.criteria.length > 0 && (
        <ul className="mt-3 space-y-1.5 text-sm">
          {c.criteria.map((k) => (
            <li key={k.name} className="flex items-start gap-2">
              {k.passed ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" aria-label="passed" /> : <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-rose-500" aria-label="failed" />}
              <div><div>{k.name}</div><div className="font-mono text-[11px] text-slate-500">{k.trace.join('; ')}</div></div>
            </li>
          ))}
        </ul>
      )}
      {c.outstanding && c.outstanding.length > 0 && (
        <div className="mt-3 text-xs text-slate-600"><strong>Outstanding:</strong> {c.outstanding.map((o) => `${o.name} (${o.status.toLowerCase()})`).join(', ')}</div>
      )}
      {run.outputs && Object.keys(run.outputs).length > 0 && <JsonView className="mt-3" value={run.outputs} />}
    </Card>
  )
}

function StepInspector({ runId, step, run, isAdmin, onAction, busy, onView }: {
  runId: string; step: StepRunView; run: RunView; isAdmin: boolean; busy?: string
  onAction: (label: string, fn: () => Promise<unknown>) => Promise<void>; onView: (a: Artifact) => void
}) {
  const detail = useAsync(() => orchestration.step(runId, step.id), [runId, step.id, step.status, step.attempts])
  const reason = step.readyReason as { dependencies?: { step: string; iteration: number; status: string }[]; condition?: { result: boolean; trace: string[] };
    approval?: string; manualRetry?: boolean; by?: string; note?: string } | undefined
  const agent = run.agents.find((a) => a.key === step.agentKey)
  const log = detail.data?.events.filter((e) => e.type === 'step_log').flatMap((e) => (e.payload.lines as string[]) ?? []) ?? []
  return (
    <Card title={<span>{step.name}{step.iteration > 1 || step.remediation ? ` · iteration ${step.iteration}` : ''}</span>}
      actions={<StatusBadge value={step.status} />}>
      <dl className="grid grid-cols-[8rem_minmax(0,1fr)] gap-y-1 text-xs">
        <dt className="text-slate-500">Agent</dt><dd>{agent?.name} <span className="text-slate-500">({agent?.role}, v{step.agentVersion})</span></dd>
        <dt className="text-slate-500">Executor</dt><dd><Badge tone={step.executor === 'external' ? 'violet' : 'indigo'}>{step.executor}</Badge></dd>
        <dt className="text-slate-500">Attempts</dt><dd>{step.attempts} of {step.maxAttempts}{step.nextAttemptAt && step.status === 'READY' ? ` · next attempt ${timeAgo(step.nextAttemptAt)}` : ''}</dd>
        <dt className="text-slate-500">Timing</dt><dd>{step.startedAt ? dateTime(step.startedAt) : 'not started'}{step.durationMs != null ? ` · ${(step.durationMs / 1000).toFixed(2)} s` : ''}</dd>
        {step.taskId && <><dt className="text-slate-500">Task</dt><dd><Link to="/tasks" className="text-brand-700 hover:underline"><Mono>{shortId(step.taskId)}</Mono></Link>{step.handoffId && <> · handoff <Mono>{shortId(step.handoffId)}</Mono></>}</dd></>}
      </dl>
      <div className="mt-3 rounded-lg bg-slate-50 p-2 text-xs">
        <div className="font-semibold text-slate-700">Why this step {step.status === 'SKIPPED' ? 'was skipped' : step.status === 'PENDING' || step.status === 'BLOCKED' ? 'is waiting' : 'was eligible to run'}</div>
        {!reason ? <div className="text-slate-500">{step.status === 'BLOCKED' ? step.error : 'Its dependencies have not all finished yet: ' + step.dependsOn.join(', ')}</div> : (
          <ul className="mt-1 space-y-0.5">
            {(reason.dependencies ?? []).length === 0 && !reason.manualRetry && <li>No dependencies (a root step or a loop iteration).</li>}
            {(reason.dependencies ?? []).map((d) => <li key={d.step + d.iteration}>Dependency <Mono>{d.step}{d.iteration > 1 ? '#' + d.iteration : ''}</Mono> was <StatusBadge value={d.status} /></li>)}
            {reason.condition && <li>Condition {reason.condition.result ? 'true' : 'false'}: <Mono>{reason.condition.trace.join('; ')}</Mono></li>}
            {reason.approval && <li>Approval: {reason.approval}</li>}
            {reason.manualRetry && <li>Manual retry requested by {reason.by}</li>}
          </ul>
        )}
      </div>
      {step.error && <div className="mt-3 rounded-lg border border-rose-200 bg-rose-50 p-2 text-xs text-rose-800"><strong>{step.errorKind?.toLowerCase().replace('_', ' ')}:</strong> {step.error}</div>}
      <div className="mt-3 flex flex-wrap gap-2">
        {step.canRetry && <Button size="sm" busy={busy === 'retry'} onClick={() => void onAction('retry', () => orchestration.stepAction(runId, step.id, 'retry'))}><RotateCcw className="h-3.5 w-3.5" aria-hidden /> Retry step</Button>}
        {step.status === 'WAITING_APPROVAL' && isAdmin && <>
          <Button size="sm" onClick={() => void onAction('approve', () => orchestration.stepAction(runId, step.id, 'approve', 'Approved in the inspector'))}><CheckCircle2 className="h-3.5 w-3.5" aria-hidden /> Approve</Button>
          <Button size="sm" variant="secondary" onClick={() => void onAction('reject', () => orchestration.stepAction(runId, step.id, 'reject', 'Rejected in the inspector'))}><CircleSlash className="h-3.5 w-3.5" aria-hidden /> Reject</Button>
        </>}
      </div>
      <details className="mt-3" open><summary className="cursor-pointer text-xs font-semibold text-slate-700">Input</summary><JsonView className="mt-1 max-h-60" value={step.input ?? {}} /></details>
      <details className="mt-2" open={step.status === 'SUCCEEDED'}><summary className="cursor-pointer text-xs font-semibold text-slate-700">Output</summary><JsonView className="mt-1 max-h-72" value={step.output ?? {}} /></details>
      <details className="mt-2"><summary className="cursor-pointer text-xs font-semibold text-slate-700">Tool calls ({detail.data?.toolCalls.length ?? 0})</summary>
        <ul className="mt-1 space-y-1 text-xs">
          {detail.data?.toolCalls.map((t) => (
            <li key={t.id} className="rounded bg-slate-50 p-1.5">
              <div className="flex items-center justify-between gap-2"><Mono>{t.toolId}</Mono><span className="flex items-center gap-1"><Mono>{t.durationMs} ms</Mono><Badge tone={t.status === 'SUCCEEDED' ? 'green' : 'red'}>{t.status.toLowerCase()}</Badge></span></div>
              {t.error && <div className="text-rose-700">{t.error}</div>}
              <div className="truncate text-slate-500">in: {JSON.stringify(t.input)}</div>
            </li>
          ))}
        </ul>
      </details>
      <details className="mt-2"><summary className="cursor-pointer text-xs font-semibold text-slate-700">Log ({log.length})</summary>
        <pre className="code-block mt-1 max-h-60">{log.join('\n') || '(no log)'}</pre>
      </details>
      {detail.data && detail.data.artifacts.length > 0 && (
        <div className="mt-2 text-xs"><span className="font-semibold text-slate-700">Artifacts: </span>
          {detail.data.artifacts.map((a) => <button key={a.id} type="button" className="mr-2 text-brand-700 hover:underline" onClick={() => void orchestration.artifact(a.id).then(onView)}>{a.name} v{a.version}</button>)}
        </div>
      )}
    </Card>
  )
}

function ArtifactViewer({ artifact, onClose }: { artifact: Artifact; onClose: () => void }) {
  return (
    <div role="dialog" aria-modal="true" aria-label={artifact.name} className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4" onClick={onClose}>
      <div className="max-h-[90vh] w-full max-w-4xl overflow-hidden rounded-xl bg-white shadow-xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
          <div>
            <div className="font-semibold">{artifact.name} <span className="text-xs font-normal text-slate-500">v{artifact.version} · {artifact.kind} · {artifact.stepKey}</span></div>
            <div className="font-mono text-[11px] text-slate-500">sha256 {artifact.checksum}</div>
          </div>
          <Button size="sm" variant="ghost" onClick={onClose}>Close</Button>
        </div>
        <pre className="code-block m-0 max-h-[75vh] rounded-none">{artifact.content}</pre>
      </div>
    </div>
  )
}
