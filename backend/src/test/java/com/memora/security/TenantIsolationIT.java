package com.memora.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Negative tests: users and agents must not read or modify anything outside their tenant and scope. */
class TenantIsolationIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private ApiClient alice;
    private ApiClient bob;
    private String acmeProject;
    private String acmeMemory;
    private String acmeTask;
    private String acmeConversation;
    private String acmeHandoff;

    @BeforeEach
    void setUp() {
        alice = as(ALICE);
        bob = as(BOB);
        acmeProject = newProject(alice, "iso");
        acmeConversation = newConversation(alice, acmeProject);
        acmeMemory = alice.post("/api/v1/memories", Map.of("type", "SEMANTIC", "content", "Acme secret roadmap: launch Atlas v2",
                "projectId", acmeProject)).body().path("memory").path("id").asText();
        acmeTask = alice.post("/api/v1/tasks", Map.of("title", "Acme internal task", "projectId", acmeProject)).text("id");
        acmeHandoff = alice.post("/api/v1/handoffs", Map.of("taskId", acmeTask, "fromAgentId", agentId(alice, "Planner"),
                "toAgentId", agentId(alice, "Developer"), "summary", "s", "nextAction", "n")).text("id");
    }

    @Test
    void otherTenantCannotReadRecordsByGuessedIds() {
        assertThat(bob.get("/api/v1/memories/" + acmeMemory).status()).isEqualTo(404);
        assertThat(bob.get("/api/v1/memories/" + acmeMemory + "/history").status()).isEqualTo(404);
        assertThat(bob.get("/api/v1/tasks/" + acmeTask).status()).isEqualTo(404);
        assertThat(bob.get("/api/v1/tasks/" + acmeTask + "/events").status()).isEqualTo(404);
        assertThat(bob.get("/api/v1/conversations/" + acmeConversation).status()).isEqualTo(404);
        assertThat(bob.get("/api/v1/conversations/" + acmeConversation + "/messages").status()).isEqualTo(404);
        assertThat(bob.get("/api/v1/projects/" + acmeProject).status()).isEqualTo(404);
        assertThat(bob.get("/api/v1/handoffs/" + acmeHandoff).status()).isEqualTo(404);
    }

    @Test
    void otherTenantCannotModifyRecords() {
        assertThat(bob.patch("/api/v1/tasks/" + acmeTask, Map.of("expectedVersion", 1, "title", "hijacked")).status()).isEqualTo(404);
        assertThat(bob.patch("/api/v1/memories/" + acmeMemory, Map.of("expectedLockVersion", 0, "importance", 1)).status()).isEqualTo(404);
        assertThat(bob.post("/api/v1/memories/" + acmeMemory + "/retract", Map.of()).status()).isEqualTo(404);
        assertThat(bob.post("/api/v1/memories/" + acmeMemory + "/revise", Map.of("content", "hijacked")).status()).isEqualTo(404);
        assertThat(bob.post("/api/v1/conversations/" + acmeConversation + "/messages", Map.of("content", "hi")).status()).isEqualTo(404);
        assertThat(bob.post("/api/v1/handoffs/" + acmeHandoff + "/ack", null).status()).isEqualTo(404);
        assertThat(alice.get("/api/v1/tasks/" + acmeTask).body().path("title").asText()).isEqualTo("Acme internal task");
    }

    @Test
    void otherTenantCannotAttachDataToForeignRecords() {
        assertThat(bob.post("/api/v1/memories", Map.of("type", "EPISODIC", "content", "x", "projectId", acmeProject)).status()).isEqualTo(404);
        assertThat(bob.post("/api/v1/memories", Map.of("type", "EPISODIC", "content", "x", "taskId", acmeTask)).status()).isEqualTo(404);
        assertThat(bob.post("/api/v1/tasks", Map.of("title", "x", "projectId", acmeProject)).status()).isEqualTo(404);
        String bobPlanner = agentId(bob, "Planner");
        Response h = bob.post("/api/v1/handoffs", Map.of("taskId", acmeTask, "fromAgentId", bobPlanner,
                "toAgentId", agentId(alice, "Developer"), "summary", "s", "nextAction", "n"));
        assertThat(h.status()).isEqualTo(404);
        Response refs = bob.post("/api/v1/tasks", Map.of("title", "bob task"));
        Response h2 = bob.post("/api/v1/handoffs", Map.of("taskId", refs.text("id"), "fromAgentId", bobPlanner,
                "toAgentId", agentId(bob, "Developer"), "summary", "s", "nextAction", "n", "memoryRefs", List.of(acmeMemory)));
        assertThat(h2.status()).isEqualTo(404);
    }

    @Test
    void searchListsAndContextOnlyShowTheCallersTenant() {
        Response search = bob.get("/api/v1/memories?q=Atlas%20roadmap&includeHistory=true&limit=100");
        assertThat(search.body().findValuesAsText("content")).noneMatch(c -> c.contains("Acme"));
        assertThat(bob.get("/api/v1/tasks?size=100").body().path("items").findValuesAsText("id")).doesNotContain(acmeTask);
        assertThat(bob.get("/api/v1/projects").body().findValuesAsText("id")).doesNotContain(acmeProject);
        assertThat(bob.get("/api/v1/handoffs?limit=200").body().findValuesAsText("id")).doesNotContain(acmeHandoff);
        Response ctx = bob.post("/api/v1/context", Map.of("query", "Atlas roadmap launch"));
        assertThat(ctx.text("rendered")).doesNotContain("Acme");
        assertThat(bob.post("/api/v1/context", Map.of("taskId", acmeTask)).status()).isEqualTo(404);
        // Filtering by another tenant's project id is a 404, not an empty (or tenant-wide) result.
        assertThat(bob.get("/api/v1/memories?projectId=" + acmeProject).status()).isEqualTo(404);
        assertThat(bob.get("/api/v1/tasks?projectId=" + acmeProject).status()).isEqualTo(404);
        assertThat(bob.get("/api/v1/conversations?projectId=" + acmeProject).status()).isEqualTo(404);
        assertThat(bob.post("/api/v1/context", Map.of("projectId", acmeProject)).status()).isEqualTo(404);
        // Seeded data really is different per tenant (other tests may add demo projects, so look at the seeded ones).
        assertThat(bob.get("/api/v1/memories?q=database&projectId=" + projectId(bob, "pulse")).body().findValuesAsText("value"))
                .contains("mongodb").doesNotContain("postgresql");
        assertThat(alice.get("/api/v1/memories?q=database&projectId=" + projectId(alice, "atlas")).body().findValuesAsText("value"))
                .contains("postgresql").doesNotContain("mongodb");
        assertThat(alice.get("/api/v1/memories?q=database&limit=100&includeHistory=true").body().findValuesAsText("value"))
                .doesNotContain("mongodb");
    }

    private String projectId(ApiClient client, String slug) {
        for (JsonNode p : client.get("/api/v1/projects").body()) {
            if (p.path("slug").asText().equals(slug)) {
                return p.path("id").asText();
            }
        }
        throw new IllegalStateException("no project " + slug);
    }

    @Test
    void agentKeysAreBoundToTheirTenant() {
        String acmeDeveloper = agentId(alice, "Developer");
        ApiClient acmeAgent = anonymous().withAgentKey(alice.post("/api/v1/agents/" + acmeDeveloper + "/rotate-key", null).text("apiKey"));
        assertThat(acmeAgent.get("/api/v1/agents/me").text("id")).isEqualTo(acmeDeveloper);
        String globexTask = bob.post("/api/v1/tasks", Map.of("title", "Globex task")).text("id");
        assertThat(acmeAgent.get("/api/v1/tasks/" + globexTask).status()).isEqualTo(404);
        assertThat(acmeAgent.get("/api/v1/agents/" + agentId(bob, "Developer") + "/inbox").status()).isEqualTo(403);
        assertThat(anonymous().withAgentKey("mem_not-a-real-key-000000000000000000000").get("/api/v1/tasks").status()).isEqualTo(401);
    }

    @Test
    void agentsCannotPerformAdminActions() {
        String acmeDeveloper = agentId(alice, "Developer");
        ApiClient agent = anonymous().withAgentKey(alice.post("/api/v1/agents/" + acmeDeveloper + "/rotate-key", null).text("apiKey"));
        assertThat(agent.post("/api/v1/agents", Map.of("name", "Rogue", "role", "CUSTOM")).status()).isEqualTo(403);
        assertThat(agent.post("/api/v1/projects", Map.of("slug", "rogue", "name", "Rogue")).status()).isEqualTo(403);
        assertThat(agent.post("/api/v1/agents/" + acmeDeveloper + "/rotate-key", null).status()).isEqualTo(403);
    }

    @Test
    void membersOnlySeeTheirOwnConversationsAndPersonalMemories() {
        ApiClient aaron = as(AARON);
        assertThat(aaron.get("/api/v1/conversations/" + acmeConversation).status()).isEqualTo(404);
        assertThat(aaron.get("/api/v1/conversations?size=100").body().path("items").findValuesAsText("id")).doesNotContain(acmeConversation);
        Response pref = alice.post("/api/v1/conversations/" + acmeConversation + "/messages", Map.of("content", "I prefer Rust over Go."));
        String personal = pref.body().path("extracted").get(0).path("memory").path("id").asText();
        assertThat(aaron.get("/api/v1/memories/" + personal).status()).isEqualTo(404);
        assertThat(aaron.get("/api/v1/memories?q=Rust").body().findValuesAsText("id")).doesNotContain(personal);
        // Shared project knowledge is still visible to every member of the tenant.
        assertThat(aaron.get("/api/v1/memories/" + acmeMemory).status()).isEqualTo(200);
        // Members cannot do admin things.
        assertThat(aaron.post("/api/v1/projects", Map.of("slug", "nope", "name", "Nope")).status()).isEqualTo(403);
        assertThat(aaron.post("/api/v1/agents", Map.of("name", "Nope", "role", "CUSTOM")).status()).isEqualTo(403);
    }

    @Test
    void rowLevelSecurityHidesOtherTenantsEvenWithoutApplicationFilters() {
        UUID acme = UUID.fromString(alice.get("/api/v1/auth/me").body().path("tenant").path("id").asText());
        UUID globex = UUID.fromString(bob.get("/api/v1/auth/me").body().path("tenant").path("id").asText());
        // Deliberately unfiltered queries: only RLS stands between them and other tenants' rows.
        Long acmeSeesGlobex = TenantContext.callAsTenant(acme,
                () -> jdbc.queryForObject("SELECT count(*) FROM memories WHERE tenant_id = ?", Long.class, globex));
        Long acmeSeesOwn = TenantContext.callAsTenant(acme,
                () -> jdbc.queryForObject("SELECT count(*) FROM memories WHERE tenant_id = ?", Long.class, acme));
        Long noScope = jdbc.queryForObject("SELECT count(*) FROM memories", Long.class);
        assertThat(acmeSeesGlobex).isZero();
        assertThat(acmeSeesOwn).isPositive();
        assertThat(noScope).isZero();
        Long systemSeesAll = TenantContext.callAsSystem(() -> jdbc.queryForObject("SELECT count(DISTINCT tenant_id) FROM memories", Long.class));
        assertThat(systemSeesAll).isGreaterThanOrEqualTo(2);
        // Writes into another tenant are rejected by the policy's WITH CHECK clause.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TenantContext.runAsTenant(acme, () -> jdbc.update(
                "INSERT INTO projects (id, tenant_id, slug, name) VALUES (?, ?, 'rls-probe', 'x')", UUID.randomUUID(), globex)))
                .rootCause().hasMessageContaining("row-level security");
        Boolean enforced = jdbc.queryForObject("SELECT NOT (rolsuper OR rolbypassrls) FROM pg_roles WHERE rolname = current_user", Boolean.class);
        assertThat(enforced).as("tests must run as a role that RLS applies to").isTrue();
    }

    @Test
    void consultantSwitchesTenantsAndSeesEachSeparately() {
        ApiClient casey = as(CASEY, "acme");
        JsonNode me = casey.get("/api/v1/auth/me").body();
        assertThat(me.path("tenant").path("slug").asText()).isEqualTo("acme");
        assertThat(me.path("memberships")).hasSize(2);
        Response switched = casey.post("/api/v1/auth/switch-tenant", Map.of("tenantSlug", "globex"));
        assertThat(switched.status()).isEqualTo(200);
        ApiClient caseyGlobex = anonymous().withBearer(switched.text("token"));
        assertThat(caseyGlobex.get("/api/v1/memories/" + acmeMemory).status()).isEqualTo(404);
        assertThat(caseyGlobex.get("/api/v1/projects").body().findValuesAsText("slug")).contains("pulse").doesNotContain("atlas");
        Response notMember = as(BOB).post("/api/v1/auth/switch-tenant", Map.of("tenantSlug", "acme"));
        assertThat(notMember.status()).isEqualTo(403);
    }
}
