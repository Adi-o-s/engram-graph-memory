package com.memora.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryLifecycleIT extends IntegrationTest {

    private ApiClient alice;
    private String project;

    @BeforeEach
    void setUp() {
        alice = as(ALICE);
        project = newProject(alice, "mem");
    }

    private Response create(Map<String, Object> body) {
        Map<String, Object> b = new java.util.HashMap<>(body);
        b.putIfAbsent("projectId", project);
        return alice.post("/api/v1/memories", b);
    }

    private List<String> contents(Response search) {
        List<String> out = new ArrayList<>();
        search.body().path("items").forEach(m -> out.add(m.path("content").asText()));
        return out;
    }

    @Test
    void allThreeMemoryTypesCanBeStoredAndRetrieved() {
        Response e = create(Map.of("type", "EPISODIC", "content", "Deployed release 1.4 to staging", "tags", List.of("deploy")));
        Response s = create(Map.of("type", "SEMANTIC", "content", "The service exposes a GraphQL API", "category", "fact"));
        Response p = create(Map.of("type", "PROCEDURAL", "content", "To rotate keys, run the rotate-keys job then restart"));
        assertThat(List.of(e.status(), s.status(), p.status())).containsOnly(201);
        assertThat(e.body().path("outcome").asText()).isEqualTo("CREATED");

        for (String type : List.of("EPISODIC", "SEMANTIC", "PROCEDURAL")) {
            Response r = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false&type=" + type);
            assertThat(r.body().path("items")).hasSize(1);
            assertThat(r.body().path("items").get(0).path("type").asText()).isEqualTo(type);
        }
        Response byId = alice.get("/api/v1/memories/" + p.body().path("memory").path("id").asText());
        assertThat(byId.status()).isEqualTo(200);
        assertThat(byId.body().path("source").path("type").asText()).isEqualTo("API");
        assertThat(byId.body().path("source").path("createdByUserId").asText()).isNotBlank();
    }

    @Test
    void fullTextSearchFindsRelevantMemoriesWithStemmingAndPrefixes() {
        create(Map.of("type", "PROCEDURAL", "content", "When migrations fail, inspect the Flyway history table"));
        create(Map.of("type", "SEMANTIC", "content", "Invoices are generated nightly"));
        Response r = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false&q=migration%20failing");
        assertThat(contents(r)).containsExactly("When migrations fail, inspect the Flyway history table");
        Response keyword = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false&q=Flyw");
        assertThat(contents(keyword)).hasSize(1);
    }

    @Test
    void conflictingFactSupersedesOldVersionButKeepsHistory() {
        Map<String, Object> mysql = Map.of("type", "SEMANTIC", "content", "The project uses MySQL",
                "subject", "project", "predicate", "uses.database", "value", "MySQL");
        Response first = create(mysql);
        Response second = create(Map.of("type", "SEMANTIC", "content", "The project uses PostgreSQL",
                "subject", "project", "predicate", "uses.database", "value", "PostgreSQL"));
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().path("outcome").asText()).isEqualTo("SUPERSEDED_PREVIOUS");
        String oldId = first.body().path("memory").path("id").asText();
        String newId = second.body().path("memory").path("id").asText();
        assertThat(second.body().path("supersededMemoryId").asText()).isEqualTo(oldId);
        assertThat(second.body().path("memory").path("version").asInt()).isEqualTo(2);

        JsonNode old = alice.get("/api/v1/memories/" + oldId).body();
        assertThat(old.path("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(old.path("supersededById").asText()).isEqualTo(newId);
        assertThat(old.path("content").asText()).isEqualTo("The project uses MySQL");

        Response current = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false&q=database");
        assertThat(contents(current)).containsExactly("The project uses PostgreSQL");
        Response withHistory = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false&q=database&includeHistory=true");
        assertThat(contents(withHistory)).containsExactlyInAnyOrder("The project uses PostgreSQL", "The project uses MySQL");

        JsonNode history = alice.get("/api/v1/memories/" + newId + "/history").body();
        assertThat(history.path("versions")).hasSize(2);
        assertThat(history.path("versions").get(0).path("value").asText()).isEqualTo("mysql");
        assertThat(history.path("versions").get(1).path("status").asText()).isEqualTo("CURRENT");
    }

    @Test
    void sameFactTwiceIsADuplicateNotANewVersion() {
        Map<String, Object> fact = Map.of("type", "SEMANTIC", "content", "We deploy on Fridays",
                "subject", "team", "predicate", "deploy.day", "value", "Friday");
        assertThat(create(fact).status()).isEqualTo(201);
        Response again = create(fact);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().path("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(again.body().path("memory").path("version").asInt()).isEqualTo(1);
    }

    @Test
    void unrelatedFactsDoNotConflict() {
        create(Map.of("type", "SEMANTIC", "content", "Uses PostgreSQL", "subject", "project", "predicate", "uses.database", "value", "postgresql"));
        Response other = create(Map.of("type", "SEMANTIC", "content", "Uses React", "subject", "project",
                "predicate", "uses.frontend framework", "value", "react"));
        assertThat(other.body().path("outcome").asText()).isEqualTo("CREATED");
        Response all = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false&type=SEMANTIC");
        assertThat(all.body().path("items")).hasSize(2);
        all.body().path("items").forEach(m -> assertThat(m.path("status").asText()).isEqualTo("CURRENT"));
    }

    @Test
    void sameFactInDifferentProjectsDoesNotConflict() {
        String otherProject = newProject(alice, "mem-other");
        create(Map.of("type", "SEMANTIC", "content", "Uses MySQL", "subject", "project", "predicate", "uses.database", "value", "mysql"));
        Response other = alice.post("/api/v1/memories", Map.of("type", "SEMANTIC", "content", "Uses SQLite", "subject", "project",
                "predicate", "uses.database", "value", "sqlite", "projectId", otherProject));
        assertThat(other.body().path("outcome").asText()).isEqualTo("CREATED");
    }

    @Test
    void revisingNonFactContentCreatesANewVersion() {
        Response p = create(Map.of("type", "PROCEDURAL", "content", "Restart the worker when the queue stalls"));
        String id = p.body().path("memory").path("id").asText();
        Response revised = alice.post("/api/v1/memories/" + id + "/revise",
                Map.of("content", "Drain, then restart the worker when the queue stalls", "reason", "safer"));
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().path("memory").path("version").asInt()).isEqualTo(2);
        assertThat(alice.get("/api/v1/memories/" + id).body().path("status").asText()).isEqualTo("SUPERSEDED");
        JsonNode history = alice.get("/api/v1/memories/" + id + "/history").body();
        assertThat(history.path("versions")).hasSize(2);
        Response again = alice.post("/api/v1/memories/" + id + "/revise", Map.of("content", "x y z"));
        assertThat(again.status()).isEqualTo(409);
    }

    @Test
    void metadataUpdateRequiresCurrentLockVersion() {
        Response p = create(Map.of("type", "EPISODIC", "content", "Load test reached 500 rps"));
        String id = p.body().path("memory").path("id").asText();
        long lock = p.body().path("memory").path("lockVersion").asLong();
        Response ok = alice.patch("/api/v1/memories/" + id, Map.of("expectedLockVersion", lock, "importance", 5));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().path("importance").asInt()).isEqualTo(5);
        Response stale = alice.patch("/api/v1/memories/" + id, Map.of("expectedLockVersion", lock, "importance", 1));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.text("code")).isEqualTo("stale_version");
        assertThat(stale.body().path("currentVersion").asLong()).isEqualTo(lock + 1);
    }

    @Test
    void retractedMemoriesDisappearFromDefaultSearch() {
        Response p = create(Map.of("type", "SEMANTIC", "content", "Temporary freeze on deploys"));
        String id = p.body().path("memory").path("id").asText();
        assertThat(alice.post("/api/v1/memories/" + id + "/retract", Map.of("reason", "freeze lifted")).status()).isEqualTo(200);
        Response r = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false");
        assertThat(contents(r)).doesNotContain("Temporary freeze on deploys");
    }

    @Test
    void expiredMemoriesAreNotReturnedAsCurrent() {
        create(Map.of("type", "EPISODIC", "content", "Maintenance window tonight", "expiresAt", "2020-01-01T00:00:00Z"));
        assertThat(contents(alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false"))).isEmpty();
    }

    @Test
    void invalidRequestsAreRejectedWithProblemDetails() {
        Response missing = alice.post("/api/v1/memories", Map.of("content", "no type"));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.text("code")).isEqualTo("validation_failed");
        assertThat(missing.body().path("fields").has("type")).isTrue();
        assertThat(missing.text("correlationId")).isNotBlank();

        Response factWithoutValue = create(Map.of("type", "SEMANTIC", "content", "x", "subject", "a", "predicate", "b"));
        assertThat(factWithoutValue.status()).isEqualTo(400);
        Response proceduralFact = create(Map.of("type", "PROCEDURAL", "content", "x", "subject", "a", "predicate", "b", "value", "c"));
        assertThat(proceduralFact.status()).isEqualTo(400);
        Response badImportance = create(Map.of("type", "EPISODIC", "content", "x", "importance", 9));
        assertThat(badImportance.status()).isEqualTo(400);
        Response malformed = alice.post("/api/v1/memories", "{not json");
        assertThat(malformed.status()).isEqualTo(400);
    }

    @Test
    void secretsAreRefused() {
        Response r = create(Map.of("type", "SEMANTIC", "content", "The prod database password is: Tr0ub4dor&3"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("code")).isEqualTo("sensitive_content");
    }

    @Test
    void idempotencyKeyMakesRetriesReturnTheOriginal() {
        String key = uniqueKey();
        Response a = alice.post("/api/v1/memories", Map.of("type", "EPISODIC", "content", "Retry-safe write", "projectId", project), key);
        Response b = alice.post("/api/v1/memories", Map.of("type", "EPISODIC", "content", "Retry-safe write", "projectId", project), key);
        assertThat(a.status()).isEqualTo(201);
        assertThat(b.status()).isEqualTo(200);
        assertThat(b.body().path("memory").path("id").asText()).isEqualTo(a.body().path("memory").path("id").asText());
    }

    @Test
    void resultLimitAndPaginationAreHonoured() {
        for (int i = 0; i < 7; i++) {
            create(Map.of("type", "EPISODIC", "content", "Paged event number " + i));
        }
        Response page1 = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false&limit=3");
        Response page3 = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false&limit=3&offset=6");
        assertThat(page1.body().path("items")).hasSize(3);
        assertThat(page1.body().path("total").asInt()).isEqualTo(7);
        assertThat(page3.body().path("items")).hasSize(1);
    }
}
