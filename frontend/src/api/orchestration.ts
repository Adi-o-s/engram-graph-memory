// Memora 2.0 orchestration API: agent definitions, tools, workflows, plans and runs.
import { newIdempotencyKey, qs, request } from './client'
import type { Page } from './types'

const V1 = '/api/v1'

export type Json = Record<string, unknown>

export interface AgentDefinition {
  id: string; key: string; name: string; description?: string; role: string; instructions: string
  capabilities: string[]; tools: string[]; inputSchema: Json; outputSchema: Json; memoryPolicy: Json; contextPolicy: Json
  executor: string; behavior: Json; timeoutSeconds: number; maxAttempts: number; backoffSeconds: number
  status: 'ACTIVE' | 'INACTIVE'; version: number; lockVersion: number; sourceTemplate?: string; agentId: string
  agentName?: string; agentHasApiKey: boolean; createdAt: string; updatedAt: string
}

export interface AgentDefinitionInput {
  key?: string; name: string; description?: string; role: string; instructions?: string; capabilities: string[]; tools: string[]
  inputSchema?: Json; outputSchema?: Json; memoryPolicy?: Json; contextPolicy?: Json; executor: string; behavior?: Json
  timeoutSeconds?: number; maxAttempts?: number; backoffSeconds?: number; expectedLockVersion?: number
}

export interface Problem { field?: string; path?: string; message: string }
export interface AgentValidation { valid: boolean; errors: Problem[]; warnings: string[] }
export interface AgentTemplate {
  key: string; name: string; role: string; description: string; category: string; capabilities: string[]; tools: string[]
  executor: string; installed: boolean; definition: AgentDefinitionInput
}
export interface ToolDescriptor {
  id: string; title: string; description: string; category: string; requiredCapability?: string; sideEffects: boolean
  requiresApproval: boolean; timeoutMs: number; inputSchema: Json; outputSchema: Json; constraints: string[]
  failureBehavior: string; available: boolean; unavailableReason?: string
}
export interface ExecutorDescriptor {
  id: string; title: string; description: string; deterministic: boolean; external: boolean; available: boolean; unavailableReason?: string
}

export interface StepSpec {
  key: string; name?: string; description?: string; agent: string; dependsOn?: string[]; inputs?: Json; when?: Json
  retry?: { maxAttempts?: number; backoffSeconds?: number }; timeoutSeconds?: number
  approval?: { required?: boolean; reason?: string }; loop?: { until?: Json; maxIterations?: number; remediationStep?: string }
  remediation?: boolean; mandatory?: boolean
}
export interface InputSpec { name: string; type?: string; required?: boolean; description?: string; defaultValue?: unknown }
export interface WorkflowSpec {
  inputs?: InputSpec[]; steps: StepSpec[]
  completion?: { requiredSteps?: string[]; criteria?: { name: string; description?: string; condition: Json }[] }
  outputs?: Json
}
export interface WorkflowValidation {
  valid: boolean; errors: Problem[]; warnings: string[]
  analysis?: { layers: string[][]; parallelGroups: string[][]; roots: string[]; leaves: string[]; order: string[]; remediationSteps: string[] }
}
export interface Workflow {
  id: string; key: string; name: string; description?: string; projectId?: string; version: number; status: string
  lockVersion: number; spec: WorkflowSpec; stepCount: number; agents: string[]; validation: WorkflowValidation
  createdAt: string; updatedAt: string
}
export interface WorkflowVersion { version: number; spec: WorkflowSpec; changeNote?: string; createdAt: string }

export type RunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'WAITING_APPROVAL' | 'BLOCKED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type StepStatus = 'PENDING' | 'READY' | 'RUNNING' | 'WAITING_APPROVAL' | 'BLOCKED' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'CANCELLED'

