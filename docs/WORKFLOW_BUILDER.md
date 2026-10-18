# Workflow builder

A workflow is a versioned specification of steps, each assigned to an agent definition, connected by explicit
dependencies. Build it in Workflow studio (`/workflows`), start from a plan (`/plan`), or use
`/api/v1/workflows`. Every save is validated and stored as a new immutable version; runs record the version and the
agent versions they used.

## Specification

```json
{
  "inputs": [{"name": "goal", "type": "text", "required": true, "description": "What to build"}],
  "steps": [
    {"key": "requirements", "name": "Extract requirements", "agent": "requirements-analyst",
     "dependsOn": [], "inputs": {"goal": "${run.inputs.goal}"}},
    {"key": "architecture", "agent": "solution-architect", "dependsOn": ["requirements"],
     "inputs": {"requirements": "${steps.requirements.output.requirements}"}},
    {"key": "design", "agent": "ux-designer", "dependsOn": ["requirements"],
     "inputs": {"requirements": "${steps.requirements.output.requirements}"}},
    {"key": "security", "agent": "security-reviewer", "dependsOn": ["architecture"],
     "when": {"path": "steps.requirements.output.categories", "op": "contains", "value": "authentication"}},
    {"key": "qa", "agent": "qa-engineer", "dependsOn": ["architecture", "design"],
     "inputs": {"requirements": "${steps.requirements.output.requirements}"},
     "retry": {"maxAttempts": 3, "backoffSeconds": 1}, "timeoutSeconds": 120,
     "loop": {"until": {"path": "output.passed", "op": "eq", "value": true}, "maxIterations": 3, "remediationStep": "fix"}},
    {"key": "fix", "agent": "remediation-engineer", "remediation": true,
     "inputs": {"missing": "${steps.qa.output.missing}", "requirements": "${steps.requirements.output.requirements}"}},
    {"key": "handover", "agent": "release-manager", "dependsOn": ["qa", "security"],
     "approval": {"required": true, "reason": "A person signs off"}}
  ],
  "completion": {
    "requiredSteps": ["qa", "handover"],
    "criteria": [{"name": "QA passed", "condition": {"path": "steps.qa.output.passed", "op": "eq", "value": true}}]
  },
  "outputs": {"verdict": "${steps.qa.output.summary}"}
}
```

| Feature | How it works |
|---|---|
| Sequential | `dependsOn` |
| Parallel | steps whose dependencies are satisfied run at the same time on separate workers (`architecture` and `design` above) |
| Conditional routing | `when` (structured condition). False means `SKIPPED`; skipped dependencies count as satisfied |
| Bounded iteration | `loop`: after the step succeeds, while `until` is false and fewer than `maxIterations` have run, the `remediation` step runs and then the step runs again. Exhausting the loop fails the step |
| Retries and timeouts | `retry`, `timeoutSeconds` (defaults come from the agent) |
| Approvals | `approval.required`: the step waits in `WAITING_APPROVAL` until an admin approves or rejects |
| Mandatory | `mandatory: false` lets a run succeed even if the step fails to finish |
| Data flow | step `inputs` are templates over `run.*` and upstream `steps.<key>.output.*` |
| Completion | `requiredSteps` must SUCCEED (skipping is not enough); every criterion must hold on the actual outputs |

Conditions: `{"path", "op", "value"}` with `eq, ne, gt, gte, lt, lte, exists, notExists, empty, notEmpty, truthy,
falsy, contains, notContains, in`, combined with `all`, `any`, `not`.

## Validation

`POST /api/v1/workflows/validate` (also run on every save and again at run start) rejects:

* unknown settings, missing or duplicate step keys, unknown or (at run start) inactive agents;
* dependencies on unknown steps, on itself, or on remediation steps; **cycles** (with the steps involved);
* loops without an exit condition, with more than 5 iterations, or naming a step that is not `remediation: true`,
  and remediation steps no loop uses;
* references to outputs of steps that are not upstream (their output is not guaranteed to exist), to undeclared
  workflow inputs, and malformed placeholders or conditions;
* steps whose agent may use a tool that requires approval (`command.run`) but that have no approval gate;
* completion criteria naming unknown steps.

It also returns the execution layers and the groups of steps that can run in parallel, shown in Workflow studio.

## Running

`POST /api/v1/workflows/{id}/runs` with `goal`, `inputs`, an optional `projectId` (otherwise a project is created
for the run), and `options`:

* `pacingMs` (0–5000): a visible delay per step so people can watch parallel execution;
* `faults` (admins only): `{"stepKey": {"failAttempts": 1, "retryable": true}}` makes the first attempts fail with a
  labelled "Injected fault (demo)" to demonstrate retries.

The Workflow runs page shows the live graph, a timeline of actual start and finish times, why each step became
ready (dependencies, condition trace, approval, manual retry), inputs, outputs, tool calls, logs, artifacts and
events, with pause, resume, cancel, retry and approve actions.
