# Graph queries

Every Cypher statement Memora runs against FalkorDB. The statements in the catalogue below are **generated from the
code** (`GraphQueryCatalog` reads the constants by reflection) and `GraphQueriesDocTest` fails the build if this
file stops being a verbatim copy. Regenerate with:

```bash
cd backend && ./mvnw -q test-compile && ./mvnw -q dependency:build-classpath -Dmdep.outputFile=cp.txt \
  && java -cp "target/classes:target/test-classes:$(cat cp.txt)" com.memora.graph.GraphQueryCatalog
```

All statements run against the caller's tenant graph `memora_t_<tenant id without dashes>`
(`TenantGraphs.nameFor`), take user input only as parameters (`$name`), and every read goes through
`GRAPH.RO_QUERY` (`GraphClient.read`). They were verified against `falkordb/falkordb:v4.14.6` by the integration
tests (`GraphMemoryIT`, `GraphRestartIT`).

FalkorDB dialect notes that shaped them: index creation is not idempotent (an "already indexed" error is expected
and ignored); there is no undirected `shortestPath()`, so explain uses a bounded variable-length match ordered by
length; full-text queries use RediSearch syntax, so the query is reduced to plain words joined by `|`.

## Index statements (`TenantGraphs.createIndexes`, once per graph)

```cypher
CREATE INDEX FOR (n:<Label>) ON (n.id)      -- for Memory, Agent, User, Project, Conversation, Message, Task, Handoff, Run, Step, Alert
CREATE INDEX FOR (e:Entity) ON (e.key)
CALL db.idx.fulltext.createNodeIndex('Memory', 'content')
```

## Algorithms and what each query is for

