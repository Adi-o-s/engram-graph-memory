// Types mirror the backend DTOs. Null fields are omitted by the API, so they are optional here.

export type MemoryType = 'EPISODIC' | 'SEMANTIC' | 'PROCEDURAL'
export type MemoryStatus = 'CURRENT' | 'SUPERSEDED' | 'RETRACTED'
export type MemoryScope = 'TENANT' | 'PROJECT' | 'USER'
export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'BLOCKED' | 'IN_REVIEW' | 'DONE' | 'FAILED'
export type HandoffStatus = 'PENDING' | 'ACKNOWLEDGED' | 'COMPLETED' | 'FAILED' | 'REJECTED' | 'DEAD_LETTER'
export type Role = 'ADMIN' | 'MEMBER' | 'AGENT'

export interface TenantSummary { id: string; slug: string; name: string; role: Role }
export interface UserSummary { id: string; email: string; displayName: string }
export interface LoginResponse { token: string; expiresAt: string; user: UserSummary; tenant: TenantSummary; memberships: TenantSummary[] }
export interface Me { actorType: string; actorId: string; name: string; tenant: TenantSummary; memberships?: TenantSummary[] }
export interface DemoAccount { email: string; displayName: string; tenants: string[]; note: string }
export interface DemoAccounts { seeded: boolean; passwordHint?: string; accounts: DemoAccount[] }

export interface Page<T> { items: T[]; page: number; size: number; totalItems: number; totalPages: number }

export interface Project { id: string; slug: string; name: string; description?: string; createdAt: string }

export interface SourceView {
  type: string; messageId?: string; ref?: string; extractor?: string; createdByUserId?: string; createdByAgentId?: string
}

export interface Memory {
  id: string; type: MemoryType; category?: string; content: string
  subject?: string; predicate?: string; value?: string; factKey?: string
  scope: MemoryScope; scopeKey: string; userId?: string; projectId?: string; conversationId?: string; taskId?: string; agentId?: string
  tags: string[]; importance: number; confidence: number; status: MemoryStatus; version: number; lockVersion?: number
  supersedesId?: string; supersededById?: string; source: SourceView; accessCount: number; lastAccessedAt?: string
  expiresAt?: string; metadata: Record<string, unknown>; createdAt: string; updatedAt: string; score?: number
}

export interface SearchResult { items: Memory[]; total: number; limit: number; offset: number; tsQuery?: string }
export type WriteOutcome = 'CREATED' | 'SUPERSEDED_PREVIOUS' | 'DUPLICATE'
export interface MemoryWriteResult { memory: Memory; outcome: WriteOutcome; supersededMemoryId?: string }
export interface History { memoryId: string; factKey?: string; versions: Memory[] }

export interface Conversation {
  id: string; projectId?: string; ownerUserId?: string; title: string; sessionId?: string; summary?: string
  messageCount: number; createdAt: string; updatedAt: string; lastMessageAt?: string
}
export interface Message {
  id: string; conversationId: string; seq: number; role: 'USER' | 'ASSISTANT' | 'AGENT' | 'SYSTEM'
  authorUserId?: string; authorAgentId?: string; content: string; metadata: Record<string, unknown>; createdAt: string
}
export interface ExtractedWrite { memory: Memory; outcome: string; supersededMemoryId?: string; rule?: string }
export interface IngestResult { message: Message; extracted: ExtractedWrite[]; replayed: boolean }

export interface Agent {
  id: string; name: string; role: 'PLANNER' | 'DEVELOPER' | 'TESTER' | 'CUSTOM'; description?: string; capabilities: string[]
  status: string; apiKeyPrefix?: string; handoffsReceived: number; handoffsSent: number; lastSeenAt?: string; createdAt: string
}
export interface RegisteredAgent { agent: Agent; apiKey?: string }

export interface Task {
  id: string; projectId?: string; title: string; description?: string; status: TaskStatus; priority: number
  assigneeAgentId?: string; createdByUserId?: string; createdByAgentId?: string; state: Record<string, unknown>
  version: number; createdAt: string; updatedAt: string
}
export interface TaskEvent {
  id: string; eventType: string; actorType: string; actorId?: string; fromStatus?: TaskStatus; toStatus?: TaskStatus
  taskVersion?: number; payload: Record<string, unknown>; createdAt: string
}
export interface Handoff {
  id: string; taskId: string; fromAgentId: string; toAgentId: string; summary: string; memoryRefs: string[]
  decisions: string[]; artifacts: Record<string, unknown>; nextAction: string; status: HandoffStatus; taskVersion: number
  attempts: number; maxAttempts: number; failureReason?: string; result?: Record<string, unknown>; leaseExpiresAt?: string
  claimedByAgentId?: string; version: number; createdAt: string; acknowledgedAt?: string; completedAt?: string; updatedAt: string
}

export interface AuditEvent {
  id: string; actorType: string; actorId?: string; actorName?: string; action: string; entityType: string; entityId?: string
  details: Record<string, unknown>; correlationId?: string; createdAt: string
}
export interface RouteStat { route: string; count: number; p50Ms: number; p95Ms: number }
export interface Overview {
  counts: { projects: number; conversations: number; messages: number; agents: number; agentsActiveLast15m: number }
  memories: Record<MemoryType, Record<MemoryStatus, number>>
  tasks: Partial<Record<TaskStatus, number>>
  handoffs: Partial<Record<HandoffStatus, number>>
  agents: Agent[]
  recentHandoffs: Handoff[]
  recentActivity: AuditEvent[]
  health: { database: string; dbPingMs: number; rowLevelSecurityEnforced: boolean }
  apiLatency: { sampleCount: number; p50Ms: number; p95Ms: number; p99Ms: number; maxMs: number; serverErrors: number; clientErrors: number; conflicts: number; topRoutes: RouteStat[] }
}

export interface ContextBundle {
  query?: string; task?: Task; latestHandoff?: Handoff; facts: Memory[]; procedures: Memory[]; episodes: Memory[]
  messages: Message[]; rendered: string
  stats: { chars: number; maxChars: number; memoriesIncluded: number; memoriesOmitted: number; messagesIncluded: number; messagesOmitted: number; truncated: boolean }
}

export interface StepResult {
  step: string; agent: string; outcome: string; taskId?: string; handoffId?: string; log: string[]; details: Record<string, unknown>
}
export interface TeachResult { project: Project; conversation: Conversation; messages: { text: string; extracted: ExtractedWrite[] }[] }
export interface RunStatus {
  project: Project; conversation?: Conversation; task?: Task; handoffs: Handoff[]; events: TaskEvent[]; memories: Memory[]; nextStep: string
}

export type LabScenario = 'TASK_COUNTER' | 'MESSAGE_APPEND' | 'MEMORY_WRITES'
export interface LabResult {
  runId: string; scenario: LabScenario; threads: number; operations: number; succeeded: number; failed: number
  conflictsDetected: number; retries: number; durationMs: number; throughputPerSec: number
  latency: { p50Ms: number; p95Ms: number; p99Ms: number; maxMs: number; meanMs: number }
  invariants: { name: string; expected: string; actual: string; passed: boolean }[]
  allInvariantsPassed: boolean; sampleErrors: string[]; target: Record<string, unknown>; startedAt: string
}
