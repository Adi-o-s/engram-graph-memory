import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { layers, WorkflowGraph } from '../components/WorkflowGraph'
import { RunTimeline } from '../components/RunTimeline'
import type { StepRunView } from '../api/orchestration'

describe('workflow graph layout', () => {
  it('layers steps by longest dependency path and keeps remediation steps out of the main graph', () => {
    const nodes = [
      { key: 'req', label: 'Requirements', deps: [] },
      { key: 'arch', label: 'Architecture', deps: ['req'] },
      { key: 'design', label: 'Design', deps: ['req'] },
      { key: 'front', label: 'Frontend', deps: ['arch', 'design'] },
      { key: 'final', label: 'Final', deps: ['req', 'front'] },
      { key: 'fix', label: 'Fix', deps: [], remediationOf: 'front' },
    ]
    expect(layers(nodes)).toEqual([['req'], ['arch', 'design'], ['front'], ['final']])
  })

  it('tolerates unknown dependencies and cycles without hanging', () => {
    expect(layers([{ key: 'a', label: 'A', deps: ['b', 'ghost'] }, { key: 'b', label: 'B', deps: ['a'] }]).flat().sort()).toEqual(['a', 'b'])
  })

  it('renders every node as an accessible button with its status', () => {
    render(<WorkflowGraph nodes={[{ key: 'a', label: 'Build', deps: [], status: 'RUNNING' }]} />)
    expect(screen.getByRole('button', { name: 'Build, running' })).toBeInTheDocument()
  })
})

describe('run timeline', () => {
  it('draws one bar per started step with its duration', () => {
    const base = { iteration: 1, agentKey: 'x', dependsOn: [], attempts: 1, maxAttempts: 3, createdAt: '', remediation: false, latest: true, canRetry: false }
    const steps = [
      { ...base, id: '1', key: 'a', name: 'Alpha', status: 'SUCCEEDED', startedAt: '2026-01-01T00:00:00Z', finishedAt: '2026-01-01T00:00:02Z' },
      { ...base, id: '2', key: 'b', name: 'Beta', status: 'SUCCEEDED', startedAt: '2026-01-01T00:00:01Z', finishedAt: '2026-01-01T00:00:03Z' },
      { ...base, id: '3', key: 'c', name: 'Gamma', status: 'PENDING' },
    ] as StepRunView[]
    render(<RunTimeline steps={steps} />)
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getAllByText('2.0 s')).toHaveLength(2)
  })
})
