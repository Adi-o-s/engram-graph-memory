import { useState } from 'react'
import { api } from '../api/endpoints'
import type { Handoff, TaskStatus } from '../api/types'
import { ApiError } from '../api/client'
import { Badge, Button, Card, Empty, ErrorBox, JsonView, Mono, PageHeader, Select, Spinner, StatusBadge } from '../components/ui'
import { StaleAlertsCard } from '../components/GraphBits'
import { useAsync } from '../lib/useAsync'
import { cx, dateTime, shortId, timeAgo, titleCase } from '../lib/format'

const COLUMNS: TaskStatus[] = ['TODO', 'IN_PROGRESS', 'BLOCKED', 'IN_REVIEW', 'DONE', 'FAILED']
const NEXT: Record<TaskStatus, TaskStatus[]> = {
  TODO: ['IN_PROGRESS', 'BLOCKED', 'FAILED'],
  IN_PROGRESS: ['TODO', 'BLOCKED', 'IN_REVIEW', 'DONE', 'FAILED'],
  BLOCKED: ['TODO', 'IN_PROGRESS', 'FAILED'],
  IN_REVIEW: ['IN_PROGRESS', 'DONE', 'FAILED'],
  FAILED: ['TODO', 'IN_PROGRESS'],
  DONE: ['IN_PROGRESS'],
}