Retrieval score (`GraphContextRetriever`): `0.6^hops × importance/5 × confidence × (0.5 + 0.5·e^(−ageDays/30))`;
a memory reached by several paths keeps its best score. Full-text hits and fill-up memories (not reached) are
multiplied by `0.6^3`, so a fact connected by graph edges outranks one that only shares a word with the query.
(The first retrieval eval run, before this weight existed, showed full-text hits on the word "service" crowding out
entity hits; see [TEST_RESULTS.md](TEST_RESULTS.md#retrieval-eval-graph-vs-keyword).)

### Projection (outbox → graph) (`GraphProjection`)

| Query | Purpose |
|---|---|
| [`MEMORY_NODE`](#memory_node) | Upsert a memory node with its current status, version, importance, confidence and scope. |
| [`MEMORY_ABOUT`](#memory_about) | Link a fact to the entity it is about (its canonical subject). |
| [`MEMORY_MENTIONS`](#memory_mentions) | Link a memory to every entity it mentions (fact value, known technologies, component names). |
| [`MEMORY_RELATES`](#memory_relates) | Store the fact itself as an Entity→Entity edge so facts chain across hops; carries the memory's status. |
| [`MEMORY_SUPERSEDES`](#memory_supersedes) | Belief revision: the new version points at the version it replaced. |
| [`MEMORY_PROVENANCE`](#memory_provenance) | Memory ← message it was extracted from. |
| [`MEMORY_PROJECT`](#memory_project) | Memory → project. |
| [`MEMORY_TASK`](#memory_task) | Memory → task it was recorded for. |
| [`MEMORY_WRITTEN_BY_AGENT`](#memory_written_by_agent) | Agent that wrote the memory. |
| [`MEMORY_WRITTEN_BY_USER`](#memory_written_by_user) | User that wrote the memory. |
| [`MEMORY_PRODUCED_BY_STEP`](#memory_produced_by_step) | Workflow step that produced the memory. |
| [`PROJECT_NODE`](#project_node) | Upsert a project. |
| [`AGENT_NODE`](#agent_node) | Upsert an agent. |
| [`CONVERSATION_NODE`](#conversation_node) | Upsert a conversation and link it to its project. |
| [`MESSAGE_NODE`](#message_node) | Upsert a message (role, sequence, snippet) inside its conversation. |
| [`TASK_NODE`](#task_node) | Upsert a task and drop its old assignment edge (re-added below). |
| [`TASK_ASSIGNEE`](#task_assignee) | Task → assigned agent. |
| [`TASK_PROJECT`](#task_project) | Task → project. |
| [`HANDOFF_NODE`](#handoff_node) | Upsert a handoff with its sender, receiver and task. |
| [`HANDOFF_CARRIES`](#handoff_carries) | Handoff → memories it carries to the receiver. |
| [`RUN_NODE`](#run_node) | Upsert a workflow run in its project. |
| [`RUN_TASK`](#run_task) | Run → task it works on. |
| [`STEP_NODE`](#step_node) | Upsert a workflow step, its run and the agent executing it. |

### Reads (who has seen what) (`GraphProjection`)

| Query | Purpose |
|---|---|
| [`AGENT_READ`](#agent_read) | An agent read memories (context bundle, memory.search, context.build): READ edge with count and lastAt. |
| [`USER_READ`](#user_read) | Same for a user. |
| [`STEP_USED`](#step_used) | A workflow step used these memories. |

### Context retrieval (`GraphContextRetriever`)

| Query | Purpose |
|---|---|
| [`BY_ENTITY`](#by_entity) | Context seed 1: memories about or mentioning the query's entities, directly or one RELATES hop away (only CURRENT fact edges). |
| [`BY_TEXT`](#by_text) | Context seed 2: full-text hits on Memory.content. |
| [`BY_TEXT_NEIGHBOURS`](#by_text_neighbours) | Context seed 2b: memories about the same entity as a full-text hit (2 hops). |
| [`BY_TASK`](#by_task) | Context seed 3: memories recorded for the task. |
| [`BY_TASK_HANDOFF`](#by_task_handoff) | Context seed 3b: memories carried by the task's handoffs (2 hops). |
| [`MOST_IMPORTANT`](#most_important) | Fill: most important visible memories when the traversal found fewer than the budget. |

### Explain and explorer (`GraphExplainService`)

| Query | Purpose |
|---|---|
| [`PATH_FROM_TASK`](#path_from_task) | Explain: shortest connecting path task → memory (bounded, undirected). |
| [`PATH_FROM_AGENT`](#path_from_agent) | Explain: shortest connecting path agent → memory. |
| [`PATH_FROM_ENTITY`](#path_from_entity) | Explain: shortest connecting path entity → memory. |
| [`PROVENANCE`](#provenance) | Explain: message, conversation, writers, previous and next versions, entities and readers of a memory. |
| [`VIEW_NODES`](#view_nodes) | Graph explorer: nodes (optionally one label). |
| [`VIEW_EDGES`](#view_edges) | Graph explorer: edges. |
| [`NEIGHBOURHOOD`](#neighbourhood) | Graph explorer: click-to-expand neighbours of one node. |
| [`COUNTS`](#counts) | Node counts per label (explorer, rebuild check). |
| [`EDGE_COUNTS`](#edge_counts) | Edge counts per type. |

### Stale-knowledge alerts (`GraphAlerts`)

| Query | Purpose |
|---|---|
| [`RAISE_FOR_READERS`](#raise_for_readers) | Stale knowledge: agents that READ a memory that is no longer CURRENT get an Alert. |
| [`RAISE_FOR_RUNS`](#raise_for_runs) | Stale knowledge: active runs whose steps USED it get an Alert. |
| [`RAISE_FOR_HANDOFFS`](#raise_for_handoffs) | Stale knowledge: the receiver of an open handoff that CARRIES it gets an Alert. |
| [`OPEN_FOR_AGENT`](#open_for_agent) | Open alerts for the calling agent, with the current replacement found through SUPERSEDES*1..10. |
| [`MARK_DELIVERED`](#mark_delivered) | Mark alerts shown in a context bundle as delivered. |
| [`LIST`](#list) | All alerts of the tenant for GET /api/graph/alerts. |

### Agent graph tools (`GraphInsights`)

| Query | Purpose |
|---|---|
| [`NEIGHBOURS_1`](#neighbours_1) | Tool graph.neighbors, depth 1. |
| [`NEIGHBOURS_2`](#neighbours_2) | Tool graph.neighbors, depth 2. |
| [`WRITERS`](#writers) | Tool graph.who_knows: who wrote current memories about the entity. |
| [`READERS`](#readers) | Tool graph.who_knows: who read them, and how often. |
| [`DEPENDENTS`](#dependents) | Tool graph.impact: entities with a current fact pointing at this one (billing-service USES postgresql). |
| [`IMPACT`](#impact) | Tool graph.impact: memories, tasks, handoffs and runs connected to the entity. |
| [`RESOLVE`](#resolve) | Graph tools given free text: pick its best-connected known entity. |
| [`KEY_ENTITIES`](#key_entities) | GET /api/graph/key-entities and the graph page: PageRank (`algo.pageRank`) over Entity-RELATES-Entity, only entities with a CURRENT fact edge are listed. The score itself counts superseded RELATES edges too, because the procedure takes no edge filter. |

## Catalogue (generated from the code)

### `GraphProjection`

#### MEMORY_NODE

```cypher
UNWIND $rows AS r
MERGE (m:Memory {id: r.id})
ON CREATE SET m.createdAt = r.createdAt
SET m.type = r.type, m.category = r.category, m.content = r.content, m.status = r.status,
    m.version = r.version, m.importance = r.importance, m.confidence = r.confidence,
    m.factKey = r.factKey, m.scopeKey = r.scopeKey, m.projectId = r.projectId, m.subject = r.subject,
    m.predicate = r.predicate, m.value = r.value, m.expiresAt = r.expiresAt, m.updatedAt = r.updatedAt
RETURN count(m) AS n
```

#### MEMORY_ABOUT

```cypher
UNWIND $rows AS r
WITH r WHERE r.aboutKey IS NOT NULL
MATCH (m:Memory {id: r.id})
MERGE (e:Entity {key: r.aboutKey})
ON CREATE SET e.name = r.aboutName, e.kind = r.aboutKind, e.createdAt = r.createdAt
MERGE (m)-[:ABOUT]->(e)
RETURN count(e) AS n
```

#### MEMORY_MENTIONS

```cypher
UNWIND $mentions AS x
MATCH (m:Memory {id: x.memoryId})
MERGE (e:Entity {key: x.key})
ON CREATE SET e.name = x.name, e.kind = x.kind, e.createdAt = x.createdAt
MERGE (m)-[:MENTIONS]->(e)
RETURN count(e) AS n
```

#### MEMORY_RELATES

```cypher
UNWIND $rows AS r
WITH r WHERE r.relates
MATCH (a:Entity {key: r.aboutKey}), (b:Entity {key: r.valueKey})
MERGE (a)-[x:RELATES {memoryId: r.id}]->(b)
SET x.predicate = r.predicate, x.status = r.status
RETURN count(x) AS n
```

#### MEMORY_SUPERSEDES

```cypher
UNWIND $rows AS r
WITH r WHERE r.supersedesId IS NOT NULL
MATCH (m:Memory {id: r.id})
MERGE (o:Memory {id: r.supersedesId})
MERGE (m)-[:SUPERSEDES]->(o)
RETURN count(o) AS n
```

#### MEMORY_PROVENANCE

```cypher
UNWIND $rows AS r
WITH r WHERE r.messageId IS NOT NULL
MATCH (m:Memory {id: r.id})
MERGE (msg:Message {id: r.messageId})
MERGE (m)-[:EXTRACTED_FROM]->(msg)
RETURN count(msg) AS n
```

#### MEMORY_PROJECT

```cypher
UNWIND $rows AS r
WITH r WHERE r.projectId IS NOT NULL
MATCH (m:Memory {id: r.id})
MERGE (p:Project {id: r.projectId})
MERGE (m)-[:IN_PROJECT]->(p)
RETURN count(p) AS n
```

#### MEMORY_TASK

```cypher
UNWIND $rows AS r
WITH r WHERE r.taskId IS NOT NULL
MATCH (m:Memory {id: r.id})
MERGE (t:Task {id: r.taskId})
MERGE (m)-[:FOR_TASK]->(t)
RETURN count(t) AS n
```

#### MEMORY_WRITTEN_BY_AGENT

```cypher
UNWIND $rows AS r
WITH r WHERE r.agentId IS NOT NULL
MATCH (m:Memory {id: r.id})
MERGE (a:Agent {id: r.agentId})
MERGE (a)-[:WROTE]->(m)
RETURN count(a) AS n
```

#### MEMORY_WRITTEN_BY_USER

```cypher
UNWIND $rows AS r
WITH r WHERE r.userId IS NOT NULL
MATCH (m:Memory {id: r.id})
MERGE (u:User {id: r.userId})
SET u.name = r.userName
MERGE (u)-[:WROTE]->(m)
RETURN count(u) AS n
```

#### MEMORY_PRODUCED_BY_STEP

```cypher
UNWIND $rows AS r
WITH r WHERE r.stepId IS NOT NULL
MATCH (m:Memory {id: r.id})
MERGE (s:Step {id: r.stepId})
MERGE (s)-[:PRODUCED]->(m)
RETURN count(s) AS n
```

#### PROJECT_NODE

```cypher
UNWIND $rows AS r
MERGE (p:Project {id: r.id})
ON CREATE SET p.createdAt = r.createdAt
SET p.name = r.name, p.slug = r.slug
RETURN count(p) AS n
```

#### AGENT_NODE

```cypher
UNWIND $rows AS r
MERGE (a:Agent {id: r.id})
ON CREATE SET a.createdAt = r.createdAt
SET a.name = r.name, a.role = r.role
RETURN count(a) AS n
```

#### CONVERSATION_NODE

```cypher
UNWIND $rows AS r
MERGE (c:Conversation {id: r.id})
ON CREATE SET c.createdAt = r.createdAt
SET c.title = r.title
WITH c, r WHERE r.projectId IS NOT NULL
MERGE (p:Project {id: r.projectId})
MERGE (c)-[:IN_PROJECT]->(p)
RETURN count(c) AS n
```

#### MESSAGE_NODE

```cypher
UNWIND $rows AS r
MERGE (msg:Message {id: r.id})
ON CREATE SET msg.createdAt = r.createdAt
SET msg.role = r.role, msg.seq = r.seq, msg.snippet = r.snippet
MERGE (c:Conversation {id: r.conversationId})
MERGE (msg)-[:IN]->(c)
RETURN count(msg) AS n
```

#### TASK_NODE

```cypher
UNWIND $rows AS r
MERGE (t:Task {id: r.id})
ON CREATE SET t.createdAt = r.createdAt
SET t.title = r.title, t.status = r.status, t.version = r.version, t.priority = r.priority
WITH t, r
OPTIONAL MATCH (t)-[old:ASSIGNED_TO]->(:Agent)
DELETE old
RETURN count(DISTINCT t) AS n
```

#### TASK_ASSIGNEE

```cypher
UNWIND $rows AS r
WITH r WHERE r.assigneeId IS NOT NULL
MATCH (t:Task {id: r.id})
MERGE (a:Agent {id: r.assigneeId})
MERGE (t)-[:ASSIGNED_TO]->(a)
RETURN count(a) AS n
```

#### TASK_PROJECT

```cypher
UNWIND $rows AS r
WITH r WHERE r.projectId IS NOT NULL
MATCH (t:Task {id: r.id})
MERGE (p:Project {id: r.projectId})
MERGE (t)-[:IN_PROJECT]->(p)
RETURN count(p) AS n
```

#### HANDOFF_NODE

```cypher
UNWIND $rows AS r
MERGE (h:Handoff {id: r.id})
ON CREATE SET h.createdAt = r.createdAt
SET h.status = r.status, h.summary = r.summary, h.nextAction = r.nextAction
MERGE (f:Agent {id: r.fromId})
MERGE (to:Agent {id: r.toId})
MERGE (t:Task {id: r.taskId})
MERGE (h)-[:FROM]->(f)
MERGE (h)-[:TO]->(to)
MERGE (h)-[:FOR]->(t)
RETURN count(h) AS n
```

#### HANDOFF_CARRIES

```cypher
UNWIND $carries AS x
MATCH (h:Handoff {id: x.handoffId}), (m:Memory {id: x.memoryId})
MERGE (h)-[:CARRIES]->(m)
RETURN count(m) AS n
```

#### RUN_NODE

```cypher
UNWIND $rows AS r
MERGE (run:Run {id: r.id})
ON CREATE SET run.createdAt = r.createdAt
SET run.status = r.status, run.name = r.name
WITH run, r
MERGE (p:Project {id: r.projectId})
MERGE (run)-[:IN_PROJECT]->(p)
RETURN count(run) AS n
```

#### RUN_TASK

```cypher
UNWIND $rows AS r
WITH r WHERE r.taskId IS NOT NULL
MATCH (run:Run {id: r.id})
MERGE (t:Task {id: r.taskId})
MERGE (run)-[:FOR_TASK]->(t)
RETURN count(t) AS n
```

#### STEP_NODE

```cypher
UNWIND $rows AS r
MERGE (s:Step {id: r.id})
ON CREATE SET s.createdAt = r.createdAt
SET s.name = r.name, s.status = r.status, s.stepKey = r.stepKey
MERGE (run:Run {id: r.runId})
MERGE (run)-[:HAS_STEP]->(s)
WITH s, r WHERE r.agentId IS NOT NULL
MERGE (a:Agent {id: r.agentId})
MERGE (s)-[:EXECUTED_BY]->(a)
RETURN count(s) AS n
```

#### AGENT_READ

```cypher
MERGE (a:Agent {id: $readerId})
WITH a
UNWIND $memoryIds AS mid
MATCH (m:Memory {id: mid})
MERGE (a)-[r:READ]->(m)
ON CREATE SET r.count = 0, r.firstAt = $at
SET r.count = r.count + 1, r.lastAt = $at
RETURN count(r) AS n
```

#### USER_READ

```cypher
MERGE (u:User {id: $readerId})
WITH u
UNWIND $memoryIds AS mid
MATCH (m:Memory {id: mid})
MERGE (u)-[r:READ]->(m)
ON CREATE SET r.count = 0, r.firstAt = $at
SET r.count = r.count + 1, r.lastAt = $at
RETURN count(r) AS n
```

#### STEP_USED

```cypher
MERGE (s:Step {id: $stepId})
WITH s
UNWIND $memoryIds AS mid
MATCH (m:Memory {id: mid})
MERGE (s)-[:USED]->(m)
RETURN count(m) AS n
```

### `GraphAlerts`

#### RAISE_FOR_READERS

```cypher
UNWIND $ids AS id
MATCH (old:Memory {id: id})<-[rd:READ]-(a:Agent)
WHERE old.status <> 'CURRENT'
MERGE (al:Alert {id: id + '|agent|' + a.id})
ON CREATE SET al.status = 'OPEN', al.kind = 'READ_BY_AGENT', al.createdAt = $now, al.memoryId = id,
              al.change = old.status, al.readAt = rd.lastAt
MERGE (al)-[:NOTIFIES]->(a)
MERGE (al)-[:CONCERNS]->(old)
RETURN count(al) AS n
```

#### RAISE_FOR_RUNS

```cypher
UNWIND $ids AS id
MATCH (old:Memory {id: id})<-[:USED]-(s:Step)<-[:HAS_STEP]-(run:Run)
WHERE old.status <> 'CURRENT' AND run.status IN ['PENDING', 'RUNNING', 'WAITING_APPROVAL', 'PAUSED', 'BLOCKED']
MERGE (al:Alert {id: id + '|run|' + run.id})
ON CREATE SET al.status = 'OPEN', al.kind = 'USED_BY_ACTIVE_RUN', al.createdAt = $now, al.memoryId = id,
              al.change = old.status
MERGE (al)-[:NOTIFIES]->(run)
MERGE (al)-[:CONCERNS]->(old)
RETURN count(al) AS n
```

#### RAISE_FOR_HANDOFFS

```cypher
UNWIND $ids AS id
MATCH (old:Memory {id: id})<-[:CARRIES]-(h:Handoff)-[:TO]->(a:Agent)
WHERE old.status <> 'CURRENT' AND h.status IN ['PENDING', 'ACKNOWLEDGED']
MERGE (al:Alert {id: id + '|handoff|' + h.id})
ON CREATE SET al.status = 'OPEN', al.kind = 'CARRIED_BY_OPEN_HANDOFF', al.createdAt = $now, al.memoryId = id,
              al.change = old.status
MERGE (al)-[:NOTIFIES]->(a)
MERGE (al)-[:VIA]->(h)
MERGE (al)-[:CONCERNS]->(old)
RETURN count(al) AS n
```

#### OPEN_FOR_AGENT

```cypher
MATCH (al:Alert {status: 'OPEN'})-[:NOTIFIES]->(a:Agent {id: $agentId})
MATCH (al)-[:CONCERNS]->(old:Memory)
OPTIONAL MATCH (nw:Memory {status: 'CURRENT'})-[:SUPERSEDES*1..10]->(old)
OPTIONAL MATCH (al)-[:VIA]->(h:Handoff)
RETURN al.id AS alertId, al.kind AS kind, al.createdAt AS createdAt, a.name AS agentName,
       old.id AS oldId, old.content AS oldContent, old.status AS oldStatus, old.scopeKey AS oldScope,
       nw.id AS newId, nw.content AS newContent, h.id AS handoffId
ORDER BY al.createdAt
LIMIT 20
```

#### MARK_DELIVERED

```cypher
UNWIND $ids AS id
MATCH (al:Alert {id: id})
SET al.status = 'DELIVERED', al.deliveredAt = $now
RETURN count(al) AS n
```

#### LIST

```cypher
MATCH (al:Alert)-[:CONCERNS]->(old:Memory)
MATCH (al)-[:NOTIFIES]->(t)
OPTIONAL MATCH (nw:Memory {status: 'CURRENT'})-[:SUPERSEDES*1..10]->(old)
RETURN al.id AS alertId, al.kind AS kind, al.status AS status, al.createdAt AS createdAt,
       al.deliveredAt AS deliveredAt, labels(t)[0] AS targetLabel, t.id AS targetId,
       coalesce(t.name, t.id) AS targetName, old.id AS oldId, old.content AS oldContent,
       old.status AS oldStatus, old.scopeKey AS oldScope, nw.id AS newId, nw.content AS newContent
ORDER BY al.createdAt DESC
LIMIT $limit
```

### `GraphContextRetriever`

#### BY_ENTITY

```cypher
MATCH (e:Entity) WHERE e.key IN $keys
MATCH p = (e)-[:RELATES*0..1]-(:Entity)<-[:ABOUT|MENTIONS]-(m:Memory)
WHERE all(r IN relationships(p) WHERE type(r) <> 'RELATES' OR r.status = 'CURRENT') AND
m.status = 'CURRENT' AND m.type = $type
  AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
  AND ($projectId IS NULL OR m.projectId IS NULL OR m.projectId = $projectId)
  AND (m.expiresAt IS NULL OR m.expiresAt > $now)
  AND ($scopeKeys IS NULL OR m.scopeKey IN $scopeKeys)
RETURN m.id AS id, length(p) AS hops, m.importance AS importance, m.confidence AS confidence,
       m.createdAt AS createdAt,
       [n IN nodes(p) | [labels(n)[0], coalesce(n.id, n.key), coalesce(n.name, n.title, n.content, n.summary, n.key)]] AS nodes,
       [r IN relationships(p) | type(r)] AS rels
LIMIT 300
```

#### BY_TEXT

```cypher
CALL db.idx.fulltext.queryNodes('Memory', $text) YIELD node
WITH node AS m
MATCH p = (m)
WHERE
m.status = 'CURRENT' AND m.type = $type
  AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
  AND ($projectId IS NULL OR m.projectId IS NULL OR m.projectId = $projectId)
  AND (m.expiresAt IS NULL OR m.expiresAt > $now)
  AND ($scopeKeys IS NULL OR m.scopeKey IN $scopeKeys)
RETURN m.id AS id, length(p) AS hops, m.importance AS importance, m.confidence AS confidence,
       m.createdAt AS createdAt,
       [n IN nodes(p) | [labels(n)[0], coalesce(n.id, n.key), coalesce(n.name, n.title, n.content, n.summary, n.key)]] AS nodes,
       [r IN relationships(p) | type(r)] AS rels
LIMIT 300
```

#### BY_TEXT_NEIGHBOURS

```cypher
CALL db.idx.fulltext.queryNodes('Memory', $text) YIELD node
WITH node AS hit LIMIT 20
MATCH p = (hit)-[:ABOUT]->(:Entity)<-[:ABOUT|MENTIONS]-(m:Memory)
WHERE hit.status = 'CURRENT' AND
m.status = 'CURRENT' AND m.type = $type
  AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
  AND ($projectId IS NULL OR m.projectId IS NULL OR m.projectId = $projectId)
  AND (m.expiresAt IS NULL OR m.expiresAt > $now)
  AND ($scopeKeys IS NULL OR m.scopeKey IN $scopeKeys)
RETURN m.id AS id, length(p) AS hops, m.importance AS importance, m.confidence AS confidence,
       m.createdAt AS createdAt,
       [n IN nodes(p) | [labels(n)[0], coalesce(n.id, n.key), coalesce(n.name, n.title, n.content, n.summary, n.key)]] AS nodes,
       [r IN relationships(p) | type(r)] AS rels
LIMIT 300
```

#### BY_TASK

```cypher
MATCH (t:Task {id: $taskId})
MATCH p = (t)<-[:FOR_TASK]-(m:Memory)
WHERE
m.status = 'CURRENT' AND m.type = $type
  AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
  AND ($projectId IS NULL OR m.projectId IS NULL OR m.projectId = $projectId)
  AND (m.expiresAt IS NULL OR m.expiresAt > $now)
  AND ($scopeKeys IS NULL OR m.scopeKey IN $scopeKeys)
RETURN m.id AS id, length(p) AS hops, m.importance AS importance, m.confidence AS confidence,
       m.createdAt AS createdAt,
       [n IN nodes(p) | [labels(n)[0], coalesce(n.id, n.key), coalesce(n.name, n.title, n.content, n.summary, n.key)]] AS nodes,
       [r IN relationships(p) | type(r)] AS rels
LIMIT 300
```

#### BY_TASK_HANDOFF

```cypher
MATCH (t:Task {id: $taskId})
MATCH p = (t)<-[:FOR]-(:Handoff)-[:CARRIES]->(m:Memory)
WHERE
m.status = 'CURRENT' AND m.type = $type
  AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
  AND ($projectId IS NULL OR m.projectId IS NULL OR m.projectId = $projectId)
  AND (m.expiresAt IS NULL OR m.expiresAt > $now)
  AND ($scopeKeys IS NULL OR m.scopeKey IN $scopeKeys)
RETURN m.id AS id, length(p) AS hops, m.importance AS importance, m.confidence AS confidence,
       m.createdAt AS createdAt,
       [n IN nodes(p) | [labels(n)[0], coalesce(n.id, n.key), coalesce(n.name, n.title, n.content, n.summary, n.key)]] AS nodes,
       [r IN relationships(p) | type(r)] AS rels
LIMIT 300
```

#### MOST_IMPORTANT

```cypher
MATCH (m:Memory)
WHERE
m.status = 'CURRENT' AND m.type = $type
  AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
  AND ($projectId IS NULL OR m.projectId IS NULL OR m.projectId = $projectId)
  AND (m.expiresAt IS NULL OR m.expiresAt > $now)
  AND ($scopeKeys IS NULL OR m.scopeKey IN $scopeKeys)
WITH m ORDER BY m.importance DESC, m.createdAt DESC LIMIT $limit
MATCH p = (m)
RETURN m.id AS id, length(p) AS hops, m.importance AS importance, m.confidence AS confidence,
       m.createdAt AS createdAt,
       [n IN nodes(p) | [labels(n)[0], coalesce(n.id, n.key), coalesce(n.name, n.title, n.content, n.summary, n.key)]] AS nodes,
       [r IN relationships(p) | type(r)] AS rels
LIMIT 300
```

### `GraphExplainService`

#### PATH_FROM_TASK

```cypher
MATCH (s:Task {id: $from}), (m:Memory {id: $memoryId})
MATCH p = (s)-[*1..4]-(m)
WITH p ORDER BY length(p) LIMIT 1
RETURN [n IN nodes(p) | [labels(n)[0], coalesce(n.id, n.key), coalesce(n.name, n.title, n.content, n.summary, n.key)]] AS nodes,
       [r IN relationships(p) | type(r)] AS rels
```

#### PATH_FROM_AGENT

```cypher
MATCH (s:Agent {id: $from}), (m:Memory {id: $memoryId})
MATCH p = (s)-[*1..4]-(m)
WITH p ORDER BY length(p) LIMIT 1
RETURN [n IN nodes(p) | [labels(n)[0], coalesce(n.id, n.key), coalesce(n.name, n.title, n.content, n.summary, n.key)]] AS nodes,
       [r IN relationships(p) | type(r)] AS rels
```

#### PATH_FROM_ENTITY

```cypher
MATCH (s:Entity {key: $from}), (m:Memory {id: $memoryId})
MATCH p = (s)-[*1..4]-(m)
WITH p ORDER BY length(p) LIMIT 1
RETURN [n IN nodes(p) | [labels(n)[0], coalesce(n.id, n.key), coalesce(n.name, n.title, n.content, n.summary, n.key)]] AS nodes,
       [r IN relationships(p) | type(r)] AS rels
```

#### PROVENANCE

```cypher
MATCH (m:Memory {id: $memoryId})
OPTIONAL MATCH (m)-[:EXTRACTED_FROM]->(msg:Message)-[:IN]->(c:Conversation)
OPTIONAL MATCH (w)-[:WROTE]->(m)
OPTIONAL MATCH (m)-[:SUPERSEDES]->(prev:Memory)
OPTIONAL MATCH (nxt:Memory)-[:SUPERSEDES]->(m)
OPTIONAL MATCH (m)-[:ABOUT|MENTIONS]->(e:Entity)
OPTIONAL MATCH (reader:Agent)-[rd:READ]->(m)
RETURN m.content AS content, m.status AS status, m.version AS version, m.scopeKey AS scopeKey,
       msg.id AS messageId, msg.snippet AS messageSnippet, msg.role AS messageRole,
       c.id AS conversationId, c.title AS conversationTitle,
       collect(DISTINCT [labels(w)[0], w.id, w.name]) AS writers,
       collect(DISTINCT [prev.id, prev.content, prev.status]) AS previous,
       collect(DISTINCT [nxt.id, nxt.content, nxt.status]) AS next,
       collect(DISTINCT [e.key, e.name, e.kind]) AS entities,
       collect(DISTINCT [reader.id, reader.name, rd.count, rd.lastAt]) AS readers
```

#### VIEW_NODES

```cypher
MATCH (n)
WHERE NOT n:Alert AND ($label IS NULL OR labels(n)[0] = $label)
RETURN labels(n)[0] AS label, coalesce(n.id, n.key) AS id,
       coalesce(n.name, n.title, n.content, n.summary, n.key, n.id) AS name, n.status AS status,
       n.scopeKey AS scopeKey, n.type AS type
LIMIT $limit
```

#### VIEW_EDGES

```cypher
MATCH (a)-[r]->(b)
WHERE NOT a:Alert AND NOT b:Alert
RETURN labels(a)[0] AS fromLabel, coalesce(a.id, a.key) AS fromId, type(r) AS type, r.predicate AS predicate,
       r.status AS status, labels(b)[0] AS toLabel, coalesce(b.id, b.key) AS toId,
       a.scopeKey AS fromScope, b.scopeKey AS toScope
LIMIT $limit
```

#### NEIGHBOURHOOD

```cypher
MATCH (n) WHERE n.id = $id OR n.key = $id
MATCH (n)-[r]-(o)
WHERE NOT o:Alert
RETURN labels(n)[0] AS fromLabel, coalesce(n.id, n.key) AS fromId, n.scopeKey AS fromScope,
       type(r) AS type, r.predicate AS predicate, r.status AS status, startNode(r) = n AS outgoing,
       labels(o)[0] AS label, coalesce(o.id, o.key) AS id,
       coalesce(o.name, o.title, o.content, o.summary, o.key, o.id) AS name, o.status AS nodeStatus,
       o.scopeKey AS scopeKey, o.type AS memoryType
LIMIT 200
```

#### COUNTS

```cypher
MATCH (n) WHERE NOT n:Alert
RETURN labels(n)[0] AS label, count(n) AS n
```

#### EDGE_COUNTS

```cypher
MATCH (a)-[r]->(b) WHERE NOT a:Alert AND NOT b:Alert
RETURN type(r) AS type, count(r) AS n
```

### `GraphInsights`

#### NEIGHBOURS_1

```cypher
MATCH (e:Entity {key: $key})
MATCH p = (e)-[:ABOUT|MENTIONS|RELATES]-(n)
WHERE NOT n:Alert AND
all(n IN nodes(p) WHERE n.scopeKey IS NULL OR $seesAll OR NOT n.scopeKey STARTS WITH 'user:' OR n.scopeKey = $userScope)
  AND all(n IN nodes(p) WHERE n.status IS NULL OR NOT n:Memory OR n.status = 'CURRENT')
  AND all(r IN relationships(p) WHERE type(r) <> 'RELATES' OR r.status = 'CURRENT')
WITH n, min(length(p)) AS hops
RETURN labels(n)[0] AS label, coalesce(n.id, n.key) AS id,
       coalesce(n.name, n.title, n.content, n.summary, n.key) AS name, hops
ORDER BY hops, label
LIMIT 60
```

#### NEIGHBOURS_2

```cypher
MATCH (e:Entity {key: $key})
MATCH p = (e)-[:ABOUT|MENTIONS|RELATES*1..2]-(n)
WHERE NOT n:Alert AND n <> e AND
all(n IN nodes(p) WHERE n.scopeKey IS NULL OR $seesAll OR NOT n.scopeKey STARTS WITH 'user:' OR n.scopeKey = $userScope)
  AND all(n IN nodes(p) WHERE n.status IS NULL OR NOT n:Memory OR n.status = 'CURRENT')
  AND all(r IN relationships(p) WHERE type(r) <> 'RELATES' OR r.status = 'CURRENT')
WITH n, min(length(p)) AS hops
RETURN labels(n)[0] AS label, coalesce(n.id, n.key) AS id,
       coalesce(n.name, n.title, n.content, n.summary, n.key) AS name, hops
ORDER BY hops, label
LIMIT 60
```

#### WRITERS

```cypher
MATCH (e:Entity {key: $key})<-[:ABOUT|MENTIONS]-(m:Memory)<-[:WROTE]-(w)
WHERE m.status = 'CURRENT' AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
RETURN labels(w)[0] AS label, w.id AS id, w.name AS name, count(DISTINCT m) AS n
```

#### READERS

```cypher
MATCH (e:Entity {key: $key})<-[:ABOUT|MENTIONS]-(m:Memory)<-[rd:READ]-(r)
WHERE m.status = 'CURRENT' AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
RETURN labels(r)[0] AS label, r.id AS id, r.name AS name, count(DISTINCT m) AS n, sum(rd.count) AS reads
```

#### DEPENDENTS

```cypher
MATCH (dep:Entity)-[r:RELATES]->(e:Entity {key: $key})
WHERE r.status = 'CURRENT'
RETURN dep.key AS key, dep.name AS name, r.predicate AS predicate
```

#### IMPACT

```cypher
MATCH (e:Entity {key: $key})<-[:ABOUT|MENTIONS]-(m:Memory)
WHERE m.status = 'CURRENT' AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
OPTIONAL MATCH (m)-[:FOR_TASK]->(t:Task)
OPTIONAL MATCH (h:Handoff)-[:CARRIES]->(m)
OPTIONAL MATCH (h)-[:FOR]->(ht:Task)
OPTIONAL MATCH (run:Run)-[:HAS_STEP]->(:Step)-[:USED|PRODUCED]->(m)
RETURN collect(DISTINCT [m.id, m.content]) AS memories,
       collect(DISTINCT [t.id, t.title, t.status]) + collect(DISTINCT [ht.id, ht.title, ht.status]) AS tasks,
       collect(DISTINCT [h.id, h.status, h.summary]) AS handoffs,
       collect(DISTINCT [run.id, run.name, run.status]) AS runs
```

#### KEY_ENTITIES

```cypher
CALL algo.pageRank('Entity', 'RELATES') YIELD node, score
MATCH (node)-[r:RELATES]-(:Entity)
WHERE r.status = 'CURRENT'
WITH node, score, count(r) AS degree
RETURN node.key AS key, node.name AS name, score, degree
ORDER BY score DESC, degree DESC
LIMIT $limit
```

#### RESOLVE

```cypher
MATCH (e:Entity) WHERE e.key IN $keys
OPTIONAL MATCH (e)-[r]-()
RETURN e.key AS key, count(r) AS degree
ORDER BY degree DESC
LIMIT 1
```
