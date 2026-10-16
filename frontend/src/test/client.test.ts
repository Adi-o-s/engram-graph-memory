import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, qs, request, setUnauthorizedHandler, tokenStore } from '../api/client'

function mockFetch(status: number, body: unknown, headers: Record<string, string> = {}) {
  const fn = vi.fn().mockResolvedValue(
    new Response(body === undefined ? '' : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } }),
  )
  vi.stubGlobal('fetch', fn)
  return fn
}

afterEach(() => {
  vi.unstubAllGlobals()
  tokenStore.set(null)
  setUnauthorizedHandler(null)
})

describe('request', () => {
  it('sends the bearer token, JSON body and idempotency key', async () => {
    tokenStore.set('tok')
    const fetchFn = mockFetch(200, { ok: true })
    await request('/api/v1/x', { body: { a: 1 }, idempotencyKey: 'k1' })
    const [url, init] = fetchFn.mock.calls[0]
    expect(url).toBe('/api/v1/x')
    expect(init.method).toBe('POST')
    expect(init.headers.Authorization).toBe('Bearer tok')
    expect(init.headers['Idempotency-Key']).toBe('k1')
    expect(init.body).toBe('{"a":1}')
  })

  it('turns a problem detail into an ApiError with code, details and correlation id', async () => {
    mockFetch(409, { detail: 'stale', code: 'stale_version', currentVersion: 4, correlationId: 'c-1' })
    const error = await request<never>('/api/v1/tasks/1', { method: 'PATCH', body: {} }).catch((e: unknown) => e as ApiError)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(409)
    expect(error.code).toBe('stale_version')
    expect(error.message).toBe('stale')
    expect(error.correlationId).toBe('c-1')
    expect(error.details.currentVersion).toBe(4)
  })

  it('calls the unauthorized handler when a stored token is rejected', async () => {
    tokenStore.set('expired')
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    mockFetch(401, { detail: 'expired', code: 'unauthorized' })
    await request('/api/v1/overview').catch(() => undefined)
    expect(handler).toHaveBeenCalledOnce()
  })

  it('reports a network failure clearly', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const error = await request<never>('/api/v1/overview').catch((e: unknown) => e as ApiError)
    expect(error.code).toBe('network_error')
  })
})

describe('qs', () => {
  it('skips empty values and repeats arrays', () => {
    expect(qs({ q: 'db', type: ['SEMANTIC', 'EPISODIC'], projectId: undefined, empty: '' })).toBe('?q=db&type=SEMANTIC&type=EPISODIC')
    expect(qs({})).toBe('')
  })
})
