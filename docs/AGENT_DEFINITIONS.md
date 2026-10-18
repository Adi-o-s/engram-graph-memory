# Agent definitions

An agent is a **configuration**, edited in Agent studio (`/agent-studio`) or through
`/api/v1/agent-definitions`. Nothing about an agent's name or role changes how it runs; only its executor,
behaviour, tools and policies do. Two agents with the same behaviour do the same thing.

## Fields

| Field | Meaning |
|---|---|
| `key` | Stable identifier workflows refer to (lowercase, digits, hyphens). Cannot change. |
| `name`, `role`, `description` | Shown to people. `role` is free text ("Compliance reviewer"). |
| `instructions` | Descriptive text, shown in Agent studio and sent to external agents in their handoff. **Never parsed for permissions.** Wording such as "ignore previous rules" or "admin access" produces a validation warning. |
| `capabilities` | Labels such as `memory.read`, `artifact.write`, `verify`. A tool can only be used if its required capability is declared. |
| `tools` | The tools this agent may call. Anything else is denied at runtime. |
| `inputSchema`, `outputSchema` | JSON-schema subset (type, required, properties, items, enum, min/max). Inputs are checked before execution and outputs after; a mismatch fails the step permanently. |
| `memoryPolicy` | `read` and `write` scopes, `types`, `maxItems`, `maxChars` (see below). |
| `contextPolicy` | `includeArtifacts`, `maxChars`, `query` for `context.build`. |
| `executor` | `template` (deterministic, built in) or `external` (work done outside Memora). `model` is documented but not available. |
| `behavior` | Executor configuration (below). |
| `timeoutSeconds`, `maxAttempts`, `backoffSeconds` | Defaults for steps using the agent; a workflow step may override them. |
| `status` | `ACTIVE` or `INACTIVE`. Runs refuse inactive agents. |
| `version` | Every save stores an immutable snapshot (`/versions`). Runs pin the snapshot they started with. |

## Memory scopes

| Scope | Resolves to | Typical use |
|---|---|---|
| `RUN` | this run only | working notes, requirement lists |
| `PROJECT` | the run's project, across runs | decisions, requirements, outcomes |
| `WORKFLOW` | every run of the workflow | lessons, checklists |
| `AGENT` | the agent's own notes | personal procedures |
| `TENANT` | shared tenant knowledge | organisation facts |
| `USER` | the person who **started** the run | personal preferences (read only; agents never write it) |

A search is restricted in SQL to the exact scope keys allowed, so an agent cannot see another run's working memory
or another person's private memories, whatever its arguments say.

## Template executor behaviour

```json
{
  "operations": [
    {"id": "reqs", "tool": "text.extract_requirements", "args": {"text": "${input.goal}"}},
    {"id": "doc", "tool": "document.compose", "args": {"title": "Requirements", "sections": [
      {"heading": "Requirements", "items": "${ops.reqs.requirements}", "itemTemplate": "**{{id}}** {{text}}"}]}},
    {"id": "save", "tool": "artifact.write", "args": {"name": "requirements.md", "kind": "markdown", "content": "${ops.doc.markdown}"}},
    {"id": "remember", "tool": "memory.write", "forEach": "${ops.reqs.requirements}",
     "args": {"content": "Requirement ${item.id}: ${item.text}", "scope": "RUN", "category": "requirement"}},
    {"id": "gap", "tool": "memory.write", "when": {"path": "ops.reqs.count", "op": "eq", "value": 0},
     "args": {"content": "Nothing extracted", "type": "EPISODIC"}}
  ],
  "output": {"requirements": "${ops.reqs.requirements}", "count": "${ops.reqs.count}"},
  "failWhen": {"condition": {"path": "output.count", "op": "eq", "value": 0}, "reason": "No requirements", "retryable": false}
}
```

* `${path}` reads from `input`, `run` (`id`, `goal`, `inputs`, `projectId`), `steps.<key>.output|status|iteration`,
  `agent`, `step` and earlier `ops`. `${path | fallback}` uses the fallback when the value is missing or empty.
  A string that is exactly one placeholder keeps the value's type (lists stay lists).
* `{{field}}` inside `itemTemplate` (and `data.select`'s `format`) is filled per item by the tool.
* `forEach` runs an operation per list item (max 100); `when` skips it; `optional: true` continues past a non-denied failure.
* There is no code execution, no method calls and no operators: just paths and structured conditions.

## External executor

```json
{"executor": "external", "behavior": {"brief": "Implement the step in the repository and report changed files."}}
```

The engine sends a handoff to the agent's inbox and keeps the step RUNNING until the agent (authenticated with its
API key, issued in Agent workspace) completes the handoff with `{"result": {"output": {...}}}` or calls
`POST /api/v1/workflow-runs/{run}/steps/{step}/complete`. The output is checked against `outputSchema`; failing the
handoff schedules a retry; rejecting it fails the step; silence beyond the timeout fails the attempt.

## Templates

`GET /api/v1/agent-templates` lists read-only system templates; installing one creates an editable, tenant-owned
definition. Every tenant gets all of them installed on first start:

* **classic**: Planner, Developer, Tester (linked to the seeded principals of the same names);
* **software**: Requirements Analyst, Solution Architect, UX Designer, Backend Engineer, Frontend Engineer,
  Integration Engineer, QA Engineer, Remediation Engineer, Security Reviewer, Documentation Writer, Final Reviewer,
  Release Manager;
* **research**: Research Planner, Source Collector, Research Analyst, Report Writer, Fact Checker, Report Reviser, Editor;
* **general / data**: External Developer, Data Analyst.

## Validation

`POST /api/v1/agent-definitions/validate` returns errors and warnings without saving. Saving runs the same checks.
Rejected: unknown tools, tools whose capability is not declared, behaviour that calls a tool not in `tools`,
unavailable executors, malformed schemas, unknown memory scopes, writing to `USER`, malformed placeholders and
conditions. Warnings: tools unavailable in this deployment, tools that need an approval gate, instructions that try
to widen access or mention tools the agent cannot use.
