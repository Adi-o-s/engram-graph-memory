import { useEffect, useState } from 'react'
import { CheckCircle2, Circle, CircleDot } from 'lucide-react'
import { api } from '../api/endpoints'
import type { RunStatus, StepResult, TeachResult } from '../api/types'
import { Badge, Button, Card, Empty, ErrorBox, JsonView, Mono, PageHeader, Select, Spinner, StatusBadge } from '../components/ui'
import { MemoryCard } from '../components/MemoryCard'
import { GraphWalkthrough } from '../components/GraphWalkthrough'
import { useAsync } from '../lib/useAsync'
import { cx, dateTime, shortId, timeAgo } from '../lib/format'

type StepKey = 'teach' | 'plan' | 'develop' | 'test' | 'retry'

const STEPS: { key: StepKey; title: string; description: string }[] = [
  { key: 'teach', title: '1. Teach', description: 'A user tells the project six things in a conversation. The rule-based extractor turns them into memories, and "switched from MySQL to PostgreSQL" supersedes the MySQL fact.' },
  { key: 'plan', title: '2. Plan', description: 'The Planner agent reads shared memory, creates a task with requirements and hands it to the Developer.' },
  { key: 'develop', title: '3. Develop', description: 'The Developer acknowledges the handoff, builds a bounded context (it sees PostgreSQL, not MySQL), records its work in shared task state and hands off to the Tester.' },
  { key: 'test', title: '4. Test (first attempt fails)', description: 'The Tester claims the handoff, hits a simulated environment failure, records a lesson and fails the handoff so it can be retried.' },
  { key: 'retry', title: '5. Retry', description: 'The Tester retries the failed handoff, verifies the work against the current database fact and completes the task.' },
]

function stepState(status: RunStatus | undefined, key: StepKey): 'done' | 'next' | 'todo' {
  if (!status) return key === 'teach' ? 'next' : 'todo'
  const handoffs = status.handoffs
  const toTester = handoffs[1]
  const done: Record<StepKey, boolean> = {
    teach: true,
    plan: handoffs.length >= 1,
    develop: handoffs.length >= 2,
    test: !!toTester && toTester.attempts >= 1,
    retry: status.task?.status === 'DONE',
  }
  if (done[key]) return 'done'
  const order: StepKey[] = ['teach', 'plan', 'develop', 'test', 'retry']
  const firstOpen = order.find((k) => !done[k])
  return firstOpen === key ? 'next' : 'todo'
}

