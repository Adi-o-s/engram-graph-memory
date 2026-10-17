import type { StepRunView } from '../api/orchestration'
import { cx } from '../lib/format'

const colors: Record<string, string> = {
  SUCCEEDED: 'bg-emerald-500', FAILED: 'bg-rose-500', RUNNING: 'bg-blue-500 animate-pulse', CANCELLED: 'bg-slate-400',
  READY: 'bg-sky-300', SKIPPED: 'bg-slate-300',
}

/**
 * Gantt-style view of when each step iteration actually ran (from persisted start and finish times).
 * Overlapping bars are steps that ran in parallel.
 */
export function RunTimeline({ steps, now = Date.now(), onSelect, selected }: {
  steps: StepRunView[]; now?: number; onSelect?: (s: StepRunView) => void; selected?: string
}) {
  const started = steps.filter((s) => s.startedAt)
  if (started.length === 0) return <p className="text-xs text-slate-500">No step has started yet.</p>
  const t0 = Math.min(...started.map((s) => new Date(s.startedAt!).getTime()))
  const t1 = Math.max(...started.map((s) => (s.finishedAt ? new Date(s.finishedAt).getTime() : now)), t0 + 1)
  const span = t1 - t0
  const rows = [...started].sort((a, b) => new Date(a.startedAt!).getTime() - new Date(b.startedAt!).getTime())
  return (
    <div className="space-y-1" role="list" aria-label="Execution timeline">
      {rows.map((s) => {
        const start = new Date(s.startedAt!).getTime() - t0
        const end = (s.finishedAt ? new Date(s.finishedAt).getTime() : now) - t0
        const left = (start / span) * 100
        const width = Math.max(((end - start) / span) * 100, 0.8)
        return (
          <button key={s.id} type="button" role="listitem" onClick={() => onSelect?.(s)}
            className={cx('grid w-full grid-cols-[10rem_minmax(0,1fr)_4.5rem] items-center gap-2 rounded px-1 py-0.5 text-left text-xs hover:bg-slate-50',
              selected === s.id && 'bg-brand-50')}>
            <span className="truncate text-slate-700">{s.name}{s.iteration > 1 || s.remediation ? ` #${s.iteration}` : ''}</span>
            <span className="relative h-3 rounded bg-slate-100">
              <span className={cx('absolute top-0 h-3 rounded', colors[s.status] ?? 'bg-slate-400')}
                style={{ left: `${left}%`, width: `${width}%` }} />
            </span>
            <span className="text-right font-mono text-[11px] text-slate-500">{((end - start) / 1000).toFixed(1)} s</span>
          </button>
        )
      })}
      <div className="flex justify-between pl-[10.5rem] pr-[5rem] font-mono text-[10px] text-slate-400">
        <span>0 s</span><span>{(span / 1000).toFixed(1)} s</span>
      </div>
    </div>
  )
}
