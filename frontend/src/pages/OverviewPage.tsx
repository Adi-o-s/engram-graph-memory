import { Link } from 'react-router-dom'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '../api/endpoints'
import type { HandoffStatus, MemoryStatus, MemoryType, TaskStatus } from '../api/types'
import { Badge, Button, Card, Empty, ErrorBox, Mono, PageHeader, Spinner, Stat, StatusBadge } from '../components/ui'
import { useAsync, useInterval } from '../lib/useAsync'
import { StaleAlertsCard } from '../components/GraphBits'
import { ms, shortId, timeAgo, titleCase } from '../lib/format'

const MEMORY_TYPES: MemoryType[] = ['SEMANTIC', 'EPISODIC', 'PROCEDURAL']
const MEMORY_STATUSES: MemoryStatus[] = ['CURRENT', 'SUPERSEDED', 'RETRACTED']
const TASK_STATUSES: TaskStatus[] = ['TODO', 'IN_PROGRESS', 'BLOCKED', 'IN_REVIEW', 'DONE', 'FAILED']
const HANDOFF_STATUSES: HandoffStatus[] = ['PENDING', 'ACKNOWLEDGED', 'COMPLETED', 'FAILED', 'REJECTED', 'DEAD_LETTER']

export function OverviewPage() {
  const { data, error, loading, reload } = useAsync(() => api.overview(), [])
  useInterval(() => void reload(), 10000)

  if (!data) return error ? <ErrorBox error={error} /> : <Spinner />
  const agentName = new Map(data.agents.map((a) => [a.id, a.name]))
  const memoryRows = MEMORY_TYPES.map((t) => ({ type: titleCase(t), ...data.memories[t] }))
  const totalMemories = MEMORY_TYPES.reduce((n, t) => n + MEMORY_STATUSES.reduce((m, s) => m + (data.memories[t]?.[s] ?? 0), 0), 0)
  const currentMemories = MEMORY_TYPES.reduce((n, t) => n + (data.memories[t]?.CURRENT ?? 0), 0)
  const lat = data.apiLatency

  return (
    <div className="space-y-6">
      <PageHeader
        title="Overview"
        description="Live numbers for the signed-in tenant, read from PostgreSQL and the API's own request metrics. Refreshes every 10 seconds."
        actions={<Button variant="secondary" busy={loading} onClick={() => void reload()}>Refresh</Button>}
      />
      <ErrorBox error={error} />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Memories" value={totalMemories} hint={`${currentMemories} current`} />
        <Stat label="Conversations" value={data.counts.conversations} hint={`${data.counts.messages} messages`} />
        <Stat label="Agents" value={data.counts.agents} hint={`${data.counts.agentsActiveLast15m} active in last 15 min`} />
        <Stat label="Projects" value={data.counts.projects} />
      </div>
      <div className="grid gap-3 lg:grid-cols-4">
        <Stat
          label="Database"
          value={data.health.database}
          tone={data.health.database === 'UP' ? 'good' : 'bad'}
          hint={`ping ${ms(data.health.dbPingMs)}`}
        />
        <Stat
          label="Row-level security"
          value={data.health.rowLevelSecurityEnforced ? 'Enforced' : 'Off'}
          tone={data.health.rowLevelSecurityEnforced ? 'good' : 'bad'}
          hint="PostgreSQL policies on tenant tables"
        />
        <Stat label="API p95" value={lat.sampleCount ? ms(lat.p95Ms) : '—'} hint={`${lat.sampleCount} recent requests · p50 ${ms(lat.p50Ms)}`} />
        <Stat
          label="API errors"
          value={lat.serverErrors}
          tone={lat.serverErrors ? 'bad' : 'good'}
          hint={`${lat.clientErrors} client errors · ${lat.conflicts} conflicts (409)`}
        />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <div className="lg:col-span-2"><StaleAlertsCard /></div>
        <Card title="Memory by type and status">
          <div className="h-56">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={memoryRows}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                <XAxis dataKey="type" fontSize={12} />
                <YAxis allowDecimals={false} fontSize={12} />
                <Tooltip />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="CURRENT" stackId="a" fill="#10b981" name="Current" />
                <Bar dataKey="SUPERSEDED" stackId="a" fill="#f59e0b" name="Superseded" />
                <Bar dataKey="RETRACTED" stackId="a" fill="#f43f5e" name="Retracted" />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </Card>
        <Card title="Tasks and handoffs">
          <div className="space-y-4">
            <StatusRow label="Tasks" statuses={TASK_STATUSES} counts={data.tasks} />
            <StatusRow label="Handoffs" statuses={HANDOFF_STATUSES} counts={data.handoffs} />
            <div className="text-xs text-slate-500">
              Most-used routes:{' '}
              {lat.topRoutes.slice(0, 4).map((r) => (
                <span key={r.route} className="mr-3"><Mono>{r.route}</Mono> ×{r.count} (p95 {ms(r.p95Ms)})</span>
              ))}
            </div>
          </div>
        </Card>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Agents" actions={<Link className="text-xs text-brand-600" to="/agents">Open workspace</Link>}>
          {data.agents.length === 0 ? <Empty>No agents registered.</Empty> : (
            <ul className="divide-y divide-slate-100">
              {data.agents.map((a) => (
                <li key={a.id} className="flex items-center justify-between py-2 text-sm">
                  <span className="font-medium">{a.name}</span>
                  <span className="flex items-center gap-2 text-xs text-slate-500">
                    <Badge tone="indigo">{a.role.toLowerCase()}</Badge>
                    sent {a.handoffsSent} · received {a.handoffsReceived} · seen {timeAgo(a.lastSeenAt)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card title="Recent handoffs" actions={<Link className="text-xs text-brand-600" to="/tasks">Open board</Link>}>
          {data.recentHandoffs.length === 0 ? <Empty>No handoffs yet. Run the guided demo to create some.</Empty> : (
            <ul className="divide-y divide-slate-100">
              {data.recentHandoffs.map((h) => (
                <li key={h.id} className="py-2 text-sm">
                  <div className="flex items-center justify-between gap-2">
                    <span>{agentName.get(h.fromAgentId) ?? shortId(h.fromAgentId)} → {agentName.get(h.toAgentId) ?? shortId(h.toAgentId)}</span>
                    <StatusBadge value={h.status} />
                  </div>
                  <div className="truncate text-xs text-slate-500">{h.summary}</div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <Card title="Recent activity (audit log)">
        {data.recentActivity.length === 0 ? <Empty>No activity yet.</Empty> : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-xs text-slate-500">
                <tr><th className="py-1 pr-4 font-medium">When</th><th className="pr-4 font-medium">Actor</th><th className="pr-4 font-medium">Action</th><th className="font-medium">Entity</th></tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.recentActivity.map((e) => (
                  <tr key={e.id}>
                    <td className="py-1.5 pr-4 text-xs text-slate-500 whitespace-nowrap">{timeAgo(e.createdAt)}</td>
                    <td className="pr-4 whitespace-nowrap">{e.actorName ?? e.actorType.toLowerCase()}</td>
                    <td className="pr-4"><Mono>{e.action}</Mono></td>
                    <td className="text-xs text-slate-500">{e.entityType} {shortId(e.entityId)}</td>
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

function StatusRow<T extends string>({ label, statuses, counts }: { label: string; statuses: T[]; counts: Partial<Record<T, number>> }) {
  return (
    <div>
      <div className="mb-1.5 text-xs font-medium text-slate-500">{label}</div>
      <div className="flex flex-wrap gap-2">
        {statuses.map((s) => (
          <div key={s} className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-2 py-1">
            <StatusBadge value={s} />
            <span className="text-sm font-semibold">{counts[s] ?? 0}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
