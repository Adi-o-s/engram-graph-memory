import { useEffect, useMemo, useRef, useState } from 'react'
import ForceGraph2D, { type ForceGraphMethods } from 'react-force-graph-2d'
import { api } from '../api/endpoints'
import type { GraphEdge, GraphNode, GraphView } from '../api/types'
import { Badge, Button, Card, ErrorBox, Mono, PageHeader, Select, Spinner } from '../components/ui'
import { LABEL_COLORS } from '../components/GraphBits'
import { useAsync } from '../lib/useAsync'

const LABELS = ['', 'Memory', 'Entity', 'Agent', 'User', 'Project', 'Conversation', 'Message', 'Task', 'Handoff', 'Run', 'Step']

interface FNode extends GraphNode { x?: number; y?: number }
interface FLink { source: string | FNode; target: string | FNode; type: string; predicate?: string; status?: string }

function merge(base: GraphView | undefined, extra: GraphView): GraphView {
  const nodes = new Map<string, GraphNode>((base?.nodes ?? []).map((n) => [n.key, n]))
  extra.nodes.forEach((n) => nodes.set(n.key, n))
  const edges = new Map<string, GraphEdge>((base?.edges ?? []).map((e) => [`${e.from}|${e.type}|${e.to}`, e]))
  extra.edges.forEach((e) => edges.set(`${e.from}|${e.type}|${e.to}`, e))
  return { nodes: [...nodes.values()], edges: [...edges.values()], counts: base?.counts ?? extra.counts }
}

