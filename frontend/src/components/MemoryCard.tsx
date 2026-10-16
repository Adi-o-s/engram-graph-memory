import type { Memory } from '../api/types'
import { Badge, Mono, StatusBadge } from './ui'
import { cx, shortId, timeAgo } from '../lib/format'

export function MemoryCard({ memory, onSelect, selected, compact }: {
  memory: Memory; onSelect?: (m: Memory) => void; selected?: boolean; compact?: boolean
}) {
  const body = (
    <>
      <div className="flex flex-wrap items-center gap-1.5">
        <StatusBadge value={memory.type} />
        <StatusBadge value={memory.status} />
        {memory.category && <Badge>{memory.category}</Badge>}
        <Badge tone="gray">{memory.scope.toLowerCase()}</Badge>
        {memory.version > 1 && <Badge tone="amber">v{memory.version}</Badge>}
        {memory.score !== undefined && <Mono className="ml-auto">score {memory.score.toFixed(2)}</Mono>}
      </div>
      <p className={cx('mt-1.5 text-sm text-slate-800', memory.status !== 'CURRENT' && 'text-slate-500 line-through decoration-slate-300')}>
        {memory.content}
      </p>
      {!compact && (
        <div className="mt-1.5 flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] text-slate-500">
          {memory.factKey && <span>fact <Mono>{memory.factKey}</Mono> = <Mono>{memory.value}</Mono></span>}
          <span>importance {memory.importance}</span>
          <span>confidence {memory.confidence.toFixed(2)}</span>
          <span>source {memory.source.type.toLowerCase()}{memory.source.extractor ? ` · ${memory.source.extractor}` : ''}</span>
          <span>{timeAgo(memory.createdAt)}</span>
          <span>id <Mono>{shortId(memory.id)}</Mono></span>
        </div>
      )}
    </>
  )
  if (!onSelect) return <div className="rounded-lg border border-slate-200 bg-white p-3">{body}</div>
  return (
    <button
      type="button"
      onClick={() => onSelect(memory)}
      className={cx(
        'block w-full rounded-lg border bg-white p-3 text-left transition hover:border-brand-500',
        selected ? 'border-brand-500 ring-2 ring-brand-100' : 'border-slate-200',
      )}
    >
      {body}
    </button>
  )
}
