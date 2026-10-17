import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AlertTriangle, Lightbulb, Sparkles } from 'lucide-react'
import { orchestration } from '../api/orchestration'
import type { Proposal } from '../api/orchestration'
import { api } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import { Badge, Button, Card, ErrorBox, Field, Input, Mono, PageHeader, Select, Textarea } from '../components/ui'
import { WorkflowGraph } from '../components/WorkflowGraph'
import { useAsync } from '../lib/useAsync'

const EXAMPLES = [
  { label: 'E-commerce website', goal: 'Build a full-stack e-commerce website with responsive design, authentication, product search, a shopping cart, order management, database persistence, and automated tests.' },
  { label: 'Research report', goal: 'Research product search options and compare PostgreSQL full-text search, Elasticsearch and Meilisearch, covering latency, faceted filtering, operational cost and vendor lock-in.' },
  { label: 'Data analysis', goal: 'Analyse a CSV dataset of monthly sales and aggregate revenue per region.' },
  { label: 'Something else', goal: 'Organise the quarterly team offsite with a venue shortlist, an agenda and a travel budget.' },
]

export function PlanPage() {
  const { me } = useAuth()
  const isAdmin = me?.tenant.role === 'ADMIN'
  const navigate = useNavigate()
  const projects = useAsync(() => api.projects(), [])
  const [goal, setGoal] = useState(EXAMPLES[0].goal)
  const [proposal, setProposal] = useState<Proposal>()
  const [name, setName] = useState('')
  const [projectId, setProjectId] = useState('')
  const [busy, setBusy] = useState<string>()
  const [error, setError] = useState<unknown>()
  const [saved, setSaved] = useState<{ id: string; installed: string[] }>()

  async function propose() {
    setBusy('plan')
    setError(undefined)
    setSaved(undefined)
    try {
      const p = await orchestration.plan(goal)
      setProposal(p)
      setName(p.suggestedName)
    } catch (e) {
      setError(e)
    } finally {
      setBusy(undefined)
    }
  }

  async function save(runAfter: boolean) {
    if (!proposal) return
    setBusy(runAfter ? 'run' : 'save')
    setError(undefined)
    try {
      const result = saved ? { workflow: { id: saved.id }, installedAgents: saved.installed }
        : await orchestration.acceptPlan({ name, spec: proposal.spec, description: `Planned from: ${proposal.goal}`, installMissingAgents: true })
      setSaved({ id: result.workflow.id, installed: result.installedAgents })
      if (runAfter) {
        const needsGoal = proposal.spec.inputs?.some((i) => i.name === 'goal')
        const { runId } = await orchestration.startRun(result.workflow.id, {
          goal: needsGoal ? proposal.goal : undefined, projectId: projectId || undefined, options: { pacingMs: 400 },
        })
        navigate(`/runs/${runId}`)
      }
    } catch (e) {
      setError(e)
    } finally {
      setBusy(undefined)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader title="Plan a workflow"
        description="Describe a goal. Memora's rule-based planner (no language model) extracts requirements, picks the closest capability template, and proposes agents, steps, dependencies and checks for you to review and edit before anything runs." />
      <Card>
        <div className="flex flex-wrap gap-2">
          {EXAMPLES.map((e) => <Button key={e.label} size="sm" variant="secondary" onClick={() => { setGoal(e.goal); setProposal(undefined) }}>{e.label}</Button>)}
        </div>
        <div className="mt-3">
          <Field label="Goal"><Textarea rows={4} value={goal} onChange={(e) => setGoal(e.target.value)} /></Field>
        </div>
        <div className="mt-3 flex items-center gap-3">
          <Button busy={busy === 'plan'} onClick={() => void propose()} disabled={goal.trim().length < 10}><Sparkles className="h-4 w-4" aria-hidden /> Propose a workflow</Button>
          <span className="text-xs text-slate-500">Nothing is saved until you accept the proposal.</span>
        </div>
      </Card>
      <ErrorBox error={error} />
      {proposal && <ProposalView proposal={proposal} />}
      {proposal && (
        <Card title="Accept the proposal">
          <div className="grid gap-3 md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
            <Field label="Workflow name"><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
            <Field label="Run in project" hint="Empty creates a new project for the run">
              <Select value={projectId} onChange={(e) => setProjectId(e.target.value)}>
                <option value="">New project</option>
                {projects.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </Select>
            </Field>
          </div>
          <div className="mt-3 flex flex-wrap gap-2">
            <Button variant="secondary" onClick={() => navigate('/workflows/new', { state: { spec: proposal.spec, name, description: `Planned from: ${proposal.goal}` } })}>Edit in Workflow studio</Button>
            {isAdmin ? <>
              <Button variant="secondary" busy={busy === 'save'} disabled={!!saved} onClick={() => void save(false)}>{saved ? 'Saved' : 'Save workflow'}</Button>
              <Button busy={busy === 'run'} onClick={() => void save(true)}>Save and run now</Button>
            </> : <span className="self-center text-xs text-slate-500">Saving workflows is an admin action; you can still edit and review.</span>}
          </div>
          {saved && <p className="mt-2 text-xs text-emerald-700">Saved. {saved.installed.length > 0 ? `Installed agent templates: ${saved.installed.join(', ')}.` : 'All agents were already installed.'}</p>}
        </Card>
      )}
    </div>
  )
}

function ProposalView({ proposal: p }: { proposal: Proposal }) {
  return (
    <div className="space-y-6">
      <div className="grid gap-4 lg:grid-cols-3">
        <Card title="Classification">
          <div className="text-sm"><Badge tone="indigo">{p.domain.key}</Badge> confidence <Badge tone={p.domain.confidence === 'high' ? 'green' : p.domain.confidence === 'none' ? 'red' : 'amber'}>{p.domain.confidence}</Badge></div>
          <p className="mt-2 text-xs text-slate-500">Template <Mono>{p.domain.template}</Mono> chosen from keyword evidence: {p.domain.evidence.length ? p.domain.evidence.join(', ') : 'none'}.</p>
          <p className="mt-1 text-xs text-slate-500">Planner: <Mono>{p.planner}</Mono> (pattern matching, not understanding)</p>
        </Card>
        <Card title="Deliverables" className="lg:col-span-2">
          <ul className="list-disc space-y-0.5 pl-5 text-sm">{p.deliverables.map((d) => <li key={d}>{d}</li>)}</ul>
        </Card>
      </div>
      {(p.limitations.length > 0 || p.warnings.length > 0) && (
        <Card title="Limitations and warnings" className="border-amber-300">
          <ul className="space-y-1 text-sm">
            {p.limitations.map((l) => <li key={l} className="flex gap-2"><Lightbulb className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" aria-hidden />{l}</li>)}
            {p.warnings.map((w) => <li key={w} className="flex gap-2"><AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" aria-hidden />{w}</li>)}
          </ul>
        </Card>
      )}
      <Card title={`Requirements (${p.requirements.length})`}>
        <table className="w-full text-sm">
          <thead className="text-left text-xs uppercase text-slate-500"><tr><th className="py-1">Id</th><th>Requirement</th><th>Category</th><th>Acceptance criteria</th></tr></thead>
          <tbody className="divide-y divide-slate-100 align-top">
            {p.requirements.map((r) => (
              <tr key={r.id}><td className="py-1.5 pr-2"><Mono>{r.id}</Mono></td><td className="pr-3">{r.text} {r.priority === 'SHOULD' && <Badge>should</Badge>}</td>
                <td className="pr-3"><Badge tone={r.category === 'general' ? 'amber' : 'gray'}>{r.category}</Badge></td>
                <td className="text-xs text-slate-600"><ul className="list-disc pl-4">{r.acceptance.map((a) => <li key={a}>{a}</li>)}</ul></td></tr>
            ))}
          </tbody>
        </table>
      </Card>
      <Card title="Proposed steps and dependencies" actions={<span className="text-xs text-slate-500">{p.parallelGroups.length} group(s) can run in parallel</span>}>
        <WorkflowGraph nodes={p.spec.steps.map((s) => ({
          key: s.key, label: s.name ?? s.key, sublabel: p.roles.find((r) => r.agent === s.agent)?.name ?? s.agent, deps: s.dependsOn ?? [],
          remediationOf: s.remediation ? p.spec.steps.find((o) => o.loop?.remediationStep === s.key)?.key : undefined,
          badges: [s.approval?.required ? 'approval' : '', s.when ? 'conditional' : '', s.loop ? 'loop' : ''].filter(Boolean),
        }))} />
        {p.parallelGroups.length > 0 && <p className="mt-2 text-xs text-slate-500">Parallel groups: {p.parallelGroups.map((g) => g.join(' ∥ ')).join(' · ')}</p>}
        <ul className="mt-3 divide-y divide-slate-100 text-sm">
          {p.steps.map((s) => (
            <li key={s.key} className="py-1.5">
              <strong>{s.name}</strong> <span className="text-xs text-slate-500">by {s.agent}{s.dependsOn.length ? ` after ${s.dependsOn.join(', ')}` : ''}</span>
              {s.purpose && <div className="text-xs text-slate-600">{s.purpose}</div>}
              <div className="text-[11px] text-slate-500">inputs: {s.inputs.join(', ') || 'none'}{s.expectedOutputs ? ` · outputs: ${s.expectedOutputs.join(', ')}` : ''}</div>
            </li>
          ))}
        </ul>
      </Card>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Agents (roles)">
          <ul className="space-y-1 text-sm">
            {p.roles.map((r) => <li key={r.agent} className="flex flex-wrap items-center gap-2">{r.name} <Mono>{r.agent}</Mono>
              <Badge tone={r.installed ? 'green' : 'amber'}>{r.installed ? 'installed' : 'template'}</Badge><Badge tone="indigo">{r.executor}</Badge></li>)}
          </ul>
        </Card>
        <Card title="Missing information, assumptions and approvals">
          <List title="Missing information" items={p.missingInformation} />
          <List title="Unclear" items={p.unclear} />
          <List title="Assumptions" items={p.assumptions} />
          <List title="Approvals" items={p.approvals} />
        </Card>
      </div>
    </div>
  )
}

function List({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null
  return (
    <div className="mb-2 text-sm">
      <div className="text-xs font-semibold uppercase tracking-wide text-slate-500">{title}</div>
      <ul className="list-disc pl-5">{items.map((i) => <li key={i}>{i}</li>)}</ul>
    </div>
  )
}