/** Interactive view of the tenant's FalkorDB graph: filter by label, click a node to expand its neighbourhood. */
export function MemoryGraphPage() {
  const [label, setLabel] = useState('')
  const { data, error, loading, reload } = useAsync(() => api.graphView(label || undefined, 400), [label])
  const [view, setView] = useState<GraphView>()
  const [selected, setSelected] = useState<GraphNode>()
  const [expandError, setExpandError] = useState<unknown>()
  const box = useRef<HTMLDivElement>(null)
  const fg = useRef<ForceGraphMethods | undefined>(undefined)
  const [width, setWidth] = useState(800)

  useEffect(() => setView(data), [data])
  useEffect(() => {
    const el = box.current
    if (!el) return
    const ro = new ResizeObserver(() => setWidth(el.clientWidth))
    ro.observe(el)
    setWidth(el.clientWidth)
    return () => ro.disconnect()
  }, [])

  const graphData = useMemo(() => {
    if (!view) return { nodes: [] as FNode[], links: [] as FLink[] }
    const keys = new Set(view.nodes.map((n) => n.key))
    return {
      nodes: view.nodes.map((n) => ({ ...n })) as FNode[],
      links: view.edges.filter((e) => keys.has(e.from) && keys.has(e.to))
        .map((e) => ({ source: e.from, target: e.to, type: e.type, predicate: e.predicate, status: e.status })) as FLink[],
    }
  }, [view])

  async function expand(n: GraphNode) {
    setSelected(n)
    try {
      setView(merge(view, await api.graphNeighbours(n.id)))
    } catch (e) {
      setExpandError(e)
    }
  }

  const counts = view?.counts ?? {}
  return (
    <div className="space-y-4">
      <PageHeader
        title="Memory graph"
        description="The signed-in tenant's graph in FalkorDB (one graph per tenant). Memories, entities, agents, tasks, handoffs and workflow steps; superseded memories are grey. Click a node to expand its neighbours."
        actions={<Button variant="secondary" busy={loading} onClick={() => void reload()}>Refresh</Button>}
      />
      <ErrorBox error={error ?? expandError} />
      <div className="flex flex-wrap items-center gap-2 text-xs" data-testid="graph-counts">
        <Select value={label} onChange={(e) => setLabel(e.target.value)} aria-label="Label filter">
          {LABELS.map((l) => <option key={l} value={l}>{l || 'All labels'}</option>)}
        </Select>
        {Object.entries(counts).filter(([k]) => !k.startsWith('rel:')).map(([k, v]) => (
          <span key={k} className="flex items-center gap-1">
            <span className="inline-block h-2.5 w-2.5 rounded-full" style={{ background: LABEL_COLORS[k] ?? '#94a3b8' }} />
            {k} <Mono>{v}</Mono>
          </span>
        ))}
        <span className="text-slate-400">·</span>
        <span>{Object.entries(counts).filter(([k]) => k.startsWith('rel:')).reduce((n, [, v]) => n + v, 0)} relationships</span>
      </div>
      <div className="grid gap-4 lg:grid-cols-[1fr_320px]">
        <div ref={box} className="h-[620px] overflow-hidden rounded-lg border border-slate-200 bg-white" data-testid="graph-canvas">
          {!view ? <Spinner /> : (
            <ForceGraph2D
              ref={fg}
              graphData={graphData}
              width={width}
              height={620}
              nodeId="key"
              nodeLabel={(n) => `${(n as FNode).label}: ${(n as FNode).name ?? (n as FNode).id}`}
              nodeColor={(n) => ((n as FNode).label === 'Memory' && (n as FNode).status && (n as FNode).status !== 'CURRENT')
                ? '#cbd5e1' : LABEL_COLORS[(n as FNode).label] ?? '#94a3b8'}
              nodeVal={(n) => ((n as FNode).label === 'Entity' ? 4 : (n as FNode).label === 'Memory' ? 2 : 3)}
              linkLabel={(l) => (l as FLink).predicate ? `${(l as FLink).type} ${(l as FLink).predicate}` : (l as FLink).type}
              linkColor={(l) => ((l as FLink).status && (l as FLink).status !== 'CURRENT' ? '#e2e8f0' : (l as FLink).type === 'RELATES' ? '#10b981' : '#cbd5e1')}
              linkWidth={(l) => ((l as FLink).type === 'RELATES' ? 2 : 1)}
              linkDirectionalArrowLength={3}
              linkDirectionalArrowRelPos={1}
              cooldownTicks={120}
              onEngineStop={() => fg.current?.zoomToFit(400, 40)}
              onNodeClick={(n) => void expand(n as FNode)}
            />
          )}
        </div>
        <div className="space-y-4">
        <Card title="Selected node">
          {!selected ? <p className="text-xs text-slate-500">Click a node. Its neighbours are loaded from FalkorDB and added to the view.</p> : (
            <div className="space-y-2 text-sm">
              <div className="flex items-center gap-2"><Badge>{selected.label}</Badge>{selected.status && <Badge tone={selected.status === 'CURRENT' ? 'green' : 'gray'}>{selected.status}</Badge>}</div>
              <p className="text-slate-800">{selected.name}</p>
              <p className="text-xs text-slate-500">id <Mono>{selected.id}</Mono></p>
              <ul className="max-h-80 space-y-1 overflow-y-auto text-xs" data-testid="graph-neighbours">
                {view?.edges.filter((e) => e.from === selected.key || e.to === selected.key).map((e, i) => {
                  const other = view.nodes.find((n) => n.key === (e.from === selected.key ? e.to : e.from))
                  return (
                    <li key={i}>
                      <Mono>{e.from === selected.key ? `-${e.type}→` : `←${e.type}-`}</Mono>{' '}
                      {e.predicate && <Mono>{e.predicate}</Mono>} <b>{other?.label}</b> {other?.name?.slice(0, 70)}
                    </li>
                  )
                })}
              </ul>
            </div>
          )}
        </Card>
        <KeyEntities onPick={(key) => void expand({ key: `Entity:${key}`, label: 'Entity', id: key, name: key })} />
        </div>
      </div>
    </div>
  )
}

/** Most central entities by PageRank (FalkorDB algo.pageRank over Entity-RELATES-Entity). */
function KeyEntities({ onPick }: { onPick: (key: string) => void }) {
  const { data, error } = useAsync(() => api.keyEntities(8), [])
  return (
    <Card title="Key entities (PageRank)">
      <ErrorBox error={error} />
      {!data ? <Spinner /> : data.length === 0 ? <p className="text-xs text-slate-500">No entity relationships yet.</p> : (
        <ol className="space-y-1 text-xs" data-testid="key-entities">
          {data.map((e) => (
            <li key={e.key} className="flex items-center justify-between gap-2">
              <button type="button" className="text-left text-brand-600 hover:underline" onClick={() => onPick(e.key)}>{e.name ?? e.key}</button>
              <span className="text-slate-500"><Mono>{e.score.toFixed(3)}</Mono> · {e.degree} links</span>
            </li>
          ))}
        </ol>
      )}
    </Card>
  )
}
