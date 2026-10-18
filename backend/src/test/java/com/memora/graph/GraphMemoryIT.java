package com.memora.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.security.TenantContext;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The FalkorDB memory graph end to end: projection, graph retrieval, supersession, alerts, isolation, rebuild. */
class GraphMemoryIT extends IntegrationTest {

    @Autowired
    GraphClient graph;
    @Autowired
    GraphProjector projector;
    @Autowired
    GraphExplainService explain;
    @Autowired
    JdbcTemplate jdbc;

    private ApiClient alice;
    private UUID acme;
    private String acmeGraph;

    @BeforeEach
    void setUp() {
        alice = as(ALICE);
        acme = tenant("acme");
        acmeGraph = TenantGraphs.nameFor(acme);
    }

    private UUID tenant(String slug) {
        return TenantContext.callAsSystem(() -> jdbc.queryForObject("SELECT id FROM tenants WHERE slug = ?", UUID.class, slug));
    }

    private void say(String conversationId, String text) {
        Response r = alice.post("/api/v1/conversations/" + conversationId + "/messages", Map.of("content", text));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
    }

    private List<Map<String, Object>> query(String cypher, Map<String, Object> params) {
        return graph.read(acmeGraph, "test", cypher, params);
    }

    private ApiClient newAgent(String prefix) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 6);
        Response r = alice.post("/api/v1/agents", Map.of("name", name, "role", "DEVELOPER"));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return anonymous().withAgentKey(r.text("apiKey"));
    }

    private static List<String> contents(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(m -> out.add(m.path("content").asText()));
        return out;
    }

    @Test
    void aMemoryWrittenInPostgresIsProjectedAndReplayIsIdempotent() {
        String content = "Procedure: rotate the graph keys every " + UUID.randomUUID();
        Response r = alice.post("/api/v1/memories", Map.of("type", "PROCEDURAL", "content", content, "importance", 4));
        String id = r.body().path("memory").path("id").asText();
        projector.catchUp(acme);

        List<Map<String, Object>> rows = query("MATCH (m:Memory {id: $id}) RETURN m.content AS content, m.type AS type", Map.of("id", id));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("content")).isEqualTo(content);
        assertThat(rows.get(0).get("type")).isEqualTo("PROCEDURAL");
        assertThat(query("MATCH (u:User)-[:WROTE]->(m:Memory {id: $id}) RETURN count(u) AS n", Map.of("id", id)).get(0).get("n"))
                .isEqualTo(1L);

        Map<String, Long> before = explain.counts(acmeGraph);
        // Replay the same entity twice more: MERGE-based projection must not duplicate anything.
        TenantContext.runAsSystem(() -> {
            jdbc.update("INSERT INTO graph_outbox (tenant_id, kind, entity_id) VALUES (?, 'MEMORY', ?)", acme, UUID.fromString(id));
            jdbc.update("INSERT INTO graph_outbox (tenant_id, kind, entity_id) VALUES (?, 'MEMORY', ?)", acme, UUID.fromString(id));
        });
        projector.catchUp(acme);
        assertThat(explain.counts(acmeGraph)).isEqualTo(before);
    }

    @Test
    void graphRetrievalFindsATwoHopFactThatKeywordSearchMisses() {
        String project = newProject(alice, "graph-hop");
        String conversation = newConversation(alice, project);
        say(conversation, "billing-service uses Postgres. Postgres is hosted on RDS.");

        // PostgreSQL keyword search for the service does not find the hosting fact: it never mentions billing-service.
        JsonNode keyword = alice.get("/api/v1/memories?projectId=" + project + "&q=billing-service").body().path("items");
        assertThat(contents(keyword)).noneMatch(c -> c.contains("RDS"));

        Response ctx = alice.post("/api/v1/context", Map.of("query", "fix the billing-service migration", "projectId", project));
        assertThat(ctx.status()).as(ctx.toString()).isEqualTo(200);
        JsonNode body = ctx.body();
        assertThat(body.path("retrieval").asText()).isEqualTo("graph");
        String hostingId = null;
        for (JsonNode f : body.path("facts")) {
            if (f.path("content").asText().contains("RDS")) {
                hostingId = f.path("id").asText();
            }
        }
        assertThat(hostingId).as("graph context should contain the hosting fact: %s", body).isNotNull();
        JsonNode path = body.path("paths").path(hostingId);
        assertThat(path.path("hops").asInt()).isEqualTo(2);
        List<String> via = new ArrayList<>();
        path.path("path").path("steps").forEach(s -> via.add(s.path("label").asText() + ":" + s.path("via").asText("-")));
        assertThat(via).containsExactly("Entity:-", "Entity:RELATES", "Memory:ABOUT");
        assertThat(path.path("path").path("steps").get(0).path("id").asText()).isEqualTo("billing-service");
        assertThat(path.path("path").path("steps").get(1).path("id").asText()).isEqualTo("postgresql");

        // Explain: the path from the entity, and provenance back to the message and conversation.
        Response why = alice.get("/api/graph/explain?memoryId=" + hostingId + "&from=entity:billing-service");
        assertThat(why.status()).as(why.toString()).isEqualTo(200);
        assertThat(why.body().path("path").path("hops").asInt()).isEqualTo(2);
        assertThat(why.body().path("provenance").path("conversation").path("id").asText()).isEqualTo(conversation);
        assertThat(why.body().path("provenance").path("message").path("snippet").asText()).contains("hosted on RDS");
    }

    @Test
    void aRevisedFactSupersedesTheOldNodeWhichLeavesTheContext() {
        String project = newProject(alice, "graph-revise");
        String conversation = newConversation(alice, project);
        say(conversation, "billing-service uses MySQL.");
        Response first = alice.post("/api/v1/context", Map.of("query", "billing-service database", "projectId", project));
        String oldId = first.body().path("facts").get(0).path("id").asText();
        assertThat(first.body().path("facts").get(0).path("content").asText()).contains("MySQL");

        say(conversation, "We moved billing-service to Postgres.");
        Response second = alice.post("/api/v1/context", Map.of("query", "billing-service database", "projectId", project));
        List<String> facts = contents(second.body().path("facts"));
        assertThat(facts).anyMatch(c -> c.contains("PostgreSQL")).noneMatch(c -> c.contains("MySQL"));

        List<Map<String, Object>> rows = query("""
                MATCH (nw:Memory)-[:SUPERSEDES]->(old:Memory {id: $id})
                RETURN old.status AS oldStatus, nw.status AS newStatus, nw.version AS version""", Map.of("id", oldId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("oldStatus", "SUPERSEDED").containsEntry("newStatus", "CURRENT").containsEntry("version", 2L);
        // The old fact edge is kept for history but marked, so traversal no longer follows it.
        assertThat(query("MATCH (:Entity {key: 'billing-service'})-[r:RELATES {memoryId: $id}]->(:Entity {key: 'mysql'}) "
                + "RETURN r.status AS status", Map.of("id", oldId)).get(0).get("status")).isEqualTo("SUPERSEDED");
    }

    @Test
    void anAgentThatReadAChangedFactIsWarnedAndOneThatDidNotIsNot() {
        String project = newProject(alice, "graph-stale");
        String conversation = newConversation(alice, project);
        say(conversation, "billing-service uses MySQL.");
        ApiClient coder = newAgent("Coder");
        ApiClient reviewer = newAgent("Reviewer");

        Response read = coder.post("/api/v1/context", Map.of("query", "billing-service", "projectId", project));
        assertThat(read.status()).as(read.toString()).isEqualTo(200);
        assertThat(read.body().path("rendered").asText()).contains("MySQL");

        say(conversation, "We moved billing-service to Postgres.");

        JsonNode coderNext = coder.post("/api/v1/context", Map.of("query", "billing-service", "projectId", project)).body();
        assertThat(coderNext.path("rendered").asText()).contains("Changed since you last read it")
                .contains("billing-service uses MySQL").contains("PostgreSQL");
        assertThat(coderNext.path("alerts")).hasSize(1);
        assertThat(coderNext.path("alerts").get(0).path("kind").asText()).isEqualTo("READ_BY_AGENT");

        JsonNode reviewerNext = reviewer.post("/api/v1/context", Map.of("query", "billing-service", "projectId", project)).body();
        assertThat(reviewerNext.path("rendered").asText()).doesNotContain("Changed since you last read it");
        assertThat(reviewerNext.path("alerts")).isEmpty();

        // Delivered once: the coder's following context no longer repeats the warning.
        JsonNode coderAgain = coder.post("/api/v1/context", Map.of("query", "billing-service", "projectId", project)).body();
        assertThat(coderAgain.path("alerts")).isEmpty();
        assertThat(alice.get("/api/graph/alerts").body().findValuesAsText("status")).contains("DELIVERED");
    }

    @Test
    void tenantsHaveSeparateGraphsAndForgedIdsFindNothing() {
        String project = newProject(alice, "graph-tenant");
        String conversation = newConversation(alice, project);
        String marker = "zebracorn" + UUID.randomUUID().toString().substring(0, 6);
        say(conversation, marker + "-service uses Kafka.");
        Response mine = alice.post("/api/v1/context", Map.of("query", marker + "-service", "projectId", project));
        String acmeMemory = mine.body().path("facts").get(0).path("id").asText();

        ApiClient bob = as(BOB);
        UUID globex = tenant("globex");
        assertThat(TenantGraphs.nameFor(globex)).isNotEqualTo(acmeGraph);
        JsonNode theirs = bob.post("/api/v1/context", Map.of("query", marker + "-service")).body();
        assertThat(theirs.path("facts").findValuesAsText("content")).noneMatch(c -> c.contains(marker));
        assertThat(theirs.path("paths").size()).isLessThanOrEqualTo(theirs.path("stats").path("memoriesIncluded").asInt());
        assertThat(bob.get("/api/graph/explain?memoryId=" + acmeMemory).status()).isEqualTo(404);
        assertThat(bob.get("/api/graph/neighbours?id=" + marker + "-service").body().path("nodes")).isEmpty();
        assertThat(bob.get("/api/graph/view?limit=2000").body().path("nodes").findValuesAsText("id")).doesNotContain(acmeMemory);
        // The graph itself: Globex's graph holds no node of Acme's.
        assertThat(graph.read(TenantGraphs.nameFor(globex), "test", "MATCH (m:Memory {id: $id}) RETURN count(m) AS n",
                Map.of("id", acmeMemory)).get(0).get("n")).isEqualTo(0L);
    }

    @Test
    void keyEntitiesAreRankedByPageRank() {
        String conversation = newConversation(alice, newProject(alice, "graph-rank"));
        String hub = "hub" + UUID.randomUUID().toString().substring(0, 6);
        say(conversation, hub + "-gateway uses Kafka. " + hub + "-orders uses Kafka. " + hub + "-billing uses Kafka.");
        Response r = alice.get("/api/v1/graph/key-entities?limit=100");
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        List<Double> scores = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        r.body().forEach(e -> {
            scores.add(e.path("score").asDouble());
            keys.add(e.path("key").asText());
        });
        assertThat(keys).contains("kafka", hub + "-gateway");
        assertThat(scores).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // Three services point at Kafka, so it outranks each of them.
        assertThat(keys.indexOf("kafka")).isLessThan(keys.indexOf(hub + "-gateway"));
    }

    @Test
    void rebuildReconstructsTheSameGraphFromPostgres() {
        String conversation = newConversation(alice, newProject(alice, "graph-rebuild"));
        say(conversation, "checkout-api uses Redis. Redis is hosted on AWS.");
        alice.post("/api/v1/context", Map.of("query", "checkout-api"));
        projector.catchUp(acme);
        Map<String, Long> before = explain.counts(acmeGraph);

        graph.deleteGraph(acmeGraph);
        assertThat(graph.listGraphs()).doesNotContain(acmeGraph);

        Response r = alice.post("/api/admin/graph/rebuild", null);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        assertThat(explain.counts(acmeGraph)).isEqualTo(before);
        assertThat(r.body().path("projected").path("MEMORY").asInt()).isPositive();

        assertThat(as(AARON).post("/api/admin/graph/rebuild", null).status()).isEqualTo(403);
    }
}
