import { useState } from 'react'
import { Link } from 'react-router-dom'
import { CheckCircle2, Circle, CircleDot } from 'lucide-react'
import { api } from '../api/endpoints'
import { request } from '../api/client'
import type { ContextBundle, GraphView, IngestResult, LoginResponse, Project } from '../api/types'
import { Badge, Button, ErrorBox, Mono, StatusBadge } from './ui'
import { AlertList, LABEL_COLORS, PathChips, WhyButton } from './GraphBits'
import { cx } from '../lib/format'

// The lines the rule-based extractor turns into graph facts (no LLM). The third one is the 2-hop fact:
// it never mentions billing-service, so only the graph (billing-service -> argocd -> kubernetes) reaches it.
const TEACH = ['billing-service uses MySQL.', 'billing-service deploys through ArgoCD.', 'ArgoCD runs on Kubernetes.']
const CHANGE = 'We moved billing-service to Postgres.'
const TASK = 'fix the billing-service deploy'

type Key = 'ingest' | 'entities' | 'context' | 'change' | 'tenant'

const STEPS: { key: Key; title: string; description: string }[] = [
  { key: 'ingest', title: 'A. Ingest a conversation', description: `Alice tells a new project three things. The extractor writes them to PostgreSQL; the outbox projects them into Acme's FalkorDB graph. Two agents, Coder and Reviewer, are registered.` },
  { key: 'entities', title: 'B. See the entities appear', description: 'The neighbourhood of the billing-service entity, read straight from the graph.' },
  { key: 'context', title: 'C. Coder asks for context', description: `The Coder asks for context to "${TASK}". Every memory comes with the graph path that reached it; the Kubernetes fact is two hops away.` },
  { key: 'change', title: 'D. Change a fact', description: `Alice says "${CHANGE}" The MySQL memory is superseded. The graph knows the Coder read it, so the Coder's next context carries an alert; the Reviewer never read it and gets none.` },
  { key: 'tenant', title: 'E. Switch tenants', description: 'Bob at Globex reads his own tenant graph. The graph name comes from the signed-in tenant, never from the request.' },
]

interface State {
  project?: Project
  conversationId?: string
  coderKey?: string
  reviewerKey?: string
  taught?: IngestResult[]
  neighbours?: GraphView
  first?: ContextBundle
  changed?: IngestResult
  coderAfter?: ContextBundle
  reviewerAfter?: ContextBundle
  acme?: GraphView
  globex?: GraphView
  globexTenant?: string
}

function suffix() {
  return Math.random().toString(36).slice(2, 7)
}