export interface RunSummary {
  id: string; name: string; goal?: string; status: RunStatus; workflowId: string; workflowVersion: number; projectId: string
  stepCounts: Record<string, number>; createdAt: string; updatedAt: string; finishedAt?: string
}
export interface StepRunView {
  id: string; key: string; iteration: number; name: string; agentKey: string; agentName?: string; agentVersion?: number
  executor?: string; status: StepStatus; dependsOn: string[]; attempts: number; maxAttempts: number; input?: Json; output?: Json
  error?: string; errorKind?: string; readyReason?: Json; leaseOwner?: string; leaseExpiresAt?: string; nextAttemptAt?: string
  taskId?: string; handoffId?: string; createdAt: string; readyAt?: string; startedAt?: string; finishedAt?: string
  durationMs?: number; remediation: boolean; latest: boolean
  approval?: { id: string; status: string; reason?: string; decisionNote?: string; decidedAt?: string }; canRetry: boolean
}
export interface CompletionReport {
  passed: boolean; summary?: string; evaluatedAt?: string
  criteria?: { name: string; description?: string; passed: boolean; trace: string[] }[]
  requiredSteps?: { step: string; status: string; passed: boolean }[]
  outstanding?: { step: string; name: string; status: string; reason?: string }[]
}
export interface RunView {
  id: string; name: string; goal?: string; status: RunStatus; workflowId: string; workflowKey?: string; workflowVersion: number
  projectId: string; inputs: Json; options: Json; spec: WorkflowSpec; completion?: CompletionReport; outputs?: Json
  taskId?: string; startedByUserId?: string; steps: StepRunView[]; stepCounts: Record<string, number>; layers: string[][]
  agents: { key: string; name: string; role: string; version: number; executor: string; agentId: string; tools: string[] }[]
  createdAt: string; updatedAt: string; finishedAt?: string
}
export interface RunEvent { id: string; stepRunId?: string; type: string; message?: string; payload: Json; createdAt: string }
export interface ToolCall {
  id: string; stepRunId?: string; toolId: string; status: 'SUCCEEDED' | 'FAILED' | 'DENIED'; sideEffects: boolean
  input: Json; output: Json; error?: string; durationMs: number; createdAt: string
}
export interface Artifact {
  id: string; runId: string; stepRunId?: string; stepKey?: string; projectId: string; name: string; kind: string
  checksum: string; sizeBytes: number; version: number; content?: string; createdAt: string
}
export interface StepDetail { step: StepRunView; events: RunEvent[]; toolCalls: ToolCall[]; artifacts: Artifact[] }
export interface ProjectWorkspace {
  projectId: string; name: string; description?: string; runs: RunSummary[]; decisions: Json[]; requirements: Json[]; lessons: Json[]
  artifacts: Artifact[]; outstanding: Json[]; history: Json[]
}
export interface Proposal {
  planner: string; goal: string; subject?: string
  domain: { key: string; confidence: string; evidence: string[]; template: string; alternatives: Record<string, string[]> }
  requirements: { id: string; text: string; feature: string; category: string; priority: string; acceptance: string[] }[]
  deliverables: string[]
  roles: { agent: string; name: string; role?: string; installed: boolean; template: boolean; executor?: string; tools: string[] }[]
  steps: { key: string; name: string; agent: string; dependsOn: string[]; inputs: string[]; expectedOutputs?: string[]; purpose?: string;
    condition?: Json; loop?: Json; approval?: Json; remediation?: boolean }[]
  parallelGroups: string[][]; acceptanceCriteria: { requirement: string; text: string; criteria: string[] }[]
  unclear: string[]; missingInformation: string[]; assumptions: string[]; approvals: string[]; limitations: string[]; warnings: string[]
  suggestedName: string; suggestedKey: string; spec: WorkflowSpec
}
export interface StartRunInput { projectId?: string; goal?: string; inputs?: Json; version?: number; options?: Json }

