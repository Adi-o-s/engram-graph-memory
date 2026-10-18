import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/endpoints'
import type { Memory, MemoryScope, MemoryType, MemoryWriteResult } from '../api/types'
import { Badge, Button, Card, Empty, ErrorBox, Field, Input, JsonView, Mono, PageHeader, Select, Spinner, StatusBadge, Textarea } from '../components/ui'
import { MemoryCard } from '../components/MemoryCard'
import { WhyButton } from '../components/GraphBits'
import { useAsync } from '../lib/useAsync'
import { dateTime, shortId } from '../lib/format'

const TYPES: MemoryType[] = ['SEMANTIC', 'EPISODIC', 'PROCEDURAL']

export function MemoryExplorerPage() {
  const projects = useAsync(() => api.projects(), [])
  const [q, setQ] = useState('')
  const [submittedQ, setSubmittedQ] = useState('')
  const [types, setTypes] = useState<MemoryType[]>([])
  const [projectId, setProjectId] = useState('')
  const [includeHistory, setIncludeHistory] = useState(false)
  const [sort, setSort] = useState('RELEVANCE')
  const [offset, setOffset] = useState(0)
  const [selected, setSelected] = useState<Memory>()
  const [showCreate, setShowCreate] = useState(false)
  const limit = 25

  const results = useAsync(
    () => api.searchMemories({ q: submittedQ || undefined, type: types, projectId: projectId || undefined, includeHistory, sort, limit, offset }),
    [submittedQ, types.join(','), projectId, includeHistory, sort, offset],
  )

  const toggleType = (t: MemoryType) => {
    setOffset(0)
    setTypes((prev) => (prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t]))
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Memory explorer"
        description="Search shared memory with PostgreSQL full-text search. Results are ranked by text relevance, importance, recency and confidence."
        actions={<Button onClick={() => setShowCreate((v) => !v)}>{showCreate ? 'Close form' : 'Add memory'}</Button>}
      />
      {showCreate && (
        <CreateMemoryForm
          projects={projects.data ?? []}
          onCreated={(r) => {
            setSelected(r.memory)
            void results.reload()
          }}
        />
      )}
      <form
        className="flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm"
        onSubmit={(e) => {
          e.preventDefault()
          setOffset(0)
          setSubmittedQ(q.trim())
        }}
      >
        <div className="min-w-56 flex-1">
          <Field label="Search">
            <Input placeholder="e.g. database, pull requests, migrations" value={q} onChange={(e) => setQ(e.target.value)} />
          </Field>
        </div>
        <Field label="Project">
          <Select value={projectId} onChange={(e) => { setOffset(0); setProjectId(e.target.value) }}>
            <option value="">All projects</option>
            {projects.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
        </Field>
        <Field label="Sort">
          <Select value={sort} onChange={(e) => setSort(e.target.value)}>
            <option value="RELEVANCE">Relevance</option>
            <option value="RECENT">Most recent</option>
            <option value="IMPORTANCE">Importance</option>
          </Select>
        </Field>
        <fieldset className="flex items-center gap-3 pb-2 text-sm">
          <legend className="sr-only">Memory types</legend>
          {TYPES.map((t) => (
            <label key={t} className="flex items-center gap-1">
              <input type="checkbox" checked={types.includes(t)} onChange={() => toggleType(t)} /> {t.toLowerCase()}
            </label>
          ))}
          <label className="flex items-center gap-1">
            <input type="checkbox" checked={includeHistory} onChange={(e) => { setOffset(0); setIncludeHistory(e.target.checked) }} /> include superseded
          </label>
        </fieldset>
        <Button type="submit">Search</Button>
      </form>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div>
          <ErrorBox error={results.error} />
          {!results.data ? <Spinner /> : (
            <>
              <div className="mb-2 flex items-center justify-between text-xs text-slate-500">
                <span>
                  {results.data.total} result{results.data.total === 1 ? '' : 's'}
                  {results.data.tsQuery && <> · tsquery <Mono>{results.data.tsQuery}</Mono></>}
                </span>
                <span className="flex gap-2">
                  <Button size="sm" variant="secondary" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - limit))}>Previous</Button>
                  <Button size="sm" variant="secondary" disabled={offset + limit >= results.data.total} onClick={() => setOffset(offset + limit)}>Next</Button>
                </span>
              </div>
              {results.data.items.length === 0 ? <Empty>No memories match.</Empty> : (
                <div className="space-y-2">
                  {results.data.items.map((m) => <MemoryCard key={m.id} memory={m} onSelect={setSelected} selected={selected?.id === m.id} />)}
                </div>
              )}
            </>
          )}
        </div>
        <div className="lg:sticky lg:top-6 lg:self-start">
          {selected ? (
            <MemoryDetail key={selected.id} id={selected.id} onChanged={() => void results.reload()} onSelect={setSelected} />
          ) : (
            <Card title="Details"><Empty>Select a memory to see its provenance and history.</Empty></Card>
          )}
        </div>
      </div>
    </div>
  )
}

