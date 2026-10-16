package com.memora.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.memora.support.ApiClient.Response;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for black-box API tests: a real server on a random port, real PostgreSQL (see
 * {@link TestDatabase}), the seeded demo tenants, and an HTTP client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
public abstract class IntegrationTest {

    public static final String PASSWORD = "memora-demo";
    public static final String ALICE = "alice@acme.test";
    public static final String AARON = "aaron@acme.test";
    public static final String BOB = "bob@globex.test";
    public static final String CASEY = "casey@consultant.test";

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TestDatabase.URL);
        registry.add("spring.datasource.username", () -> TestDatabase.USER);
        registry.add("spring.datasource.password", () -> TestDatabase.PASSWORD);
    }

    protected ApiClient anonymous() {
        return ApiClient.anonymous(port);
    }

    protected ApiClient as(String email) {
        return as(email, null);
    }

    protected ApiClient as(String email, String tenantSlug) {
        Map<String, Object> body = tenantSlug == null
                ? Map.of("email", email, "password", PASSWORD)
                : Map.of("email", email, "password", PASSWORD, "tenantSlug", tenantSlug);
        Response r = anonymous().post("/api/v1/auth/login", body);
        assertThat(r.status()).as("login %s: %s", email, r).isEqualTo(200);
        return anonymous().withBearer(r.text("token"));
    }

    /** Creates a fresh project so each test works on its own data. Requires an admin client. */
    protected String newProject(ApiClient admin, String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Response r = admin.post("/api/v1/projects", Map.of("slug", slug, "name", prefix + " test project"));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.text("id");
    }

    protected String newConversation(ApiClient client, String projectId) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("title", "Test conversation " + UUID.randomUUID()));
        if (projectId != null) {
            body.put("projectId", projectId);
        }
        Response r = client.post("/api/v1/conversations", body);
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.text("id");
    }

    protected String agentId(ApiClient client, String name) {
        for (var a : client.get("/api/v1/agents").body()) {
            if (a.path("name").asText().equals(name)) {
                return a.path("id").asText();
            }
        }
        throw new IllegalStateException("No agent named " + name);
    }

    protected static String uniqueKey() {
        return "test-" + UUID.randomUUID();
    }
}
