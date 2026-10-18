// Thin fetch wrapper: JSON in/out, bearer token, problem-detail errors.

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly correlationId?: string
  readonly details: Record<string, unknown>

  constructor(status: number, code: string, message: string, correlationId?: string, details: Record<string, unknown> = {}) {
    super(message)
    this.status = status
    this.code = code
    this.correlationId = correlationId
    this.details = details
  }
}

const TOKEN_KEY = 'memora.token'

export const tokenStore = {
  get(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY)
    } catch {
      return null
    }
  },
  set(token: string | null) {
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token)
      else localStorage.removeItem(TOKEN_KEY)
    } catch {
      /* storage unavailable: token lives only in memory for this tab */
    }
  },
}

let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler
}

export interface RequestOptions {
  method?: string
  body?: unknown
  token?: string | null
  /** Call as an agent: sends X-Agent-Key instead of the user's bearer token. */
  agentKey?: string
  idempotencyKey?: string
  signal?: AbortSignal
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  const token = options.agentKey ? null : options.token === undefined ? tokenStore.get() : options.token
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.agentKey) headers['X-Agent-Key'] = options.agentKey
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey

  let response: Response
  try {
    response = await fetch(path, {
      method: options.method ?? (options.body !== undefined ? 'POST' : 'GET'),
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
    })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiError(0, 'network_error', 'Cannot reach the Memora backend. Is it running on port 8080?')
  }

  const text = await response.text()
  let data: unknown = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = text
    }
  }
  if (!response.ok) {
    const problem = (data && typeof data === 'object' ? data : {}) as Record<string, unknown>
    const error = new ApiError(
      response.status,
      String(problem.code ?? 'http_' + response.status),
      String(problem.detail || problem.message || (typeof data === 'string' && data.length < 200 ? data : '') || response.statusText || `Request failed with HTTP ${response.status}`),
      (problem.correlationId as string | undefined) ?? response.headers.get('X-Correlation-Id') ?? undefined,
      problem,
    )
    if (response.status === 401 && token && options.token === undefined) onUnauthorized?.()
    throw error
  }
  return data as T
}

export function qs(params: Record<string, string | number | boolean | undefined | null | (string | number)[]>): string {
  const sp = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    if (Array.isArray(value)) value.forEach((v) => sp.append(key, String(v)))
    else sp.append(key, String(value))
  }
  const s = sp.toString()
  return s ? `?${s}` : ''
}

export function newIdempotencyKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : String(Date.now() + Math.random())
}