export function GraphWalkthrough() {
  const [st, setSt] = useState<State>({})
  const [busy, setBusy] = useState<Key | null>(null)
  const [error, setError] = useState<unknown>()

  const done: Record<Key, boolean> = {
    ingest: !!st.taught, entities: !!st.neighbours, context: !!st.first, change: !!st.coderAfter, tenant: !!st.globex,
  }
  const order = STEPS.map((s) => s.key)
  const next = order.find((k) => !done[k])

  async function run(key: Key) {
    setBusy(key)
    setError(undefined)
    try {
      if (key === 'ingest') {
        const tag = suffix()
        const project = await request<Project>('/api/v1/projects', { body: { name: `Graph walkthrough ${tag}`, slug: `graph-${tag}` } })
        const conversation = await api.createConversation('billing-service notes', project.id)
        const taught: IngestResult[] = []
        for (const line of TEACH) taught.push(await api.sendMessage(conversation.id, line, true))
        const coder = await api.registerAgent({ name: `Coder-${tag}`, role: 'DEVELOPER' })
        const reviewer = await api.registerAgent({ name: `Reviewer-${tag}`, role: 'DEVELOPER' })
        setSt({ project, conversationId: conversation.id, coderKey: coder.apiKey, reviewerKey: reviewer.apiKey, taught })
      } else if (key === 'entities') {
        const neighbours = await api.graphNeighbours('billing-service')
        setSt((s) => ({ ...s, neighbours }))
      } else if (key === 'context') {
        const first = await api.context({ query: TASK, projectId: st.project?.id }, st.coderKey)
        setSt((s) => ({ ...s, first }))
      } else if (key === 'change' && st.conversationId) {
        const changed = await api.sendMessage(st.conversationId, CHANGE, true)
        const coderAfter = await api.context({ query: TASK, projectId: st.project?.id }, st.coderKey)
        const reviewerAfter = await api.context({ query: TASK, projectId: st.project?.id }, st.reviewerKey)
        setSt((s) => ({ ...s, changed, coderAfter, reviewerAfter }))
      } else if (key === 'tenant') {
        const acme = await api.graphView('Entity', 400)
        const bob = await request<LoginResponse>('/api/v1/auth/login', { body: { email: 'bob@globex.test', password: 'memora-demo' }, token: null })
        const globex = await api.graphView('Entity', 400, bob.token)
        setSt((s) => ({ ...s, acme, globex, globexTenant: bob.tenant.name }))
      }
    } catch (e) {
      setError(e)
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className="space-y-3" data-testid="graph-walkthrough">
      <div>
        <h2 className="text-base font-semibold">Graph walkthrough (FalkorDB)</h2>
        <p className="text-xs text-slate-500">
          Five steps against the real API. Each step reads back from the tenant's memory graph; open the{' '}
          <Link to="/graph" className="text-brand-600">Memory graph</Link> page at any point to see it.
        </p>
      </div>
      <ErrorBox error={error} />
      {STEPS.map((step) => {
        const state = done[step.key] ? 'done' : next === step.key ? 'next' : 'todo'
        const Icon = state === 'done' ? CheckCircle2 : state === 'next' ? CircleDot : Circle
        return (
          <div key={step.key} className={cx('rounded-lg border bg-white p-4 shadow-sm', state === 'next' ? 'border-brand-500' : 'border-slate-200')}>
            <div className="flex items-start gap-3">
              <Icon className={cx('mt-0.5 h-5 w-5 shrink-0', state === 'done' ? 'text-emerald-500' : state === 'next' ? 'text-brand-600' : 'text-slate-300')} aria-hidden />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <h3 className="text-sm font-semibold">{step.title}</h3>
                  <Button
                    size="sm"
                    variant={state === 'next' ? 'primary' : 'secondary'}
                    busy={busy === step.key}
                    disabled={busy !== null || (step.key !== 'ingest' && !st.taught) || (step.key === 'change' && !st.first)}
                    onClick={() => void run(step.key)}
                    aria-label={`Graph step ${step.title}`}
                  >
                    {step.key === 'ingest' && st.taught ? 'New project' : state === 'done' ? 'Again' : 'Go'}
                  </Button>
                </div>
                <p className="mt-1 text-xs text-slate-500">{step.description}</p>
                <StepOutput k={step.key} st={st} />
              </div>
            </div>
          </div>
        )
      })}
    </section>
  )
}

function StepOutput({ k, st }: { k: Key; st: State }) {
  if (k === 'ingest' && st.taught) {
    return (
      <ul className="mt-2 space-y-1 text-xs">
        {st.taught.map((t) => (
          <li key={t.message.id}>
            <span className="text-slate-500">“{t.message.content}”</span>
            {t.extracted.map((x) => <span key={x.memory.id} className="ml-2"><StatusBadge value={x.outcome} /> {x.memory.content}</span>)}
          </li>
        ))}
      </ul>
    )
  }
  if (k === 'entities' && st.neighbours) {
    const nodes = [...new Map(st.neighbours.nodes.map((n) => [n.key, n])).values()]
    return nodes.length === 0 ? <p className="mt-2 text-xs text-slate-500">Nothing yet: the projector may still be catching up. Try again.</p> : (
      <div className="mt-2 flex flex-wrap gap-1 text-[11px]" data-testid="walkthrough-entities">
        {nodes.slice(0, 24).map((n) => (
          <span key={n.key} className="rounded border px-1.5 py-0.5" style={{ borderColor: LABEL_COLORS[n.label] ?? '#cbd5e1' }}>
            <span className="font-semibold" style={{ color: LABEL_COLORS[n.label] }}>{n.label}</span> {n.name ?? n.id}
          </span>
        ))}
      </div>
    )
  }
  if (k === 'context' && st.first) return <ContextFacts bundle={st.first} />
  if (k === 'change' && st.coderAfter && st.reviewerAfter) {
    return (
      <div className="mt-2 grid gap-3 md:grid-cols-2">
        <div>
          <div className="mb-1 text-xs font-medium">Coder (read the old fact)</div>
          <AlertList alerts={st.coderAfter.alerts ?? []} />
        </div>
        <div data-testid="reviewer-alerts">
          <div className="mb-1 text-xs font-medium">Reviewer (never read it)</div>
          <AlertList alerts={st.reviewerAfter.alerts ?? []} empty="No alerts: the Reviewer never read the MySQL fact." />
        </div>
        <div className="md:col-span-2"><ContextFacts bundle={st.coderAfter} /></div>
      </div>
    )
  }
  if (k === 'tenant' && st.acme && st.globex) {
    const count = (v: GraphView) => Object.entries(v.counts ?? {}).filter(([k]) => !k.startsWith('rel:')).reduce((a, [, n]) => a + n, 0)
    const has = (v: GraphView) => v.nodes.some((n) => n.label === 'Entity' && n.id === 'billing-service')
    return (
      <table className="mt-2 text-xs" data-testid="tenant-graphs">
        <thead><tr className="text-left text-slate-500"><th className="pr-4">Tenant graph</th><th className="pr-4">Nodes</th><th>billing-service entity</th></tr></thead>
        <tbody>
          <tr><td className="pr-4">Acme (you)</td><td className="pr-4">{count(st.acme)}</td><td>{has(st.acme) ? 'yes' : 'no'}</td></tr>
          <tr><td className="pr-4">{st.globexTenant ?? 'Globex'} (bob)</td><td className="pr-4">{count(st.globex)}</td><td>{has(st.globex) ? 'yes' : 'no'}</td></tr>
        </tbody>
      </table>
    )
  }
  return null
}

function ContextFacts({ bundle }: { bundle: ContextBundle }) {
  return (
    <ul className="mt-2 space-y-2 text-xs">
      <li><Badge tone="indigo">retrieval: {bundle.retrieval ?? 'keyword'}</Badge></li>
      {bundle.facts.map((m) => {
        const p = bundle.paths?.[m.id]
        return (
          <li key={m.id} className="rounded-md bg-slate-50 p-2">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{m.content}</span>
              {p && <Mono className="text-[10px]">{p.hops} hop{p.hops === 1 ? '' : 's'} · score {p.score}</Mono>}
            </div>
            {p && <PathChips steps={p.path.steps} className="mt-1" />}
            <WhyButton memoryId={m.id} path={p} from="entity:billing-service" />
          </li>
        )
      })}
    </ul>
  )
}
