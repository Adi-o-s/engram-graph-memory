import { qs, request, newIdempotencyKey } from './client'
import type {
  Agent, AuditEvent, ContextBundle, Conversation, DemoAccounts, Handoff, History, IngestResult, KeyEntity, LabResult, LabScenario,
  LoginResponse, Me, Memory, MemoryType, MemoryWriteResult, Message, Overview, Page, Project, RegisteredAgent, RunStatus,
  SearchResult, StepResult, Task, TaskEvent, TaskStatus, TeachResult, Explanation, GraphAlert, GraphView,
} from './types'

const V1 = '/api/v1'

export const api = {
  // auth
  login: (email: string, password: string, tenantSlug?: string) =>
    request<LoginResponse>(`${V1}/auth/login`, { body: { email, password, tenantSlug }, token: null }),
  me: (token?: string | null) => request<Me>(`${V1}/auth/me`, { token }),
  switchTenant: (tenantSlug: string) => request<LoginResponse>(`${V1}/auth/switch-tenant`, { body: { tenantSlug } }),
  demoAccounts: () => request<DemoAccounts>(`${V1}/auth/demo-accounts`, { token: null }),

  overview: () => request<Overview>(`${V1}/overview`),
  audit: (limit = 50) => request<AuditEvent[]>(`${V1}/audit${qs({ limit })}`),
  projects: (token?: string | null) => request<Project[]>(`${V1}/projects`, { token }),

  // memories
  searchMemories: (p: {
    q?: string; type?: MemoryType[]; includeHistory?: boolean; projectId?: string; includeShared?: boolean
    conversationId?: string; taskId?: string; category?: string; sort?: string; limit?: number; offset?: number
  }, token?: string | null) => request<SearchResult>(`${V1}/memories${qs(p)}`, { token }),
  memory: (id: string, token?: string | null) => request<Memory>(`${V1}/memories/${id}`, { token }),
  history: (id: string) => request<History>(`${V1}/memories/${id}/history`),
  createMemory: (body: Record<string, unknown>) =>
    request<MemoryWriteResult>(`${V1}/memories`, { body, idempotencyKey: newIdempotencyKey() }),
  retractMemory: (id: string, reason: string) => request<Memory>(`${V1}/memories/${id}/retract`, { body: { reason } }),
  reviseMemory: (id: string, content: string, value?: string) =>
    request<MemoryWriteResult>(`${V1}/memories/${id}/revise`, { body: { content, value } }),

  // conversations
  conversations: (projectId?: string) => request<Page<Conversation>>(`${V1}/conversations${qs({ projectId, size: 100 })}`),
  conversation: (id: string) => request<Conversation>(`${V1}/conversations/${id}`),
  createConversation: (title: string, projectId?: string) =>
    request<Conversation>(`${V1}/conversations`, { body: { title, projectId }, idempotencyKey: newIdempotencyKey() }),
  messages: (id: string, page = 0) => request<Page<Message>>(`${V1}/conversations/${id}/messages${qs({ page, size: 200 })}`),
  sendMessage: (id: string, content: string, extract: boolean) =>
    request<IngestResult>(`${V1}/conversations/${id}/messages`, { body: { content, extract }, idempotencyKey: newIdempotencyKey() }),
  messageMemories: (conversationId: string, messageId: string) =>
    request<Memory[]>(`${V1}/conversations/${conversationId}/messages/${messageId}/memories`),
  summarize: (id: string) => request<Conversation>(`${V1}/conversations/${id}/summarize`, { method: 'POST' }),

  // context
  context: (body: Record<string, unknown>, agentKey?: string) => request<ContextBundle>(`${V1}/context`, { body, agentKey }),

  // memory graph (FalkorDB, one graph per tenant)
  explain: (memoryId: string, from?: string) => request<Explanation>(`${V1}/graph/explain${qs({ memoryId, from })}`),
  graphAlerts: (limit = 50) => request<GraphAlert[]>(`${V1}/graph/alerts${qs({ limit })}`),
  graphView: (label?: string, limit = 400, token?: string) => request<GraphView>(`${V1}/graph/view${qs({ label, limit })}`, { token }),
  graphNeighbours: (id: string) => request<GraphView>(`${V1}/graph/neighbours${qs({ id })}`),
  keyEntities: (limit = 10) => request<KeyEntity[]>(`${V1}/graph/key-entities${qs({ limit })}`),
  rebuildGraph: () => request<Record<string, unknown>>(`${V1}/admin/graph/rebuild`, { method: 'POST' }),

  // agents
  agents: () => request<Agent[]>(`${V1}/agents`),
  registerAgent: (body: Record<string, unknown>) => request<RegisteredAgent>(`${V1}/agents`, { body }),
  rotateKey: (id: string) => request<RegisteredAgent>(`${V1}/agents/${id}/rotate-key`, { method: 'POST' }),
  inbox: (id: string) => request<Handoff[]>(`${V1}/agents/${id}/inbox`),

  // tasks & handoffs
  tasks: (p: { projectId?: string; status?: TaskStatus } = {}, token?: string | null) =>
    request<Page<Task>>(`${V1}/tasks${qs({ ...p, size: 100 })}`, { token }),
  task: (id: string) => request<Task>(`${V1}/tasks/${id}`),
  taskEvents: (id: string) => request<TaskEvent[]>(`${V1}/tasks/${id}/events`),
  updateTask: (id: string, body: Record<string, unknown>) => request<Task>(`${V1}/tasks/${id}`, { method: 'PATCH', body }),
  handoffs: (p: { taskId?: string; limit?: number } = {}) => request<Handoff[]>(`${V1}/handoffs${qs(p)}`),
  handoffAction: (id: string, action: 'ack' | 'complete' | 'fail' | 'retry' | 'reject', body?: unknown) =>
    request<Handoff>(`${V1}/handoffs/${id}/${action}`, { method: 'POST', body }),

  // demo
  startDemo: () => request<TeachResult>(`${V1}/demo/runs`, { method: 'POST' }),
  demoRuns: () => request<Project[]>(`${V1}/demo/runs`),
  demoStatus: (projectId: string) => request<RunStatus>(`${V1}/demo/runs/${projectId}`),
  demoStep: (projectId: string, step: 'plan' | 'develop' | 'test', failFirst = true) =>
    request<StepResult>(`${V1}/demo/runs/${projectId}/${step}${step === 'test' ? qs({ failFirst }) : ''}`, { method: 'POST' }),

  // lab
  runLab: (scenario: LabScenario, threads: number, operations: number) =>
    request<LabResult>(`${V1}/lab/runs`, { body: { scenario, threads, operations } }),
  labRuns: () => request<LabResult[]>(`${V1}/lab/runs`),
}
