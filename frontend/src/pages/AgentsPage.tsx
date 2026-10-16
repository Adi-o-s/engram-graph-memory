import { useState } from 'react'
import { Copy, KeyRound } from 'lucide-react'
import { api } from '../api/endpoints'
import type { Agent, ContextBundle, RegisteredAgent } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Badge, Button, Card, Empty, ErrorBox, Field, Input, Mono, PageHeader, Select, Spinner, StatusBadge } from '../components/ui'
import { MemoryCard } from '../components/MemoryCard'
import { useAsync } from '../lib/useAsync'
import { shortId, timeAgo } from '../lib/format'

export function AgentsPage() {
  const { me } = useAuth()
  const isAdmin = me?.tenant.role === 'ADMIN'
  const agents = useAsync(() => api.agents(), [])
  const [selected, setSelected] = useState<string>()
  const [issued, setIssued] = useState<RegisteredAgent>()
  const [error, setError] = useState<unknown>()

  async function rotate(agent: Agent) {
    setError(undefined)
    try {
      setIssued(await api.rotateKey(agent.id))
      await agents.reload()
    } catch (e) {
      setError(e)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Agent workspace"
        description="Agents are first-class principals. Each one authenticates with its own API key (sent as X-Agent-Key), receives handoffs in an inbox and reads shared memory through the context builder."
      />
      {issued?.apiKey && <IssuedKey result={issued} onDismiss={() => setIssued(undefined)} />}
      <ErrorBox error={error ?? agents.error} />
      {!agents.data ? <Spinner /> : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {agents.data.map((a) => (
            <div key={a.id} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
              <div className="flex items-start justify-between gap-2">
                <div>
                  <div className="font-semibold">{a.name}</div>
                  <div className="text-xs text-slate-500">{a.description}</div>
                </div>
                <Badge tone="indigo">{a.role.toLowerCase()}</Badge>
              </div>
              <div className="mt-3 flex flex-wrap gap-1">
                {a.capabilities.map((c) => <Badge key={c}>{c}</Badge>)}
              </div>
              <dl className="mt-3 grid grid-cols-2 gap-y-1 text-xs text-slate-600">
                <dt className="text-slate-500">Handoffs sent</dt><dd>{a.handoffsSent}</dd>
                <dt className="text-slate-500">Handoffs received</dt><dd>{a.handoffsReceived}</dd>
                <dt className="text-slate-500">Last seen</dt><dd>{timeAgo(a.lastSeenAt)}</dd>
                <dt className="text-slate-500">API key</dt><dd>{a.apiKeyPrefix ? <Mono>{a.apiKeyPrefix}…</Mono> : 'none issued'}</dd>
              </dl>
              <div className="mt-3 flex gap-2">
                <Button size="sm" variant="secondary" onClick={() => setSelected(selected === a.id ? undefined : a.id)}>
                  {selected === a.id ? 'Hide inbox' : 'Inbox'}
                </Button>
                {isAdmin && (
                  <Button size="sm" variant="ghost" onClick={() => void rotate(a)}>
                    <KeyRound className="h-3.5 w-3.5" aria-hidden /> {a.apiKeyPrefix ? 'Rotate key' : 'Issue key'}
                  </Button>
                )}
              </div>
              {selected === a.id && <InboxList agentId={a.id} agents={agents.data ?? []} />}
            </div>
          ))}
        </div>
      )}
      <div className="grid gap-6 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <ContextBuilder />
        {isAdmin && <RegisterAgent onRegistered={async (r) => { setIssued(r); await agents.reload() }} />}
      </div>
    </div>
  )
}

function InboxList({ agentId, agents }: { agentId: string; agents: Agent[] }) {
  const inbox = useAsync(() => api.inbox(agentId), [agentId])
  const names = new Map(agents.map((a) => [a.id, a.name]))
  if (inbox.error) return <ErrorBox error={inbox.error} className="mt-3" />
  if (!inbox.data) return <Spinner />
  if (inbox.data.length === 0) return <p className="mt-3 text-xs text-slate-500">Inbox is empty (no pending or acknowledged handoffs).</p>
  return (
    <ul className="mt-3 space-y-2">
      {inbox.data.map((h) => (
        <li key={h.id} className="rounded-lg bg-slate-50 p-2 text-xs">
          <div className="flex items-center justify-between gap-2">
            <span>from {names.get(h.fromAgentId) ?? shortId(h.fromAgentId)}</span>
            <StatusBadge value={h.status} />
          </div>
          <div className="mt-1 text-slate-700">{h.summary}</div>
          <div className="mt-0.5 text-slate-500">next: {h.nextAction}</div>
        </li>
      ))}
    </ul>
  )
}

