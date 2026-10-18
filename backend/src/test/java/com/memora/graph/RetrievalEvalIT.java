package com.memora.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Retrieval eval: recall@k of the context builder in graph, hybrid and keyword mode on the same synthetic project.
 *
 * <p>Twelve services each get three direct facts ("X uses DynamoDB", "X uses Kafka", "X deploys through Jenkins"),
 * and every technology gets one hosting fact ("DynamoDB is hosted on AWS"). The query for a service is
 * "fix the X outage". Its relevant set is its three direct facts plus the hosting facts of its three technologies,
 * which never mention the service: they are two hops away (service -RELATES-> technology <-ABOUT- memory).
 * The dataset is built to contain multi-hop facts, which is the case graph retrieval exists for; the per-hop
 * columns show where each mode's recall comes from. Results are written to target/retrieval-eval.md.
 */
class RetrievalEvalIT extends IntegrationTest {

    private static final String[] SERVICES = {"orders", "billing", "payments", "search", "catalog", "shipping",
            "inventory", "auth", "notify", "ledger", "pricing", "reviews"};
    private static final String[] STORES = {"DynamoDB", "MySQL", "Cassandra", "Postgres"};
    private static final String[] MESSAGING = {"Kafka", "Redis"};
    private static final String[] DEPLOY = {"Jenkins", "ArgoCD", "CircleCI"};
    private static final Map<String, String> HOSTS = Map.of(
            "DynamoDB", "AWS", "MySQL", "RDS", "Cassandra", "Kubernetes", "Postgres", "AWS",
            "Kafka", "Confluent Cloud", "Redis", "Azure", "Jenkins", "GCP", "ArgoCD", "Kubernetes", "CircleCI", "GCP");
    private static final int[] KS = {3, 6, 10};
    private static final String[] MODES = {"graph", "hybrid", "keyword"};

    private record Query(String service, Set<String> direct, Set<String> twoHop) {
    }

    @Test
    void graphRetrievalRecallVersusKeyword() throws IOException {
        ApiClient alice = as(ALICE);
        String prefix = "ev" + UUID.randomUUID().toString().substring(0, 4);
        String project = newProject(alice, "retrieval-eval");
        String conversation = newConversation(alice, project);

        Map<String, String> hostingFact = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : HOSTS.entrySet()) {
            hostingFact.put(h.getKey(), say(alice, conversation, h.getKey() + " is hosted on " + h.getValue() + "."));
        }
        List<Query> queries = new ArrayList<>();
        for (int i = 0; i < SERVICES.length; i++) {
            String service = prefix + "-" + SERVICES[i] + "-service";
            String store = STORES[i % STORES.length];
            String messaging = MESSAGING[i % MESSAGING.length];
            String deploy = DEPLOY[i % DEPLOY.length];
            Set<String> direct = Set.of(
                    say(alice, conversation, service + " uses " + store + "."),
                    say(alice, conversation, service + " uses " + messaging + "."),
                    say(alice, conversation, service + " deploys through " + deploy + "."));
            queries.add(new Query(service, direct,
                    Set.of(hostingFact.get(store), hostingFact.get(messaging), hostingFact.get(deploy))));
        }

        StringBuilder md = new StringBuilder();
        md.append("| Mode | k | recall@k (all) | direct facts | 2-hop facts | mean latency (ms) |\n|---|---|---|---|---|---|\n");
        Map<String, Double> recallAt6 = new LinkedHashMap<>();
        for (String mode : MODES) {
            for (int k : KS) {
                int hitDirect = 0;
                int hitHop = 0;
                int totalDirect = 0;
                int totalHop = 0;
                long nanos = 0;
                for (Query q : queries) {
                    long start = System.nanoTime();
                    Response r = alice.post("/api/v1/context", Map.of("query", "fix the " + q.service() + " outage",
                            "projectId", project, "maxMemories", 2 * k, "maxChars", 20000, "retrieval", mode));
                    nanos += System.nanoTime() - start;
                    assertThat(r.status()).as(r.toString()).isEqualTo(200);
                    Set<String> got = new HashSet<>();
                    JsonNode facts = r.body().path("facts");
                    for (int i = 0; i < Math.min(k, facts.size()); i++) {
                        got.add(facts.get(i).path("id").asText());
                    }
                    hitDirect += (int) q.direct().stream().filter(got::contains).count();
                    hitHop += (int) q.twoHop().stream().filter(got::contains).count();
                    totalDirect += q.direct().size();
                    totalHop += q.twoHop().size();
                }
                double all = (hitDirect + hitHop) / (double) (totalDirect + totalHop);
                if (k == 6) {
                    recallAt6.put(mode, all);
                }
                md.append(String.format(Locale.ROOT, "| %s | %d | %.2f | %.2f | %.2f | %.1f |%n", mode, k, all,
                        hitDirect / (double) totalDirect, hitHop / (double) totalHop, nanos / 1e6 / queries.size()));
            }
        }
        md.append(String.format(Locale.ROOT, "%n%d queries, %d relevant facts per query (3 direct + 3 two-hop), %d memories in the project.%n",
                queries.size(), 6, hostingFact.size() + 3 * queries.size()));
        Path out = Path.of("target", "retrieval-eval.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println("\n" + md);

        assertThat(recallAt6.get("graph")).isGreaterThan(recallAt6.get("keyword"));
    }

    /** Sends one line and returns the id of the memory it produced. */
    private static String say(ApiClient alice, String conversation, String text) {
        Response r = alice.post("/api/v1/conversations/" + conversation + "/messages", Map.of("content", text));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        JsonNode extracted = r.body().path("extracted");
        assertThat(extracted.size()).as("no memory extracted from: %s", text).isPositive();
        return extracted.get(0).path("memory").path("id").asText();
    }
}