export const orchestration = {
  agentDefinitions: () => request<AgentDefinition[]>(`${V1}/agent-definitions`),
  agentDefinition: (id: string) => request<AgentDefinition>(`${V1}/agent-definitions/${id}`),
  createAgent: (body: AgentDefinitionInput) => request<AgentDefinition>(`${V1}/agent-definitions`, { body }),
  updateAgent: (id: string, body: AgentDefinitionInput) => request<AgentDefinition>(`${V1}/agent-definitions/${id}`, { method: 'PUT', body }),
  validateAgent: (body: AgentDefinitionInput) => request<AgentValidation>(`${V1}/agent-definitions/validate`, { body }),
  duplicateAgent: (id: string) => request<AgentDefinition>(`${V1}/agent-definitions/${id}/duplicate`, { method: 'POST', body: {} }),
  setAgentActive: (id: string, active: boolean) =>
    request<AgentDefinition>(`${V1}/agent-definitions/${id}/${active ? 'activate' : 'deactivate'}`, { method: 'POST' }),
  agentVersions: (id: string) => request<{ version: number; snapshot: Json; createdAt: string }[]>(`${V1}/agent-definitions/${id}/versions`),
  templates: () => request<AgentTemplate[]>(`${V1}/agent-templates`),
  installTemplate: (key: string) => request<AgentDefinition>(`${V1}/agent-templates/${key}/install`, { method: 'POST' }),
  tools: () => request<ToolDescriptor[]>(`${V1}/tools`),
  executors: () => request<ExecutorDescriptor[]>(`${V1}/executors`),

  workflows: () => request<Workflow[]>(`${V1}/workflows`),
  workflow: (id: string) => request<Workflow>(`${V1}/workflows/${id}`),
  createWorkflow: (body: { key?: string; name: string; description?: string; projectId?: string; spec: WorkflowSpec; changeNote?: string }) =>
    request<Workflow>(`${V1}/workflows`, { body }),
  updateWorkflow: (id: string, body: { name: string; description?: string; projectId?: string; spec: WorkflowSpec; changeNote?: string; expectedLockVersion: number }) =>
    request<Workflow>(`${V1}/workflows/${id}`, { method: 'PUT', body }),
  validateWorkflow: (spec: WorkflowSpec) => request<WorkflowValidation>(`${V1}/workflows/validate`, { body: { spec } }),
  duplicateWorkflow: (id: string) => request<Workflow>(`${V1}/workflows/${id}/duplicate`, { method: 'POST', body: {} }),
  archiveWorkflow: (id: string, archived: boolean) => request<Workflow>(`${V1}/workflows/${id}/${archived ? 'archive' : 'restore'}`, { method: 'POST' }),
  workflowVersions: (id: string) => request<WorkflowVersion[]>(`${V1}/workflows/${id}/versions`),
  startRun: (workflowId: string, body: StartRunInput) =>
    request<{ runId: string }>(`${V1}/workflows/${workflowId}/runs`, { body, idempotencyKey: newIdempotencyKey() }),

  plan: (goal: string) => request<Proposal>(`${V1}/workflow-plans`, { body: { goal } }),
  acceptPlan: (body: { key?: string; name: string; description?: string; projectId?: string; spec: WorkflowSpec; installMissingAgents?: boolean }) =>
    request<{ workflow: Workflow; installedAgents: string[] }>(`${V1}/workflow-plans/accept`, { body }),

  runs: (p: { projectId?: string; workflowId?: string; status?: RunStatus; page?: number } = {}) =>
    request<Page<RunSummary>>(`${V1}/workflow-runs${qs({ ...p, size: 50 })}`),
  run: (id: string) => request<RunView>(`${V1}/workflow-runs/${id}`),
  runEvents: (id: string) => request<RunEvent[]>(`${V1}/workflow-runs/${id}/events`),
  runArtifacts: (id: string, allVersions = false) => request<Artifact[]>(`${V1}/workflow-runs/${id}/artifacts${qs({ allVersions })}`),
  artifact: (id: string) => request<Artifact>(`${V1}/workflow-artifacts/${id}`),
  step: (runId: string, stepId: string) => request<StepDetail>(`${V1}/workflow-runs/${runId}/steps/${stepId}`),
  runAction: (id: string, action: 'pause' | 'resume' | 'cancel') => request<RunView>(`${V1}/workflow-runs/${id}/${action}`, { method: 'POST' }),
  stepAction: (runId: string, stepId: string, action: 'retry' | 'approve' | 'reject', note?: string) =>
    request<RunView>(`${V1}/workflow-runs/${runId}/steps/${stepId}/${action}`, { method: 'POST', body: action === 'retry' ? undefined : { note } }),
  workspace: (projectId: string) => request<ProjectWorkspace>(`${V1}/projects/${projectId}/workspace`),
}
