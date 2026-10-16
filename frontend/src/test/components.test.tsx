import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryCard } from '../components/MemoryCard'
import { ErrorBox } from '../components/ui'
import { ApiError } from '../api/client'
import { AuthProvider } from '../auth/AuthContext'
import { LoginPage } from '../pages/LoginPage'
import type { Memory } from '../api/types'

const memory: Memory = {
  id: 'aaaaaaaa-0000-0000-0000-000000000000', type: 'SEMANTIC', category: 'fact', content: 'The project uses PostgreSQL as its database.',
  subject: 'project', predicate: 'uses.database', value: 'postgresql', factKey: 'project|uses.database',
  scope: 'PROJECT', scopeKey: 'project:1', tags: [], importance: 4, confidence: 0.9, status: 'CURRENT', version: 2,
  source: { type: 'MESSAGE', extractor: 'rule-based-v1' }, accessCount: 0, metadata: {},
  createdAt: '2026-10-16T12:00:00Z', updatedAt: '2026-10-16T12:00:00Z',
}

afterEach(() => vi.unstubAllGlobals())

describe('MemoryCard', () => {
  it('shows type, status, version, fact and provenance', () => {
    render(<MemoryCard memory={memory} />)
    expect(screen.getByText('Semantic')).toBeInTheDocument()
    expect(screen.getByText('Current')).toBeInTheDocument()
    expect(screen.getByText('v2')).toBeInTheDocument()
    expect(screen.getByText('project|uses.database')).toBeInTheDocument()
    expect(screen.getByText(/rule-based-v1/)).toBeInTheDocument()
  })

  it('strikes through superseded memories and calls onSelect', async () => {
    const onSelect = vi.fn()
    render(<MemoryCard memory={{ ...memory, status: 'SUPERSEDED' }} onSelect={onSelect} />)
    expect(screen.getByText(memory.content).className).toContain('line-through')
    await userEvent.click(screen.getByRole('button'))
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: memory.id }))
  })
})

describe('ErrorBox', () => {
  it('shows HTTP status, code and correlation id for API errors', () => {
    render(<ErrorBox error={new ApiError(404, 'not_found', 'Memory x was not found', 'corr-9')} />)
    expect(screen.getByRole('alert')).toHaveTextContent('Memory x was not found')
    expect(screen.getByRole('alert')).toHaveTextContent('HTTP 404 · not_found · correlation corr-9')
  })
})

describe('LoginPage', () => {
  it('lists demo accounts from the API and fills the form when one is picked', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify({
      seeded: true, passwordHint: 'memora-demo',
      accounts: [{ email: 'bob@globex.test', displayName: 'Bob Admin', tenants: ['globex (ADMIN)'], note: 'Globex admin' }],
    }), { status: 200 })))
    render(<AuthProvider><LoginPage /></AuthProvider>)
    await userEvent.click(await screen.findByText('Bob Admin'))
    expect(screen.getByLabelText('Email')).toHaveValue('bob@globex.test')
    expect(screen.getByLabelText('Password')).toHaveValue('memora-demo')
  })
})