function MemoryDetail({ id, onChanged, onSelect }: { id: string; onChanged: () => void; onSelect: (m: Memory) => void }) {
  const memory = useAsync(() => api.memory(id), [id])
  const history = useAsync(() => api.history(id), [id])
  const [revision, setRevision] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const m = memory.data

  async function act(fn: () => Promise<unknown>) {
    setBusy(true)
    setError(undefined)
    try {
      await fn()
      await Promise.all([memory.reload(), history.reload()])
      onChanged()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  if (!m) return memory.error ? <ErrorBox error={memory.error} /> : <Spinner />
  return (
    <Card title="Details" actions={<Mono>{shortId(m.id)}</Mono>}>
      <div className="space-y-4 text-sm">
        <div className="flex flex-wrap gap-1.5">
          <StatusBadge value={m.type} /> <StatusBadge value={m.status} /> <Badge>v{m.version}</Badge>
          {m.category && <Badge>{m.category}</Badge>}
          {m.tags.map((t) => <Badge key={t} tone="blue">#{t}</Badge>)}
        </div>
        <WhyButton memoryId={m.id} from={m.subject ? `entity:${m.subject}` : undefined} />
        <p className="text-base text-slate-900">{m.content}</p>
        <dl className="grid grid-cols-[8rem_1fr] gap-x-3 gap-y-1 text-xs">
          {m.factKey && (<><dt className="text-slate-500">Fact</dt><dd><Mono>{m.subject} · {m.predicate} = {m.value}</Mono></dd></>)}
          <dt className="text-slate-500">Scope</dt><dd><Mono>{m.scopeKey}</Mono></dd>
          <dt className="text-slate-500">Importance</dt><dd>{m.importance} / 5</dd>
          <dt className="text-slate-500">Confidence</dt><dd>{m.confidence.toFixed(2)}</dd>
          <dt className="text-slate-500">Source</dt>
          <dd>
            {m.source.type.toLowerCase()}
            {m.source.extractor && <> via <Mono>{m.source.extractor}</Mono></>}
            {m.source.ref && <> · <Mono>{m.source.ref}</Mono></>}
          </dd>
          {m.source.messageId && m.conversationId && (
            <><dt className="text-slate-500">From message</dt><dd><Link className="text-brand-600 hover:underline" to={`/conversations?id=${m.conversationId}&message=${m.source.messageId}`}>open conversation</Link></dd></>
          )}
          {m.taskId && (<><dt className="text-slate-500">Task</dt><dd><Mono>{shortId(m.taskId)}</Mono></dd></>)}
          <dt className="text-slate-500">Accessed</dt><dd>{m.accessCount} times{m.lastAccessedAt ? `, last ${dateTime(m.lastAccessedAt)}` : ''}</dd>
          <dt className="text-slate-500">Created</dt><dd>{dateTime(m.createdAt)}</dd>
        </dl>

        <div>
          <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">Version history</h3>
          {!history.data ? <Spinner /> : history.data.versions.length <= 1 ? (
            <p className="text-xs text-slate-500">No earlier or later versions.</p>
          ) : (
            <ol className="space-y-1.5">
              {history.data.versions.map((v) => (
                <li key={v.id}>
                  <button type="button" className="flex w-full items-center gap-2 rounded-md px-2 py-1 text-left text-xs hover:bg-slate-50" onClick={() => onSelect(v)}>
                    <Badge>v{v.version}</Badge><StatusBadge value={v.status} />
                    <span className={v.id === m.id ? 'font-semibold' : ''}>{v.value ?? v.content}</span>
                    <span className="ml-auto text-slate-400">{dateTime(v.createdAt)}</span>
                  </button>
                </li>
              ))}
            </ol>
          )}
        </div>

        {m.status === 'CURRENT' && (
          <div className="space-y-2 border-t border-slate-100 pt-3">
            <Field label="Revise" hint="Creates a new version and marks this one superseded.">
              <Textarea rows={2} value={revision} onChange={(e) => setRevision(e.target.value)} placeholder={m.content} />
            </Field>
            <div className="flex gap-2">
              <Button size="sm" busy={busy} disabled={!revision.trim()} onClick={() => act(async () => {
                const r = await api.reviseMemory(m.id, revision.trim())
                setRevision('')
                onSelect(r.memory)
              })}>Save new version</Button>
              <Button size="sm" variant="danger" busy={busy} onClick={() => act(() => api.retractMemory(m.id, 'Retracted from the dashboard'))}>Retract</Button>
            </div>
          </div>
        )}
        <ErrorBox error={error} />
        {Object.keys(m.metadata).length > 0 && (
          <details><summary className="cursor-pointer text-xs text-slate-500">Metadata</summary><JsonView value={m.metadata} className="mt-2" /></details>
        )}
      </div>
    </Card>
  )
}

function CreateMemoryForm({ projects, onCreated }: { projects: { id: string; name: string }[]; onCreated: (r: MemoryWriteResult) => void }) {
  const [type, setType] = useState<MemoryType>('SEMANTIC')
  const [content, setContent] = useState('')
  const [subject, setSubject] = useState('')
  const [predicate, setPredicate] = useState('')
  const [value, setValue] = useState('')
  const [category, setCategory] = useState('')
  const [scope, setScope] = useState<MemoryScope>('PROJECT')
  const [projectId, setProjectId] = useState(projects[0]?.id ?? '')
  const [importance, setImportance] = useState(3)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const [result, setResult] = useState<MemoryWriteResult>()

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      const r = await api.createMemory({
        type, content, category: category || undefined, importance, scope,
        projectId: scope === 'PROJECT' ? projectId || undefined : undefined,
        subject: subject || undefined, predicate: predicate || undefined, value: value || undefined,
      })
      setResult(r)
      onCreated(r)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title="Add a memory">
      <form onSubmit={submit} className="grid gap-3 md:grid-cols-4">
        <Field label="Type">
          <Select value={type} onChange={(e) => setType(e.target.value as MemoryType)}>
            {TYPES.map((t) => <option key={t} value={t}>{t.toLowerCase()}</option>)}
          </Select>
        </Field>
        <Field label="Scope">
          <Select value={scope} onChange={(e) => setScope(e.target.value as MemoryScope)}>
            <option value="PROJECT">project</option>
            <option value="TENANT">tenant (shared)</option>
            <option value="USER">only me</option>
          </Select>
        </Field>
        <Field label="Project">
          <Select value={projectId} disabled={scope !== 'PROJECT'} onChange={(e) => setProjectId(e.target.value)}>
            {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
        </Field>
        <Field label="Importance (1–5)">
          <Input type="number" min={1} max={5} value={importance} onChange={(e) => setImportance(Number(e.target.value))} />
        </Field>
        <div className="md:col-span-4">
          <Field label="Content">
            <Textarea rows={2} required value={content} onChange={(e) => setContent(e.target.value)} placeholder="The team deploys on Thursdays." />
          </Field>
        </div>
        <Field label="Subject" hint="Optional. Subject + predicate form a fact key; a new value supersedes the old one.">
          <Input value={subject} onChange={(e) => setSubject(e.target.value)} placeholder="project" />
        </Field>
        <Field label="Predicate"><Input value={predicate} onChange={(e) => setPredicate(e.target.value)} placeholder="deploy.day" /></Field>
        <Field label="Value"><Input value={value} onChange={(e) => setValue(e.target.value)} placeholder="thursday" /></Field>
        <Field label="Category"><Input value={category} onChange={(e) => setCategory(e.target.value)} placeholder="process" /></Field>
        <div className="flex items-center gap-3 md:col-span-4">
          <Button type="submit" busy={busy}>Save memory</Button>
          {result && (
            <span className="text-sm text-slate-600">
              Saved: <StatusBadge value={result.outcome} />
              {result.supersededMemoryId && <> (superseded <Mono>{shortId(result.supersededMemoryId)}</Mono>)</>}
            </span>
          )}
        </div>
        <ErrorBox error={error} className="md:col-span-4" />
      </form>
    </Card>
  )
}
