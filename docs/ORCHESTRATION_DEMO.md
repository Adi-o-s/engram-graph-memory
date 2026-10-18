# Orchestration demo (about 8 minutes)

Everything below runs on the real engine through the normal UI and API. Nothing is simulated except the clearly
labelled "Injected fault (demo)" option and the optional pacing delay that makes parallel steps visible.

**Setup:** `docker compose up --build`, open the dashboard and sign in as `alice@acme.test` / `memora-demo`.
Every tenant already has 24 agent definitions and 3 example workflows installed.

## 1. Describe a goal (Plan a workflow)

1. Open **Plan a workflow**. Click **E-commerce website**:
   *"Build a full-stack e-commerce website with responsive design, authentication, product search, a shopping
   cart, order management, database persistence, and automated tests."*
2. Click **Propose a workflow**. Point out:
   * classification `software`, confidence `high`, and the keyword evidence: the planner says it is pattern matching;
   * 7 requirements (R1–R7) with categories and acceptance criteria;
   * deliverables, roles, missing information and assumptions;
   * the limitation box: built-in agents produce specifications, contracts, a schema scaffold and verification
     reports; they do not write or run application code;
   * the graph: requirements → architecture ∥ design → backend ∥ frontend → integration / security (conditional)
     / payments compliance (conditional) → QA (loop with remediation) and docs → final review → approved handover.
3. Click **Edit in Workflow studio** to show the proposal is editable (add a step, change dependencies, see the
   validation and parallel groups update). Then come back and click **Save and run now**.

## 2. Watch it run (Workflow runs)

* The **graph** turns blue and green live; **architecture** and **design** run at the same time, as do
  **backend** and **frontend**. The **timeline** shows their overlapping bars from persisted start and finish times.
* **Frontend** starts only after both architecture and design finish: click it and read *Why this step was
  eligible*: both dependencies with their status.
* **Payments compliance** is `skipped`: its condition (`categories contains payments`) was false; the trace is shown.
* **QA** fails its first iteration: requirement R5 ("order management") has no API capability or screen. The
  remediation step **Fix coverage gaps** adds one (`gap-resolution.md`), QA runs again (×2 badge) and passes.
* Click any step: inputs, outputs, tool calls (each authorised and timed), log, artifacts, the task on the board and
  its handoff.
* **Approved handover** waits for a person: click **Approve**. The run becomes **Succeeded** and the completion card
  shows each criterion evaluated against actual outputs.
* Open artifacts such as `api-contract.json` (requirement ids in `covers`), `schema.sql`, `final-report.md` (which
  states nothing was compiled or deployed); each has a SHA-256 checksum.

## 3. Memory and prior decisions

* **Project workspace** for the run's project: requirements, decisions ("the API is REST over JSON", "application
  data is stored in postgresql"), lessons, artifacts and history.
* Run the same workflow in the seeded **Atlas Warehouse Robots** project: the architect reads the project's current
  database fact (PostgreSQL, which superseded MySQL in conversation) instead of assuming one. Add the message
  "We switched from PostgreSQL to SQLite." in Conversations and run again: the architecture now says SQLite and its
  own decision fact is superseded with history (Memory explorer → history).

## 4. Failure, retry and resumption

1. In **Workflow studio**, open **Plan, build, test**. In the run panel set fault injection to
   `{"build": {"failAttempts": 1}}` and start. The build step's first attempt fails, the event log shows
   `retry_scheduled`, the second attempt succeeds (attempts 2/3).
2. Start it again with `{"build": {"failAttempts": 3}}`: retries are exhausted, **test** is blocked, the run is
   **Failed** with the reason. Click **build → Retry step**: one more attempt is granted, the run resumes and
   succeeds without re-running **plan**.
3. Start any run with pacing, click **Pause** while a step runs: the running step finishes and is recorded, nothing
   new starts. **Resume** continues from where it stopped.
4. Restart the backend mid-run (`docker compose restart backend`, with pacing set high): steps that had finished
   keep their outputs and are not re-run. A step that was still running either finishes during graceful shutdown or
   is detected at startup (`lease_expired`: "The backend restarted…") and retried. The run then continues.
   (`WorkflowRestartIT` tests the crash case explicitly.)

## 5. A completely different workflow on the same engine

Open **Workflow studio → Research and report**, choose the project **Search engine evaluation** (it has six recorded
notes) and run with the brief from the **Research report** example on the Plan page. Different agents (planner,
source collector, analyst, writer, fact checker, reviser, editor), same engine:

* **plan** and **collect** run in parallel;
* the report cites notes as `[m:…]`;
* "vendor lock-in" has no recorded evidence, so the **fact check** fails once and the **reviser** discloses it as an
  open question instead of inventing an answer; the second fact check passes;
* the report says it has no recommendation because there is no language model.

## 6. Security in one minute

* Sign in as `bob@globex.test`: none of Acme's agents, workflows or runs are visible; their ids return 404.
* As `aaron@acme.test` (member): runs can be started, but **Approve** and editing are admin only.
* In Agent studio, add `command.run` to an agent: validation warns that it needs an approval gate and is unavailable
  without a configured workspace; a workflow using it without approval is rejected.

## API version of the main run

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice@acme.test","password":"memora-demo"}' | jq -r .token)
WF=$(curl -s localhost:8080/api/v1/workflows -H "Authorization: Bearer $TOKEN" | jq -r '.[] | select(.key=="fullstack-delivery") | .id')
RUN=$(curl -s localhost:8080/api/v1/workflows/$WF/runs -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"goal":"Build a full-stack e-commerce website with responsive design, authentication, product search, a shopping cart, order management, database persistence, and automated tests.","options":{"pacingMs":300}}' | jq -r .runId)
curl -s localhost:8080/api/v1/workflow-runs/$RUN -H "Authorization: Bearer $TOKEN" | jq '{status, stepCounts}'
```

(With the ports used on this machine, replace 8080 by 8081.)