function IssuedKey({ result, onDismiss }: { result: RegisteredAgent; onDismiss: () => void }) {
  const [copied, setCopied] = useState(false)
  return (
    <div role="status" className="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm">
      <div className="font-semibold text-amber-900">API key for {result.agent.name}. It is shown once and only its hash is stored.</div>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <code className="rounded bg-white px-2 py-1 font-mono text-xs break-all">{result.apiKey}</code>
        <Button size="sm" variant="secondary" onClick={async () => {
          try { await navigator.clipboard.writeText(result.apiKey ?? ''); setCopied(true) } catch { /* clipboard blocked */ }
        }}>
          <Copy className="h-3.5 w-3.5" aria-hidden /> {copied ? 'Copied' : 'Copy'}
        </Button>
        <Button size="sm" variant="ghost" onClick={onDismiss}>Done</Button>
      </div>
      <div className="mt-2 text-xs text-amber-800">
        Use it with <Mono>curl -H "X-Agent-Key: …" http://localhost:8080/api/v1/agents/me</Mono>
      </div>
    </div>
  )
}

function RegisterAgent({ onRegistered }: { onRegistered: (r: RegisteredAgent) => Promise<void> }) {
  const [name, setName] = useState('')
  const [role, setRole] = useState('CUSTOM')
  const [capabilities, setCapabilities] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  return (
    <Card title="Register an agent">
      <form
        className="space-y-3"
        onSubmit={async (e) => {
          e.preventDefault()
          setBusy(true)
          setError(undefined)
          try {
            const r = await api.registerAgent({
              name, role, capabilities: capabilities.split(',').map((s) => s.trim()).filter(Boolean), issueApiKey: true,
            })
            setName('')
            setCapabilities('')
            await onRegistered(r)
          } catch (err) {
            setError(err)
          } finally {
            setBusy(false)
          }
        }}
      >
        <Field label="Name"><Input required value={name} onChange={(e) => setName(e.target.value)} placeholder="Reviewer" /></Field>
        <Field label="Role">
          <Select value={role} onChange={(e) => setRole(e.target.value)}>
            {['PLANNER', 'DEVELOPER', 'TESTER', 'CUSTOM'].map((r) => <option key={r} value={r}>{r.toLowerCase()}</option>)}
          </Select>
        </Field>
        <Field label="Capabilities" hint="Comma-separated"><Input value={capabilities} onChange={(e) => setCapabilities(e.target.value)} placeholder="review, security" /></Field>
        <ErrorBox error={error} />
        <Button type="submit" busy={busy} disabled={!name.trim()}>Register and issue key</Button>
      </form>
    </Card>
  )
}

function ContextBuilder() {
  const projects = useAsync(() => api.projects(), [])
  const [query, setQuery] = useState('database migrations testing')
  const [projectId, setProjectId] = useState('')
  const [maxChars, setMaxChars] = useState(2000)
  const [bundle, setBundle] = useState<ContextBundle>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()

  return (
    <Card title="Context builder">
      <form
        className="grid gap-3 md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_8rem_auto] md:items-end"
        onSubmit={async (e) => {
          e.preventDefault()
          setBusy(true)
          setError(undefined)
          try {
            setBundle(await api.context({ query: query || undefined, projectId: projectId || undefined, maxChars }))
          } catch (err) {
            setError(err)
          } finally {
            setBusy(false)
          }
        }}
      >
        <Field label="What is the agent working on?"><Input value={query} onChange={(e) => setQuery(e.target.value)} /></Field>
        <Field label="Project">
          <Select value={projectId} onChange={(e) => setProjectId(e.target.value)}>
            <option value="">Tenant-wide</option>
            {projects.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
        </Field>
        <Field label="Max chars"><Input type="number" min={200} max={20000} value={maxChars} onChange={(e) => setMaxChars(Number(e.target.value))} /></Field>
        <Button type="submit" busy={busy}>Build context</Button>
      </form>
      <ErrorBox error={error} className="mt-3" />
      {!bundle ? (
        <p className="mt-3 text-xs text-slate-500">Builds the bounded context an agent would receive: current facts first, then procedures, then recent episodes, within a character budget.</p>
      ) : (
        <div className="mt-4 space-y-3">
          <div className="flex flex-wrap gap-2 text-xs">
            <Badge tone={bundle.stats.truncated ? 'amber' : 'green'}>{bundle.stats.chars} / {bundle.stats.maxChars} chars</Badge>
            <Badge>{bundle.stats.memoriesIncluded} memories included</Badge>
            <Badge>{bundle.stats.memoriesOmitted} omitted for budget</Badge>
            <Badge>{bundle.stats.messagesIncluded} messages</Badge>
          </div>
          <div className="grid gap-3 lg:grid-cols-2">
            <pre className="code-block max-h-96">{bundle.rendered || '(empty)'}</pre>
            <div className="max-h-96 space-y-1.5 overflow-y-auto">
              {[...bundle.facts, ...bundle.procedures, ...bundle.episodes].length === 0 ? <Empty>No matching memories.</Empty> :
                [...bundle.facts, ...bundle.procedures, ...bundle.episodes].map((m) => <MemoryCard key={m.id} memory={m} compact />)}
            </div>
          </div>
        </div>
      )}
    </Card>
  )
}