export function TaskBoardPage() {
  const projects = useAsync(() => api.projects(), [])
  const [projectId, setProjectId] = useState('')
  const tasks = useAsync(() => api.tasks({ projectId: projectId || undefined }), [projectId])
  const agents = useAsync(() => api.agents(), [])
  const [selected, setSelected] = useState<string>()
  const names = new Map((agents.data ?? []).map((a) => [a.id, a.name]))

  return (
    <div className="space-y-6">
      <PageHeader
        title="Tasks and handoffs"
        description="Tasks carry a version number. Every update must name the version it was based on; a stale update gets HTTP 409 instead of silently overwriting someone else's change."
        actions={
          <Select aria-label="Project" value={projectId} onChange={(e) => setProjectId(e.target.value)} className="w-64">
            <option value="">All projects</option>
            {projects.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
        }
      />
      <ErrorBox error={tasks.error} />
      <StaleAlertsCard limit={5} />
      {!tasks.data ? <Spinner /> : (
        <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-6">
          {COLUMNS.map((col) => {
            const items = tasks.data!.items.filter((t) => t.status === col)
            return (
              <div key={col} className="rounded-xl bg-slate-100 p-2">
                <div className="mb-2 flex items-center justify-between px-1">
                  <span className="text-xs font-semibold uppercase tracking-wide text-slate-600">{titleCase(col)}</span>
                  <span className="text-xs text-slate-500">{items.length}</span>
                </div>
                <div className="space-y-2">
                  {items.map((t) => (
                    <button
                      key={t.id}
                      type="button"
                      onClick={() => setSelected(t.id)}
                      className={cx('block w-full rounded-lg border bg-white p-2.5 text-left text-sm shadow-sm', selected === t.id ? 'border-brand-500 ring-2 ring-brand-100' : 'border-transparent hover:border-slate-300')}
                    >
                      <div className="font-medium leading-snug [overflow-wrap:anywhere]">{t.title}</div>
                      <div className="mt-1 flex flex-wrap gap-1 text-[11px] text-slate-500">
                        <Badge>v{t.version}</Badge>
                        <Badge tone="gray">P{t.priority}</Badge>
                        {t.assigneeAgentId && <span>{names.get(t.assigneeAgentId) ?? shortId(t.assigneeAgentId)}</span>}
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            )
          })}
        </div>
      )}
      {selected && <TaskDetail key={selected} id={selected} names={names} onChanged={() => void tasks.reload()} />}
    </div>
  )
}

function TaskDetail({ id, names, onChanged }: { id: string; names: Map<string, string>; onChanged: () => void }) {
  const task = useAsync(() => api.task(id), [id])
  const events = useAsync(() => api.taskEvents(id), [id])
  const handoffs = useAsync(() => api.handoffs({ taskId: id, limit: 50 }), [id])
  const [error, setError] = useState<unknown>()
  const [notice, setNotice] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function act(fn: () => Promise<unknown>, success?: string) {
    setBusy(true)
    setError(undefined)
    setNotice(undefined)
    try {
      await fn()
      if (success) setNotice(success)
    } catch (e) {
      setError(e)
    } finally {
      await Promise.all([task.reload(), events.reload(), handoffs.reload()])
      onChanged()
      setBusy(false)
    }
  }

  const t = task.data
  if (!t) return task.error ? <ErrorBox error={task.error} /> : <Spinner />

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Card title={t.title} actions={<><StatusBadge value={t.status} /><Badge>version {t.version}</Badge></>}>
        <div className="space-y-4 text-sm">
          {t.description && <p className="whitespace-pre-wrap text-slate-600">{t.description}</p>}
          <div>
            <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-slate-500">Move to</div>
            <div className="flex flex-wrap gap-2">
              {NEXT[t.status].map((s) => (
                <Button key={s} size="sm" variant="secondary" disabled={busy}
                  onClick={() => act(() => api.updateTask(t.id, { expectedVersion: t.version, status: s, note: 'Moved on the board' }))}>
                  {titleCase(s)}
                </Button>
              ))}
              <Button size="sm" variant="ghost" disabled={busy}
                title="Sends an update based on the previous version, as a client with stale data would"
                onClick={() => act(() => api.updateTask(t.id, { expectedVersion: t.version - 1, note: 'stale write' }))}>
                Simulate stale write
              </Button>
            </div>
          </div>
          {error instanceof ApiError && error.status === 409 ? (
            <div role="alert" className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-xs text-amber-900">
              <div className="font-semibold">409 Conflict: {error.message}</div>
              <div className="mt-1">
                expected version <Mono>{String(error.details.expectedVersion ?? '?')}</Mono>, current version{' '}
                <Mono>{String(error.details.currentVersion ?? '?')}</Mono>. Nothing was overwritten.
              </div>
            </div>
          ) : <ErrorBox error={error} />}
          {notice && <p className="text-xs text-emerald-700">{notice}</p>}
          <details open>
            <summary className="cursor-pointer text-xs text-slate-500">Shared state (JSONB)</summary>
            <JsonView value={t.state} className="mt-2" />
          </details>
          <div>
            <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-slate-500">Event log ({events.data?.length ?? 0}, newest first)</div>
            {!events.data ? <Spinner /> : events.data.length === 0 ? <p className="text-xs text-slate-500">No events.</p> : (
              <ol className="max-h-72 space-y-1 overflow-y-auto pr-1 text-xs">
                {[...events.data].reverse().map((e) => (
                  <li key={e.id} className="flex flex-wrap gap-1.5">
                    <span className="text-slate-400" title={dateTime(e.createdAt)}>{timeAgo(e.createdAt)}</span>
                    <Mono>{e.eventType}</Mono>
                    {e.fromStatus && e.toStatus && e.fromStatus !== e.toStatus && <span>{e.fromStatus} → {e.toStatus}</span>}
                    {e.taskVersion != null && <span className="text-slate-400">v{e.taskVersion}</span>}
                    <span className="text-slate-400">{e.actorType.toLowerCase()} {e.actorId ? names.get(e.actorId) ?? shortId(e.actorId) : ''}</span>
                  </li>
                ))}
              </ol>
            )}
          </div>
        </div>
      </Card>
      <Card title="Handoffs for this task">
        {!handoffs.data ? <Spinner /> : handoffs.data.length === 0 ? <Empty>No handoffs.</Empty> : (
          <ul className="space-y-3">
            {handoffs.data.map((h) => <HandoffItem key={h.id} handoff={h} names={names} busy={busy} act={act} />)}
          </ul>
        )}
      </Card>
    </div>
  )
}

function HandoffItem({ handoff: h, names, busy, act }: {
  handoff: Handoff; names: Map<string, string>; busy: boolean; act: (fn: () => Promise<unknown>, success?: string) => Promise<void>
}) {
  const name = (id?: string) => (id ? names.get(id) ?? shortId(id) : '—')
  return (
    <li className="rounded-lg border border-slate-200 p-3 text-sm">
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium">{name(h.fromAgentId)} → {name(h.toAgentId)}</span>
        <StatusBadge value={h.status} />
      </div>
      <p className="mt-1 text-slate-700">{h.summary}</p>
      <dl className="mt-2 grid grid-cols-[7rem_1fr] gap-y-0.5 text-xs">
        <dt className="text-slate-500">Next action</dt><dd>{h.nextAction}</dd>
        {h.decisions.length > 0 && (<><dt className="text-slate-500">Decisions</dt><dd><ul className="list-disc pl-4">{h.decisions.map((d) => <li key={d}>{d}</li>)}</ul></dd></>)}
        <dt className="text-slate-500">Memory refs</dt><dd>{h.memoryRefs.length ? h.memoryRefs.map(shortId).join(', ') : 'none'}</dd>
        <dt className="text-slate-500">Attempts</dt><dd>{h.attempts} of {h.maxAttempts}</dd>
        <dt className="text-slate-500">Task version</dt><dd>{h.taskVersion}</dd>
        {h.leaseExpiresAt && h.status === 'ACKNOWLEDGED' && (<><dt className="text-slate-500">Lease until</dt><dd>{dateTime(h.leaseExpiresAt)}</dd></>)}
        {h.failureReason && (<><dt className="text-slate-500">Failure</dt><dd className="text-rose-700">{h.failureReason}</dd></>)}
      </dl>
      <div className="mt-2 flex flex-wrap gap-2">
        {h.status === 'PENDING' && (
          <>
            <Button size="sm" disabled={busy} onClick={() => act(() => api.handoffAction(h.id, 'ack'), 'Acknowledged on behalf of the receiving agent')}>Acknowledge</Button>
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => act(() => api.handoffAction(h.id, 'reject', { reason: 'Rejected from the dashboard' }))}>Reject</Button>
          </>
        )}
        {h.status === 'ACKNOWLEDGED' && (
          <>
            <Button size="sm" disabled={busy} onClick={() => act(() => api.handoffAction(h.id, 'complete', { result: { via: 'dashboard' } }))}>Complete</Button>
            <Button size="sm" variant="danger" disabled={busy} onClick={() => act(() => api.handoffAction(h.id, 'fail', { reason: 'Failed from the dashboard' }))}>Fail</Button>
          </>
        )}
        {h.status === 'FAILED' && (
          <Button size="sm" disabled={busy} onClick={() => act(() => api.handoffAction(h.id, 'retry'), 'Returned to the inbox for another attempt')}>Retry</Button>
        )}
      </div>
    </li>
  )
}
