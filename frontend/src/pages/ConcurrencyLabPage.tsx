import { useState } from 'react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '../api/endpoints'
import type { LabResult, LabScenario } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Badge, Button, Card, Empty, ErrorBox, Field, Input, PageHeader, Select, Spinner, Stat } from '../components/ui'
import { useAsync } from '../lib/useAsync'
import { dateTime, ms } from '../lib/format'

const SCENARIOS: { value: LabScenario; label: string; description: string }[] = [
  { value: 'TASK_COUNTER', label: 'Versioned task counter', description: 'Every worker increments one counter in a shared task using optimistic locking. Stale writes get a conflict and retry with backoff. The final counter must equal the number of operations.' },
  { value: 'MESSAGE_APPEND', label: 'Concurrent message append', description: 'Workers append messages to one conversation at once. Sequence numbers come from an atomic UPDATE … RETURNING, so they must be 1..N with no gaps or duplicates.' },
  { value: 'MEMORY_WRITES', label: 'Competing fact writes', description: 'Workers write different values for one fact plus unique episodic memories. Exactly one value may remain CURRENT, the rest become history, and every episodic write must be stored.' },
]

export function ConcurrencyLabPage() {
  const { me } = useAuth()
  const history = useAsync(() => api.labRuns(), [])
  const [scenario, setScenario] = useState<LabScenario>('TASK_COUNTER')
  const [threads, setThreads] = useState(16)
  const [operations, setOperations] = useState(400)
  const [result, setResult] = useState<LabResult>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const isUser = me?.actorType === 'USER'

  async function run(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      setResult(await api.runLab(scenario, threads, operations))
      await history.reload()
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  const shown = result ?? history.data?.[0]
  const info = SCENARIOS.find((s) => s.value === scenario)!

  return (
    <div className="space-y-6">
      <PageHeader
        title="Concurrency lab"
        description="Runs a real concurrent workload inside the backend against PostgreSQL, then checks the invariants in the database. All numbers are measured on this machine; nothing is simulated in the browser."
      />
      <Card title="Run a workload">
        <form onSubmit={run} className="grid gap-3 md:grid-cols-[minmax(0,2fr)_8rem_8rem_auto] md:items-end">
          <Field label="Scenario">
            <Select value={scenario} onChange={(e) => setScenario(e.target.value as LabScenario)}>
              {SCENARIOS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
            </Select>
          </Field>
          <Field label="Threads (1–64)"><Input type="number" min={1} max={64} value={threads} onChange={(e) => setThreads(Number(e.target.value))} /></Field>
          <Field label="Operations"><Input type="number" min={1} max={20000} value={operations} onChange={(e) => setOperations(Number(e.target.value))} /></Field>
          <Button type="submit" busy={busy} disabled={!isUser}>Run</Button>
        </form>
        <p className="mt-2 text-xs text-slate-500">{info.description}</p>
        <ErrorBox error={error} className="mt-3" />
      </Card>

      {busy && <Spinner label={`Running ${operations} operations on ${threads} threads`} />}
      {shown ? <ResultView result={shown} latest={!!result} /> : history.data && <Card><Empty>No runs yet.</Empty></Card>}

      <Card title="Previous runs">
        {!history.data ? <Spinner /> : history.data.length === 0 ? <Empty>No runs yet.</Empty> : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-xs text-slate-500">
                <tr>
                  <th className="py-1 pr-3 font-medium">When</th><th className="pr-3 font-medium">Scenario</th><th className="pr-3 font-medium">Threads</th>
                  <th className="pr-3 font-medium">Ops</th><th className="pr-3 font-medium">OK / failed</th><th className="pr-3 font-medium">Conflicts</th>
                  <th className="pr-3 font-medium">p95</th><th className="pr-3 font-medium">Throughput</th><th className="font-medium">Invariants</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {history.data.map((r) => (
                  <tr key={r.runId}>
                    <td className="py-1.5 pr-3 text-xs text-slate-500 whitespace-nowrap">{dateTime(r.startedAt)}</td>
                    <td className="pr-3">{r.scenario}</td>
                    <td className="pr-3">{r.threads}</td>
                    <td className="pr-3">{r.operations}</td>
                    <td className="pr-3">{r.succeeded} / {r.failed}</td>
                    <td className="pr-3">{r.conflictsDetected}</td>
                    <td className="pr-3">{ms(r.latency.p95Ms)}</td>
                    <td className="pr-3">{r.throughputPerSec.toFixed(0)}/s</td>
                    <td><Badge tone={r.allInvariantsPassed ? 'green' : 'red'}>{r.allInvariantsPassed ? 'passed' : 'failed'}</Badge></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  )
}

function ResultView({ result: r, latest }: { result: LabResult; latest: boolean }) {
  const latency = [
    { name: 'p50', ms: r.latency.p50Ms },
    { name: 'p95', ms: r.latency.p95Ms },
    { name: 'p99', ms: r.latency.p99Ms },
    { name: 'max', ms: r.latency.maxMs },
  ]
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 text-sm text-slate-600">
        {latest ? 'Latest run' : 'Most recent stored run'}: <Badge tone="indigo">{r.scenario}</Badge> {r.threads} threads × {r.operations} operations · {dateTime(r.startedAt)}
      </div>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
        <Stat label="Succeeded" value={r.succeeded} tone={r.failed ? undefined : 'good'} hint={`${r.failed} failed`} />
        <Stat label="Conflicts detected" value={r.conflictsDetected} hint={`${r.retries} retries`} />
        <Stat label="Duration" value={ms(r.durationMs)} />
        <Stat label="Throughput" value={`${r.throughputPerSec.toFixed(0)}/s`} />
        <Stat label="Invariants" value={r.allInvariantsPassed ? 'All passed' : 'Failed'} tone={r.allInvariantsPassed ? 'good' : 'bad'} />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Latency per operation (ms)">
          <div className="h-56">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={latency}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                <XAxis dataKey="name" fontSize={12} />
                <YAxis fontSize={12} />
                <Tooltip formatter={(v) => `${Number(v).toFixed(2)} ms`} />
                <Bar dataKey="ms" fill="#6366f1" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <p className="text-xs text-slate-500">Mean {ms(r.latency.meanMs)}. Latency includes retries after conflicts.</p>
        </Card>
        <Card title="Invariants checked in PostgreSQL">
          <ul className="space-y-2">
            {r.invariants.map((i) => (
              <li key={i.name} className="rounded-lg border border-slate-200 p-2 text-sm">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium">{i.name}</span>
                  <Badge tone={i.passed ? 'green' : 'red'}>{i.passed ? 'pass' : 'fail'}</Badge>
                </div>
                <div className="mt-0.5 text-xs text-slate-500">expected {i.expected} · actual {i.actual}</div>
              </li>
            ))}
          </ul>
          {r.sampleErrors.length > 0 && (
            <div className="mt-3 text-xs text-rose-700">
              Sample errors: {r.sampleErrors.slice(0, 3).join('; ')}
            </div>
          )}
        </Card>
      </div>
    </div>
  )
}
