import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api/endpoints'
import type { IngestResult, Memory, Message } from '../api/types'
import { Badge, Button, Card, Empty, ErrorBox, Input, Mono, PageHeader, Select, Spinner, StatusBadge, Textarea } from '../components/ui'
import { MemoryCard } from '../components/MemoryCard'
import { useAsync } from '../lib/useAsync'
import { cx, dateTime, timeAgo } from '../lib/format'

export function ConversationsPage() {
  const [params, setParams] = useSearchParams()
  const selectedId = params.get('id') ?? undefined
  const highlight = params.get('message') ?? undefined
  const projects = useAsync(() => api.projects(), [])
  const [projectFilter, setProjectFilter] = useState('')
  const list = useAsync(() => api.conversations(projectFilter || undefined), [projectFilter])
  const [newTitle, setNewTitle] = useState('')
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<unknown>()

  const select = (id: string) => setParams({ id })

  async function create(e: React.FormEvent) {
    e.preventDefault()
    if (!newTitle.trim()) return
    setCreating(true)
    setError(undefined)
    try {
      const c = await api.createConversation(newTitle.trim(), projectFilter || undefined)
      setNewTitle('')
      await list.reload()
      select(c.id)
    } catch (err) {
      setError(err)
    } finally {
      setCreating(false)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Conversations"
        description="Messages are stored verbatim and survive restarts. When extraction is on, each new message runs through the rule-based extractor and any memories it produces link back to the message."
      />
      <div className="grid gap-6 lg:grid-cols-[18rem_minmax(0,1fr)]">
        <div className="space-y-3">
          <Select aria-label="Filter by project" value={projectFilter} onChange={(e) => setProjectFilter(e.target.value)}>
            <option value="">All projects</option>
            {projects.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
          <form onSubmit={create} className="flex gap-2">
            <Input aria-label="New conversation title" placeholder="New conversation title" value={newTitle} onChange={(e) => setNewTitle(e.target.value)} />
            <Button type="submit" busy={creating} disabled={!newTitle.trim()}>Add</Button>
          </form>
          <ErrorBox error={error ?? list.error} />
          {!list.data ? <Spinner /> : list.data.items.length === 0 ? <Empty>No conversations.</Empty> : (
            <ul className="space-y-1.5">
              {list.data.items.map((c) => (
                <li key={c.id}>
                  <button
                    type="button"
                    onClick={() => select(c.id)}
                    className={cx('w-full rounded-lg border bg-white px-3 py-2 text-left', c.id === selectedId ? 'border-brand-500 ring-2 ring-brand-100' : 'border-slate-200 hover:border-slate-300')}
                  >
                    <div className="truncate text-sm font-medium">{c.title}</div>
                    <div className="text-xs text-slate-500">{c.messageCount} messages · {timeAgo(c.lastMessageAt ?? c.createdAt)}</div>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        {selectedId ? <ConversationView key={selectedId} id={selectedId} highlight={highlight} onChanged={() => void list.reload()} /> : (
          <Card><Empty>Select a conversation, or create one.</Empty></Card>
        )}
      </div>
    </div>
  )
}

function ConversationView({ id, highlight, onChanged }: { id: string; highlight?: string; onChanged: () => void }) {
  const conversation = useAsync(() => api.conversation(id), [id])
  const messages = useAsync(() => api.messages(id), [id])
  const [text, setText] = useState('')
  const [extract, setExtract] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const [last, setLast] = useState<IngestResult>()
  const [openMessage, setOpenMessage] = useState<string | undefined>(highlight)
  const [summarizing, setSummarizing] = useState(false)
  const endRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!highlight) endRef.current?.scrollIntoView?.({ block: 'nearest' })
  }, [messages.data, highlight])

  async function send(e: React.FormEvent) {
    e.preventDefault()
    if (!text.trim()) return
    setBusy(true)
    setError(undefined)
    try {
      const r = await api.sendMessage(id, text.trim(), extract)
      setLast(r)
      setText('')
      await Promise.all([messages.reload(), conversation.reload()])
      onChanged()
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  async function summarize() {
    setSummarizing(true)
    setError(undefined)
    try {
      conversation.setData(await api.summarize(id))
    } catch (err) {
      setError(err)
    } finally {
      setSummarizing(false)
    }
  }

  const c = conversation.data
  return (
    <div className="space-y-4">
      <Card
        title={c ? c.title : 'Conversation'}
        actions={<Button size="sm" variant="secondary" busy={summarizing} onClick={summarize}>Summarize</Button>}
      >
        {c?.summary && (
          <div className="mb-4 rounded-lg bg-brand-50 p-3 text-sm text-brand-700">
            <div className="mb-1 text-xs font-semibold uppercase tracking-wide">Summary (deterministic)</div>
            <div className="whitespace-pre-wrap">{c.summary}</div>
          </div>
        )}
        {!messages.data ? <Spinner /> : messages.data.items.length === 0 ? <Empty>No messages yet.</Empty> : (
          <ol className="max-h-[28rem] space-y-2 overflow-y-auto pr-1">
            {messages.data.items.map((m) => (
              <MessageRow key={m.id} conversationId={id} message={m} open={openMessage === m.id} onToggle={() => setOpenMessage(openMessage === m.id ? undefined : m.id)} />
            ))}
            <div ref={endRef} />
          </ol>
        )}
        <form onSubmit={send} className="mt-4 space-y-2 border-t border-slate-100 pt-4">
          <Textarea
            aria-label="Message"
            rows={2}
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder='Try "We switched from PostgreSQL to CockroachDB." or "I prefer tabs over spaces."'
          />
          <div className="flex items-center justify-between gap-2">
            <label className="flex items-center gap-1.5 text-sm text-slate-600">
              <input type="checkbox" checked={extract} onChange={(e) => setExtract(e.target.checked)} /> Extract memories
            </label>
            <Button type="submit" busy={busy} disabled={!text.trim()}>Send</Button>
          </div>
          <ErrorBox error={error} />
        </form>
      </Card>
      {last && (
        <Card title={`Extraction result for message #${last.message.seq}`}>
          {last.extracted.length === 0 ? (
            <p className="text-sm text-slate-500">No memory extracted. The rule-based extractor skips questions, negations, very short sentences and anything that looks like a secret.</p>
          ) : (
            <div className="space-y-2">
              {last.extracted.map((x) => (
                <div key={x.memory.id}>
                  <div className="mb-1 flex items-center gap-2 text-xs text-slate-500">
                    <StatusBadge value={x.outcome} /> {x.rule && <>rule <Mono>{x.rule}</Mono></>}
                  </div>
                  <MemoryCard memory={x.memory} />
                </div>
              ))}
            </div>
          )}
        </Card>
      )}
    </div>
  )
}

function MessageRow({ conversationId, message, open, onToggle }: { conversationId: string; message: Message; open: boolean; onToggle: () => void }) {
  const [memories, setMemories] = useState<Memory[]>()
  const [error, setError] = useState<unknown>()
  useEffect(() => {
    if (open && !memories) api.messageMemories(conversationId, message.id).then(setMemories, setError)
  }, [open, memories, conversationId, message.id])

  return (
    <li className={cx('rounded-lg border p-2.5', open ? 'border-brand-500 bg-brand-50/40' : 'border-slate-200 bg-white')}>
      <button type="button" className="w-full text-left" onClick={onToggle}>
        <div className="flex items-center gap-2 text-xs text-slate-500">
          <Badge tone={message.role === 'USER' ? 'indigo' : 'violet'}>{message.role.toLowerCase()}</Badge>
          <span>#{message.seq}</span>
          <span title={dateTime(message.createdAt)}>{timeAgo(message.createdAt)}</span>
          <span className="ml-auto">{open ? 'hide memories' : 'show memories'}</span>
        </div>
        <div className="mt-1 whitespace-pre-wrap text-sm">{message.content}</div>
      </button>
      {open && (
        <div className="mt-2 space-y-1.5">
          <ErrorBox error={error} />
          {!memories ? (!error && <Spinner />) : memories.length === 0 ? (
            <p className="text-xs text-slate-500">No memories derived from this message.</p>
          ) : memories.map((m) => <MemoryCard key={m.id} memory={m} compact />)}
        </div>
      )}
    </li>
  )
}
