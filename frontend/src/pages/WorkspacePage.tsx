import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { orchestration } from '../api/orchestration'
import type { Artifact } from '../api/orchestration'
import { api } from '../api/endpoints'
import { Badge, Button, Card, Empty, ErrorBox, Mono, PageHeader, Select, Spinner, StatusBadge } from '../components/ui'
import { useAsync } from '../lib/useAsync'
import { dateTime, timeAgo } from '../lib/format'

export function WorkspacePage() {
  const [params, setParams] = useSearchParams()
  const projects = useAsync(() => api.projects(), [])
  const projectId = params.get('project') ?? ''
  const ws = useAsync(() => (projectId ? orchestration.workspace(projectId) : Promise.resolve(undefined)), [projectId])
  const [viewing, setViewing] = useState<Artifact>()

  useEffect(() => {
    if (!projectId && projects.data && projects.data.length > 0) setParams({ project: projects.data[0].id }, { replace: true })
  }, [projectId, projects.data, setParams])

  const w = ws.data
  return (
    <div className="space-y-6">
      <PageHeader title="Project workspace"
        description="Everything a project has accumulated across runs: goals, requirements, decisions and lessons in shared memory, generated artifacts, outstanding work and history. A resumed or new run starts from this."
        actions={<Select aria-label="Project" className="w-72" value={projectId} onChange={(e) => setParams({ project: e.target.value })}>
          {projects.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</Select>} />
      <ErrorBox error={ws.error ?? projects.error} />
      {!w ? (projectId ? <Spinner /> : <Card><Empty>No projects yet.</Empty></Card>) : (
        <>
          <Card title={w.name}>
            <p className="text-sm text-slate-600">{w.description ?? 'No description.'}</p>
            {w.runs.length > 0 && <ul className="mt-3 space-y-1 text-sm">
              {w.runs.map((r) => (
                <li key={r.id} className="flex flex-wrap items-center justify-between gap-2">
                  <Link to={`/runs/${r.id}`} className="text-brand-700 hover:underline">{r.name}</Link>
                  <span className="flex items-center gap-2 text-xs text-slate-500">{timeAgo(r.createdAt)} <StatusBadge value={r.status} /></span>
                </li>
              ))}
            </ul>}
            {w.runs.length === 0 && <p className="mt-2 text-xs text-slate-500">No workflow runs in this project yet.</p>}
          </Card>
          <div className="grid gap-6 xl:grid-cols-3">
            <MemoryList title="Decisions" items={w.decisions} empty="No decisions recorded yet." />
            <MemoryList title="Requirements" items={w.requirements} empty="No requirements recorded yet." />
            <MemoryList title="Lessons" items={w.lessons} empty="No lessons recorded yet." />
          </div>
          <div className="grid gap-6 xl:grid-cols-2">
            <Card title={`Artifacts (${w.artifacts.length})`}>
              {w.artifacts.length === 0 ? <Empty>No artifacts yet.</Empty> : (
                <ul className="max-h-96 divide-y divide-slate-100 overflow-y-auto text-sm">
                  {w.artifacts.map((a) => (
                    <li key={a.id} className="flex items-center justify-between gap-2 py-1.5">
                      <button type="button" className="truncate text-left text-brand-700 hover:underline" onClick={() => void orchestration.artifact(a.id).then(setViewing)}>{a.name}</button>
                      <span className="shrink-0 text-xs text-slate-500">v{a.version} · {timeAgo(a.createdAt)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
            <Card title={`Outstanding work (${w.outstanding.length})`}>
              {w.outstanding.length === 0 ? <Empty>Nothing outstanding.</Empty> : (
                <ul className="space-y-1 text-sm">
                  {w.outstanding.map((o, i) => (
                    <li key={i} className="flex flex-wrap items-center justify-between gap-2">
                      <span><Link className="text-brand-700 hover:underline" to={`/runs/${String(o.runId)}`}>{String(o.step)}</Link> <span className="text-xs text-slate-500">in {String(o.run)}</span></span>
                      <StatusBadge value={String(o.status)} />
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>
          <Card title="History">
            {w.history.length === 0 ? <Empty>No history yet.</Empty> : (
              <ol className="space-y-1 text-xs">
                {w.history.map((h, i) => (
                  <li key={i} className="flex gap-2"><span className="w-40 shrink-0 font-mono text-slate-400">{dateTime(String(h.createdAt))}</span>
                    <Badge>{String(h.type)}</Badge><span>{String(h.message ?? '')}</span></li>
                ))}
              </ol>
            )}
          </Card>
        </>
      )}
      {viewing && (
        <div role="dialog" aria-modal="true" aria-label={viewing.name} className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4" onClick={() => setViewing(undefined)}>
          <div className="max-h-[90vh] w-full max-w-4xl overflow-hidden rounded-xl bg-white shadow-xl" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
              <div className="font-semibold">{viewing.name} <span className="text-xs font-normal text-slate-500">v{viewing.version}</span><div className="font-mono text-[11px] font-normal text-slate-500">sha256 {viewing.checksum}</div></div>
              <Button size="sm" variant="ghost" onClick={() => setViewing(undefined)}>Close</Button>
            </div>
            <pre className="code-block m-0 max-h-[75vh] rounded-none">{viewing.content}</pre>
          </div>
        </div>
      )}
    </div>
  )
}

function MemoryList({ title, items, empty }: { title: string; items: Record<string, unknown>[]; empty: string }) {
  return (
    <Card title={`${title} (${items.length})`}>
      {items.length === 0 ? <p className="text-xs text-slate-500">{empty}</p> : (
        <ul className="max-h-80 space-y-1.5 overflow-y-auto text-sm">
          {items.map((m) => (
            <li key={String(m.id)} className="rounded bg-slate-50 px-2 py-1">
              <div>{String(m.content)}</div>
              <div className="mt-0.5 flex gap-2 text-[11px] text-slate-500"><Badge>{String(m.scope).toLowerCase()}</Badge>{Number(m.version) > 1 && <Badge tone="amber">v{String(m.version)}</Badge>}
                {m.workflowRunId ? <Link className="hover:underline" to={`/runs/${String(m.workflowRunId)}`}>from a run</Link> : null}<Mono>{timeAgo(String(m.createdAt))}</Mono></div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
