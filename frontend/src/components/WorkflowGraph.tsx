import { cx } from '../lib/format'

export interface GraphNode {
  key: string
  label: string
  sublabel?: string
  status?: string
  deps: string[]
  /** Remediation steps hang off their loop step instead of the main graph. */
  remediationOf?: string
  iterations?: number
  badges?: string[]
}

const W = 184
const H = 62
const GAP_X = 56
const GAP_Y = 18

const fills: Record<string, { fill: string; stroke: string; text: string }> = {
  PENDING: { fill: '#f8fafc', stroke: '#cbd5e1', text: '#475569' },
  READY: { fill: '#f0f9ff', stroke: '#38bdf8', text: '#0369a1' },
  RUNNING: { fill: '#eff6ff', stroke: '#2563eb', text: '#1d4ed8' },
  WAITING_APPROVAL: { fill: '#fffbeb', stroke: '#f59e0b', text: '#b45309' },
  BLOCKED: { fill: '#fff7ed', stroke: '#fb923c', text: '#c2410c' },
  SUCCEEDED: { fill: '#ecfdf5', stroke: '#10b981', text: '#047857' },
  FAILED: { fill: '#fff1f2', stroke: '#f43f5e', text: '#be123c' },
  SKIPPED: { fill: '#f8fafc', stroke: '#94a3b8', text: '#64748b' },
  CANCELLED: { fill: '#f1f5f9', stroke: '#94a3b8', text: '#64748b' },
  DEFAULT: { fill: '#ffffff', stroke: '#c7d2fe', text: '#3730a3' },
}

/** Longest-path layering of a dependency graph; unknown or cyclic references are ignored. */
export function layers(nodes: GraphNode[]): string[][] {
  const main = nodes.filter((n) => !n.remediationOf)
  const keys = new Set(main.map((n) => n.key))
  const depth = new Map<string, number>()
  const byKey = new Map(main.map((n) => [n.key, n]))
  const visiting = new Set<string>()
  const d = (k: string): number => {
    if (depth.has(k)) return depth.get(k)!
    if (visiting.has(k)) return 0
    visiting.add(k)
    const n = byKey.get(k)
    const value = n ? Math.max(0, ...n.deps.filter((x) => keys.has(x)).map((x) => d(x) + 1)) : 0
    visiting.delete(k)
    depth.set(k, value)
    return value
  }
  main.forEach((n) => d(n.key))
  const out: string[][] = []
  main.forEach((n) => {
    const i = depth.get(n.key) ?? 0
    ;(out[i] ??= []).push(n.key)
  })
  return out.filter(Boolean)
}

export function WorkflowGraph({ nodes, selected, onSelect, className }: {
  nodes: GraphNode[]; selected?: string; onSelect?: (key: string) => void; className?: string
}) {
  const layout = layers(nodes)
  const pos = new Map<string, { x: number; y: number }>()
  const remediation = nodes.filter((n) => n.remediationOf)
  let maxRows = 0
  layout.forEach((col, ci) => {
    let row = 0
    col.forEach((k) => {
      pos.set(k, { x: 16 + ci * (W + GAP_X), y: 16 + row * (H + GAP_Y) })
      row++
      remediation.filter((r) => r.remediationOf === k).forEach((r) => {
        pos.set(r.key, { x: 16 + ci * (W + GAP_X) + 22, y: 16 + row * (H + GAP_Y) })
        row++
      })
    })
    maxRows = Math.max(maxRows, row)
  })
  const width = 32 + layout.length * W + Math.max(0, layout.length - 1) * GAP_X
  const height = 32 + maxRows * H + Math.max(0, maxRows - 1) * GAP_Y
  const byKey = new Map(nodes.map((n) => [n.key, n]))

  return (
    <div className={cx('overflow-x-auto rounded-lg border border-slate-200 bg-white', className)}>
      <svg width={Math.max(width, 200)} height={Math.max(height, 80)} role="img" aria-label="Workflow dependency graph">
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" fill="#94a3b8" />
          </marker>
        </defs>
        {nodes.flatMap((n) =>
          n.deps.filter((dep) => pos.has(dep) && pos.has(n.key) && !n.remediationOf).map((dep) => {
            const a = pos.get(dep)!
            const b = pos.get(n.key)!
            const x1 = a.x + W
            const y1 = a.y + H / 2
            const x2 = b.x
            const y2 = b.y + H / 2
            const mx = (x1 + x2) / 2
            return <path key={dep + '->' + n.key} d={`M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2 - 2} ${y2}`}
              fill="none" stroke="#94a3b8" strokeWidth={1.5} markerEnd="url(#arrow)" />
          }),
        )}
        {remediation.filter((r) => pos.has(r.key) && r.remediationOf && pos.has(r.remediationOf)).map((r) => {
          const a = pos.get(r.remediationOf!)!
          const b = pos.get(r.key)!
          return <path key={'loop-' + r.key} d={`M ${a.x + 12} ${a.y + H} L ${b.x + 12} ${b.y}`} fill="none" stroke="#f59e0b"
            strokeWidth={1.5} strokeDasharray="4 3" markerEnd="url(#arrow)" />
        })}
        {nodes.filter((n) => pos.has(n.key)).map((n) => {
          const p = pos.get(n.key)!
          const c = fills[n.status ?? 'DEFAULT'] ?? fills.DEFAULT
          const isSelected = selected === n.key
          return (
            <g key={n.key} transform={`translate(${p.x},${p.y})`} role="button" tabIndex={0}
              aria-label={`${n.label}${n.status ? ', ' + n.status.toLowerCase().replace('_', ' ') : ''}`}
              className="cursor-pointer focus:outline-none" onClick={() => onSelect?.(n.key)}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect?.(n.key) } }}>
              <rect width={W} height={H} rx={10} fill={c.fill} stroke={isSelected ? '#4f46e5' : c.stroke}
                strokeWidth={isSelected ? 3 : 1.5} strokeDasharray={n.status === 'SKIPPED' || n.remediationOf ? '5 3' : undefined}>
                {n.status === 'RUNNING' && <animate attributeName="stroke-opacity" values="1;0.35;1" dur="1.2s" repeatCount="indefinite" />}
              </rect>
              <text x={10} y={22} fontSize={12} fontWeight={600} fill="#0f172a">{truncate(n.label, 24)}</text>
              <text x={10} y={40} fontSize={11} fill="#64748b">{truncate(n.sublabel ?? n.key, 28)}</text>
              {n.status && <text x={10} y={55} fontSize={10} fontWeight={600} fill={c.text}>{n.status.replace('_', ' ').toLowerCase()}</text>}
              {(n.iterations ?? 0) > 1 && (
                <g transform={`translate(${W - 30}, 6)`}>
                  <rect width={24} height={16} rx={8} fill="#fef3c7" stroke="#f59e0b" />
                  <text x={12} y={12} fontSize={10} textAnchor="middle" fill="#92400e">×{n.iterations}</text>
                </g>
              )}
              {n.badges?.map((b, i) => (
                <text key={b} x={W - 8} y={55 - i * 12} fontSize={9} textAnchor="end" fill="#6366f1">{b}</text>
              ))}
            </g>
          )
        })}
        {/* keep unused lookup referenced for type-checkers that flag it */}
        {byKey.size === 0 && <text x={16} y={40} fontSize={12} fill="#94a3b8">No steps yet</text>}
      </svg>
    </div>
  )
}

function truncate(s: string, n: number) {
  return s.length <= n ? s : s.slice(0, n - 1) + '…'
}
