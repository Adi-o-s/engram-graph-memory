import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/endpoints'
import type { ContextPath, Explanation, GraphAlert, PathStep } from '../api/types'
import { Badge, Button, Card, ErrorBox, Mono } from './ui'
import { cx, shortId } from '../lib/format'

export const LABEL_COLORS: Record<string, string> = {
  Memory: '#6366f1', Entity: '#10b981', Agent: '#f59e0b', User: '#ec4899', Project: '#0ea5e9', Conversation: '#8b5cf6',
  Message: '#a78bfa', Task: '#ef4444', Handoff: '#f97316', Run: '#14b8a6', Step: '#84cc16',
}

/** A graph path as chips: (Label name) -REL-> (Label name) ... */
export function PathChips({ steps, className }: { steps: PathStep[]; className?: string }) {
  return (
    <div className={cx('flex flex-wrap items-center gap-1 text-[11px]', className)} data-testid="graph-path">
      {steps.map((s, i) => (
        <span key={i} className="flex items-center gap-1">
          {s.via && <span className="font-mono text-slate-400">-{s.via}→</span>}
          <span className="rounded border px-1.5 py-0.5" style={{ borderColor: LABEL_COLORS[s.label] ?? '#cbd5e1' }}>
            <span className="font-semibold" style={{ color: LABEL_COLORS[s.label] ?? '#475569' }}>{s.label}</span>{' '}
            <span className="text-slate-700">{trim(s.name ?? (s.label === 'Memory' ? shortId(s.id) : s.id) ?? '')}</span>
          </span>
        </span>
      ))}
    </div>
  )
}

function trim(s: string, n = 60) {
  return s.length > n ? s.slice(0, n - 1) + '…' : s
}

/** "Why?" for one memory: the path that reached it (if known) plus graph provenance from /api/graph/explain. */
export function WhyButton({ memoryId, path, from }: { memoryId: string; path?: ContextPath; from?: string }) {
  const [open, setOpen] = useState(false)
  const [data, setData] = useState<Explanation>()
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  async function load() {
    setOpen(!open)
    if (data || open) return
    setBusy(true)
    try {
      setData(await api.explain(memoryId, from))
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }
  const p = data?.provenance
  return (
    <div className="mt-1">
      <Button variant="secondary" size="sm" busy={busy} onClick={() => void load()}>Why?</Button>
      {open && (
        <div className="mt-1.5 space-y-1.5 rounded-md border border-indigo-100 bg-indigo-50/50 p-2 text-[11px] text-slate-700" data-testid="why-panel">
          {path && (
            <div>
              <div className="mb-0.5 font-medium">Reached by graph traversal ({path.hops} hop{path.hops === 1 ? '' : 's'}, score {path.score.toFixed(3)})</div>
              <PathChips steps={path.path.steps} />
            </div>
          )}
          {data?.path && (
            <div>
              <div className="mb-0.5 font-medium">Shortest path from {data.from}</div>
              <PathChips steps={data.path.steps} />
            </div>
          )}
          <ErrorBox error={error} />
          {p && (
            <ul className="space-y-0.5">
              {p.conversation && <li>From conversation <b>{p.conversation.title}</b>{p.message && <>: “{trim(p.message.snippet, 120)}”</>}</li>}
              {!!p.writtenBy?.length && <li>Written by {p.writtenBy.map((w) => `${w.name ?? shortId(w.id)} (${w.label})`).join(', ')}</li>}
              {!!p.supersedes?.length && <li>Supersedes: {p.supersedes.map((s) => `“${trim(s.content, 80)}”`).join(', ')}</li>}
              {!!p.supersededBy?.length && <li>Superseded by: {p.supersededBy.map((s) => `“${trim(s.content, 80)}”`).join(', ')}</li>}
              {!!p.entities?.length && <li>Entities: {p.entities.map((e) => <Mono key={e.key} className="mr-1">{e.key}</Mono>)}</li>}
              {!!p.readBy?.length && <li>Read by {p.readBy.map((r) => `${r.name ?? shortId(r.id)} ×${r.count ?? 1}`).join(', ')}</li>}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}

export function AlertList({ alerts, empty = 'No stale-knowledge alerts.' }: { alerts: GraphAlert[]; empty?: string }) {
  if (!alerts.length) return <p className="text-xs text-slate-500">{empty}</p>
  return (
    <ul className="space-y-2" data-testid="stale-alerts">
      {alerts.map((a) => (
        <li key={a.alertId} className="rounded-md border border-amber-200 bg-amber-50 p-2 text-xs">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="font-semibold text-amber-800">⚠ {a.targetName ?? a.targetLabel}</span>
            <Badge tone="amber">{a.kind.replaceAll('_', ' ').toLowerCase()}</Badge>
            <Badge tone={a.status === 'OPEN' ? 'red' : 'gray'}>{a.status.toLowerCase()}</Badge>
          </div>
          <div className="mt-1 text-slate-700">
            <span className="line-through decoration-slate-400">{a.oldContent}</span>
            {' → '}
            <span className="font-medium">{a.newContent ?? `(${a.oldStatus.toLowerCase()})`}</span>
          </div>
          <PathChips steps={a.path} className="mt-1" />
        </li>
      ))}
    </ul>
  )
}

/** Stale-knowledge alerts from the graph, for Overview and the task board. */
export function StaleAlertsCard({ limit = 10 }: { limit?: number }) {
  const [alerts, setAlerts] = useState<GraphAlert[]>()
  const [error, setError] = useState<unknown>()
  useEffect(() => {
    let live = true
    const load = () => api.graphAlerts(limit).then((a) => live && setAlerts(a)).catch((e) => live && setError(e))
    void load()
    const t = setInterval(load, 10000)
    return () => { live = false; clearInterval(t) }
  }, [limit])
  return (
    <Card title="Stale-knowledge alerts (memory graph)" actions={<Link className="text-xs text-brand-600" to="/graph">Open graph</Link>}>
      <ErrorBox error={error} />
      <p className="mb-2 text-xs text-slate-500">
        When a fact changes, FalkorDB finds who is working from the old version: agents that read it, active runs whose
        steps used it, open handoffs that carry it.
      </p>
      {alerts ? <AlertList alerts={alerts} /> : !error && <p className="text-xs text-slate-500">Loading…</p>}
    </Card>
  )
}
