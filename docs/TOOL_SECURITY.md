# Tools and security

Executors never receive a repository, service or database connection. Everything an agent does goes through
`ToolGateway`, which checks, in order:

1. the tool exists and is available in this deployment;
2. the tool is in the agent's `tools` list, **as pinned when the run started** (editing an agent mid-run changes nothing);
3. the agent declares the tool's required capability;
4. a tool that requires approval runs only in a step that passed an approval gate;
5. the arguments match the tool's input schema.

Every call, allowed or not, is written to `tool_invocations` (status `SUCCEEDED`, `FAILED` or `DENIED`, duration,
summarised inputs and outputs). Calls with side effects are also written to the audit trail. Summaries replace
anything that looks like a credential with `[redacted: looks like a secret]`. A denied call fails the step
permanently; it is never retried.

Tools also receive a fixed context (tenant, project, run, step, agent, the user who started the run). Arguments
cannot point a tool at another tenant, run or project.

## Built-in tools

| Tool | Capability | Side effects | Notes |
|---|---|---|---|
| `memory.search`, `context.build` | `memory.read` | no | restricted to the policy's read scopes, enforced in SQL |
| `memory.write` | `memory.write` | yes | policy's write scopes only; never `USER`; secrets refused; provenance recorded |
| `artifact.read`, `artifact.list` | `artifact.read` | no | current run only |
| `artifact.write` | `artifact.write` | yes | current run only; versioned, never overwritten; SHA-256; 512 KB; secrets refused |
| `text.extract_requirements`, `catalog.match`, `text.keywords`, `data.parse_csv`, `data.aggregate`, `data.select`, `data.join`, `data.coalesce` | `analysis` | no | pure functions |
| `document.compose` | `document` | no | formatting only, no free-form generation |
| `checks.evaluate`, `schema.validate` | `verify` | no | verify actual artifacts and values |
| `task.create` | `task.write` | yes | run's project only; idempotent per step and title |
| `workspace.list`, `workspace.read_file` | `workspace.read` | no | **off** unless `MEMORA_WORKSPACE_ROOT` is set; read-only; canonical-path check rejects `..`, absolute paths, drive letters and symlinks that leave the directory |
| `command.run` | `command.execute` | yes | **off** unless commands are allow-listed; agents name a command id and cannot pass arguments; started without a shell; output to a file; timeout; requires an approval gate |

There is no unrestricted shell, no arbitrary file access and no network tool.

## Tenant isolation and roles

* The 10 new tables use the same `FORCE ROW LEVEL SECURITY` policy as the original ones; every repository query
  also filters by the caller's tenant. Another tenant's agent, workflow, run, step, artifact, handoff or project
  workspace returns **404**, and a workflow cannot reference another tenant's agent key.
* The scheduler claims work in system scope and executes each step in that step's tenant scope; tools act as the
  step's agent principal.
* `ADMIN`: create and edit agents and workflows, approve or reject steps, inject faults. `MEMBER`: view, plan, run,
  retry, pause. Only the step's own agent (by API key) or an admin can report an external step's result.

## Secrets

Memory, artifacts, stored step inputs and outputs and tool-call summaries are all checked with the existing secret
detector (handoffs created by the engine carry input names, not values) (passwords, tokens, private keys, common API key formats). Artifacts and memories that
look like credentials are refused; stored step inputs/outputs and tool summaries are redacted. The executor still
receives the real input value; nothing stores it.

## Tests

`OrchestrationSecurityIT` (cross-tenant reads and writes on every new resource, member restrictions, runtime
denial and audit, approval-gated tools, secret handling), `AgentDefinitionIT` (validation, instruction warnings),
`WorkspaceToolsTest` (path traversal and symlink escape), `MigrationIT` (RLS on every new table).