export function GuidedDemoPage() {
  const runs = useAsync(() => api.demoRuns(), [])
  const agents = useAsync(() => api.agents(), [])
  const [runId, setRunId] = useState<string>()
  const [teach, setTeach] = useState<TeachResult>()
  const [results, setResults] = useState<Partial<Record<StepKey, StepResult>>>({})
  const [busy, setBusy] = useState<StepKey | null>(null)
  const [error, setError] = useState<unknown>()
  const status = useAsync<RunStatus | undefined>(() => (runId ? api.demoStatus(runId) : Promise.resolve(undefined)), [runId])

  useEffect(() => {
    if (!runId && runs.data && runs.data.length > 0) setRunId(runs.data[0].id)
  }, [runs.data, runId])

  async function run(key: StepKey) {
    setBusy(key)
    setError(undefined)
    try {
      if (key === 'teach') {
        const t = await api.startDemo()
        setTeach(t)
        setResults({})
        setRunId(t.project.id)
        await runs.reload()
      } else if (runId) {
        const step = key === 'retry' ? 'test' : key
        const r = await api.demoStep(runId, step, true)
        setResults((prev) => ({ ...prev, [key]: r }))
        await status.reload()
      }
    } catch (e) {
      setError(e)
      await status.reload()
    } finally {
      setBusy(null)
    }
  }

  const s = status.data
  const agentNames = new Map((agents.data ?? []).map((a) => [a.id, a.name]))

  return (
    <div className="space-y-6">
      <PageHeader
        title="Guided demo"
        description="Start with the graph walkthrough: a conversation becomes a FalkorDB memory graph, an agent gets context with paths, a changed fact alerts the agent that read it, and tenants have separate graphs. Below it, three deterministic agents (no LLM) coordinate through the same APIs."
        actions={
          runs.data && runs.data.length > 0 ? (
            <Select aria-label="Demo run" value={runId ?? ''} onChange={(e) => { setRunId(e.target.value); setResults({}); setTeach(undefined) }} className="w-64">
              {runs.data.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </Select>
          ) : null
        }
      />
      <GraphWalkthrough />
      <div>
        <h2 className="text-base font-semibold">Orchestration demo (Planner → Developer → Tester)</h2>
        <p className="text-xs text-slate-500">Every step writes to PostgreSQL; the panel on the right reads it back.</p>
      </div>
      <ErrorBox error={error} />
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div className="space-y-3">
          {STEPS.map((step) => {
            const state = step.key === 'teach' ? (runId ? 'done' : 'next') : stepState(s, step.key)
            const result = results[step.key]
            const Icon = state === 'done' ? CheckCircle2 : state === 'next' ? CircleDot : Circle
            return (
              <div key={step.key} className={cx('rounded-xl border bg-white p-4 shadow-sm', state === 'next' ? 'border-brand-500' : 'border-slate-200')}>
                <div className="flex items-start gap-3">
                  <Icon className={cx('mt-0.5 h-5 w-5 shrink-0', state === 'done' ? 'text-emerald-500' : state === 'next' ? 'text-brand-600' : 'text-slate-300')} aria-hidden />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <h3 className="text-sm font-semibold">{step.title}</h3>
                      <Button
                        size="sm"
                        variant={state === 'next' ? 'primary' : 'secondary'}
                        busy={busy === step.key}
                        disabled={busy !== null || (step.key !== 'teach' && (!runId || state === 'todo'))}
                        onClick={() => void run(step.key)}
                      >
                        {step.key === 'teach' ? (runId ? 'Start a new run' : 'Start demo') : state === 'done' ? 'Run again' : 'Run step'}
                      </Button>
                    </div>
                    <p className="mt-1 text-xs text-slate-500">{step.description}</p>
                    {step.key === 'teach' && teach && (
                      <ul className="mt-3 space-y-2">
                        {teach.messages.map((m) => (
                          <li key={m.text} className="rounded-lg bg-slate-50 p-2 text-xs">
                            <div className="font-medium text-slate-700">“{m.text}”</div>
                            {m.extracted.length === 0 ? <div className="text-slate-400">no memory extracted</div> : m.extracted.map((x) => (
                              <div key={x.memory.id} className="mt-1 flex flex-wrap items-center gap-1.5">
                                <StatusBadge value={x.outcome} /> <StatusBadge value={x.memory.type} />
                                <span className="text-slate-600">{x.memory.content}</span>
                                {x.rule && <Mono className="text-[10px]">rule {x.rule}</Mono>}
                              </div>
                            ))}
                          </li>
                        ))}
                      </ul>
                    )}
                    {result && (
                      <div className="mt-3 rounded-lg bg-slate-50 p-2 text-xs">
                        <div className="mb-1 flex items-center gap-2">
                          <span className="font-medium">{result.agent}</span>
                          <Badge tone={result.outcome.startsWith('FAILED') ? 'amber' : 'green'}>{result.outcome}</Badge>
                        </div>
                        <ol className="list-decimal space-y-0.5 pl-4 text-slate-600">
                          {result.log.map((line, i) => <li key={i}>{line}</li>)}
                        </ol>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )
          })}
        </div>

        <div className="space-y-4">
          {!runId ? (
            <Card title="Run state"><Empty>Start the demo to create a sandbox project.</Empty></Card>
          ) : !s ? (
            status.error ? <ErrorBox error={status.error} /> : <Spinner />
          ) : (
            <>
              <Card
                title="Task"
                actions={<span className="text-xs text-slate-500">next step: <Badge tone="indigo">{s.nextStep}</Badge></span>}
              >
                {!s.task ? <Empty>No task yet. Run the Planner.</Empty> : (
                  <div className="space-y-2 text-sm">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{s.task.title}</span>
                      <StatusBadge value={s.task.status} />
                      <Badge>version {s.task.version}</Badge>
                    </div>
                    <details>
                      <summary className="cursor-pointer text-xs text-slate-500">Shared task state (JSONB)</summary>
                      <JsonView value={s.task.state} className="mt-2" />
                    </details>
                  </div>
                )}
              </Card>
              <Card title="Handoffs">
                {s.handoffs.length === 0 ? <Empty>No handoffs yet.</Empty> : (
                  <ul className="space-y-2">
                    {s.handoffs.map((h) => (
                      <li key={h.id} className="rounded-lg border border-slate-200 p-2 text-xs">
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-medium">{agentNames.get(h.fromAgentId) ?? shortId(h.fromAgentId)} → {agentNames.get(h.toAgentId) ?? shortId(h.toAgentId)}</span>
                          <StatusBadge value={h.status} />
                        </div>
                        <div className="mt-1 text-slate-600">{h.summary}</div>
                        <div className="mt-0.5 text-slate-500">next action: {h.nextAction}</div>
                        <div className="mt-1 text-slate-400">
                          attempts {h.attempts}/{h.maxAttempts} · task v{h.taskVersion} · {h.memoryRefs.length} memory refs
                          {h.failureReason ? ` · failed: ${h.failureReason}` : ''}
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
              <Card title={`Task events (${s.events.length})`}>
                {s.events.length === 0 ? <Empty>No events yet.</Empty> : (
                  <ol className="space-y-1 text-xs">
                    {s.events.map((e) => (
                      <li key={e.id} className="flex flex-wrap items-center gap-1.5">
                        <span className="text-slate-400" title={dateTime(e.createdAt)}>{timeAgo(e.createdAt)}</span>
                        <Mono>{e.eventType}</Mono>
                        {e.fromStatus && e.toStatus && e.fromStatus !== e.toStatus && <span>{e.fromStatus} → {e.toStatus}</span>}
                        {e.taskVersion != null && <span className="text-slate-400">v{e.taskVersion}</span>}
                        <span className="text-slate-400">by {e.actorType.toLowerCase()}</span>
                      </li>
                    ))}
                  </ol>
                )}
              </Card>
              <Card title={`Project memory (${s.memories.length}, including history)`}>
                <div className="max-h-[32rem] space-y-2 overflow-y-auto pr-1">
                  {s.memories.map((m) => <MemoryCard key={m.id} memory={m} compact />)}
                </div>
              </Card>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
