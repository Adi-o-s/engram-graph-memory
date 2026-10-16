import { useState } from 'react'
import { ShieldCheck, ShieldAlert } from 'lucide-react'
import { api } from '../api/endpoints'
import { ApiError } from '../api/client'
import type { LoginResponse, Memory, Project, Task } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Badge, Button, Card, Empty, ErrorBox, Field, Input, Mono, PageHeader, Select, Spinner } from '../components/ui'
import { MemoryCard } from '../components/MemoryCard'
import { useAsync } from '../lib/useAsync'
import { cx, shortId } from '../lib/format'

interface Snapshot { projects: Project[]; memories: Memory[]; tasks: Task[] }
interface Probe { label: string; request: string; status: number | string; isolated: boolean; detail: string }

async function snapshot(token: string | null | undefined): Promise<Snapshot> {
  const [projects, memories, tasks] = await Promise.all([
    api.projects(token),
    api.searchMemories({ q: 'database', limit: 8 }, token),
    api.tasks({}, token),
  ])
  return { projects, memories: memories.items, tasks: tasks.items }
}

export function TenantIsolationPage() {
  const { me } = useAuth()
  const overview = useAsync(() => api.overview(), [])
  const accounts = useAsync(() => api.demoAccounts(), [])
  const mine = useAsync(() => snapshot(undefined), [])
  const defaultOther = me?.tenant.slug === 'globex' ? 'alice@acme.test' : 'bob@globex.test'
  const [otherEmail, setOtherEmail] = useState(defaultOther)
  const [password, setPassword] = useState('')
  const [other, setOther] = useState<{ login: LoginResponse; data: Snapshot }>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const [probes, setProbes] = useState<Probe[]>([])

  const pw = password || (accounts.data?.passwordHint === 'memora-demo' ? 'memora-demo' : '')

  async function signInOther(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    setProbes([])
    try {
      const login = await api.login(otherEmail, pw)
      if (login.tenant.id === me?.tenant.id) throw new Error(`${otherEmail} signed in to ${login.tenant.name}, the same tenant you are in. Pick an account from the other tenant.`)
      setOther({ login, data: await snapshot(login.token) })
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  async function runProbes() {
    if (!other || !mine.data) return
    setBusy(true)
    const results: Probe[] = []
    const probe = async (label: string, requestText: string, fn: () => Promise<unknown>, isolatedWhen: (r: unknown) => boolean) => {
      try {
        const r = await fn()
        results.push({ label, request: requestText, status: 200, isolated: isolatedWhen(r), detail: summarize(r) })
      } catch (e) {
        const status = e instanceof ApiError ? e.status : 'error'
        results.push({ label, request: requestText, status, isolated: status === 404 || status === 403, detail: e instanceof Error ? e.message : String(e) })
      }
    }
    const theirMemory = other.data.memories[0]
    const theirTask = other.data.tasks[0]
    const myMemory = mine.data.memories[0]
    const theirTenant = other.login.tenant.name
    if (theirMemory) {
      await probe(`You read a ${theirTenant} memory by id`, `GET /api/v1/memories/${shortId(theirMemory.id)}…`, () => api.memory(theirMemory.id), () => false)
    }
    if (theirTask) {
      await probe(`You read a ${theirTenant} task by id`, `GET /api/v1/tasks/${shortId(theirTask.id)}…`, () => api.task(theirTask.id), () => false)
    }
    if (myMemory) {
      await probe(`${other.login.user.displayName} reads your memory by id`, `GET /api/v1/memories/${shortId(myMemory.id)}…`, () => api.memory(myMemory.id, other.login.token), () => false)
    }
    const theirWord = theirMemory?.value ?? theirMemory?.content.split(' ').slice(-1)[0]?.replace(/[^\w]/g, '')
    if (theirWord) {
      await probe(`You search for "${theirWord}" (only ${theirTenant} has it)`, `GET /api/v1/memories?q=${theirWord}`,
        () => api.searchMemories({ q: theirWord }), (r) => (r as { items: Memory[] }).items.every((m) => !other.data.memories.some((o) => o.id === m.id)))
    }
    const theirProject = other.data.projects[0]
    if (theirProject) {
      await probe(`You filter memories by a ${theirTenant} project id`, `GET /api/v1/memories?projectId=${shortId(theirProject.id)}…`,
        () => api.searchMemories({ projectId: theirProject.id }), () => false)
      await probe(`You list tasks in a ${theirTenant} project`, `GET /api/v1/tasks?projectId=${shortId(theirProject.id)}…`,
        () => api.tasks({ projectId: theirProject.id }), () => false)
    }
    setProbes(results)
    setBusy(false)
  }

  const rls = overview.data?.health.rowLevelSecurityEnforced

  return (
    <div className="space-y-6">
      <PageHeader
        title="Tenant isolation"
        description="The tenant always comes from the signed-in principal, never from the request. Every query filters by tenant, and PostgreSQL row-level security enforces the same rule on each database connection as a second line of defense."
      />
      <div className={cx('flex items-center gap-3 rounded-xl border p-4', rls ? 'border-emerald-200 bg-emerald-50' : 'border-rose-200 bg-rose-50')}>
        {rls ? <ShieldCheck className="h-6 w-6 text-emerald-600" aria-hidden /> : <ShieldAlert className="h-6 w-6 text-rose-600" aria-hidden />}
        <div className="text-sm">
          <div className="font-semibold">{rls === undefined ? 'Checking row-level security…' : rls ? 'Row-level security is enforced' : 'Row-level security is not enforced'}</div>
          <div className="text-slate-600">Policies on 11 tenant tables (FORCE ROW LEVEL SECURITY). The app role is not a superuser, so the policies apply to it.</div>
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <TenantPanel
          title={`You: ${me?.name ?? ''}`}
          tenant={me?.tenant.name ?? ''}
          data={mine.data}
          error={mine.error}
        />
        {other ? (
          <TenantPanel title={`Second session: ${other.login.user.displayName}`} tenant={other.login.tenant.name} data={other.data} onClose={() => { setOther(undefined); setProbes([]) }} />
        ) : (
          <Card title="Sign in a second user from another tenant">
            <form onSubmit={signInOther} className="space-y-3">
              <p className="text-xs text-slate-500">This session lives only in this page. Its token is not stored and your own session is unchanged.</p>
              <Field label="Account">
                <Select value={otherEmail} onChange={(e) => setOtherEmail(e.target.value)}>
                  {(accounts.data?.accounts ?? []).map((a) => <option key={a.email} value={a.email}>{a.displayName} ({a.tenants.join(', ')})</option>)}
                  {!accounts.data?.accounts.length && <option value={defaultOther}>{defaultOther}</option>}
                </Select>
              </Field>
              <Field label="Password" hint={accounts.data?.passwordHint ? `Demo password: ${accounts.data.passwordHint}` : undefined}>
                <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={pw ? '(demo password filled in)' : ''} />
              </Field>
              <ErrorBox error={error} />
              <Button type="submit" busy={busy} disabled={!pw}>Sign in second user</Button>
            </form>
          </Card>
        )}
      </div>

      <Card
        title="Cross-tenant probes"
        actions={<Button size="sm" busy={busy && !!other} disabled={!other || !mine.data} onClick={() => void runProbes()}>Run probes</Button>}
      >
        {!other ? <Empty>Sign in a second user from another tenant, then run the probes.</Empty> : probes.length === 0 ? (
          <p className="text-sm text-slate-500">Each probe uses real ids from the other tenant and calls the real API. An isolated result is 404 (the record does not exist for you) or an empty list.</p>
        ) : (
          <ul className="divide-y divide-slate-100">
            {probes.map((p) => (
              <li key={p.label} className="flex flex-wrap items-center gap-3 py-2 text-sm">
                <Badge tone={p.isolated ? 'green' : 'red'}>{p.isolated ? 'Isolated' : 'LEAK'}</Badge>
                <span className="font-medium">{p.label}</span>
                <Mono>{p.request}</Mono>
                <span className="ml-auto text-xs text-slate-500">HTTP {p.status} · {p.detail}</span>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  )
}

function summarize(r: unknown): string {
  if (r && typeof r === 'object' && 'items' in r) return `${(r as { items: unknown[] }).items.length} results`
  return 'record returned'
}

function TenantPanel({ title, tenant, data, error, onClose }: { title: string; tenant: string; data?: Snapshot; error?: unknown; onClose?: () => void }) {
  return (
    <Card title={title} actions={<>{<Badge tone="indigo">{tenant}</Badge>}{onClose && <Button size="sm" variant="ghost" onClick={onClose}>Sign out</Button>}</>}>
      <ErrorBox error={error} />
      {!data ? (!error && <Spinner />) : (
        <div className="space-y-3 text-sm">
          <div>
            <div className="text-xs font-semibold uppercase tracking-wide text-slate-500">Projects</div>
            <div className="mt-1 flex flex-wrap gap-1">{data.projects.map((p) => <Badge key={p.id}>{p.name}</Badge>)}</div>
          </div>
          <div>
            <div className="text-xs font-semibold uppercase tracking-wide text-slate-500">Search "database"</div>
            <div className="mt-1 space-y-1.5">
              {data.memories.length === 0 ? <p className="text-xs text-slate-500">No results.</p> : data.memories.slice(0, 4).map((m) => <MemoryCard key={m.id} memory={m} compact />)}
            </div>
          </div>
          <div className="text-xs text-slate-500">{data.tasks.length} tasks visible</div>
        </div>
      )}
    </Card>
  )
}
