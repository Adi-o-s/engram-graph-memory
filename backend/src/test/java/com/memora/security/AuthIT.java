package com.memora.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthIT extends IntegrationTest {

    @Test
    void loginReturnsATenantScopedToken() {
        Response r = anonymous().post("/api/v1/auth/login", Map.of("email", ALICE, "password", PASSWORD));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().path("tenant").path("slug").asText()).isEqualTo("acme");
        assertThat(r.body().path("tenant").path("role").asText()).isEqualTo("ADMIN");
        assertThat(r.text("token")).isNotBlank();
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() {
        Response wrong = anonymous().post("/api/v1/auth/login", Map.of("email", ALICE, "password", "nope-nope"));
        Response unknown = anonymous().post("/api/v1/auth/login", Map.of("email", "nobody@nowhere.test", "password", "nope-nope"));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(wrong.text("detail")).isEqualTo(unknown.text("detail"));
    }

    @Test
    void loginToATenantTheUserDoesNotBelongToIsForbidden() {
        Response r = anonymous().post("/api/v1/auth/login", Map.of("email", BOB, "password", PASSWORD, "tenantSlug", "acme"));
        assertThat(r.status()).isEqualTo(403);
    }

    @Test
    void protectedEndpointsRequireAuthentication() {
        for (String path : new String[]{"/api/v1/memories", "/api/v1/tasks", "/api/v1/overview", "/api/v1/agents", "/api/v1/auth/me"}) {
            Response r = anonymous().get(path);
            assertThat(r.status()).as(path).isEqualTo(401);
            assertThat(r.text("code")).isEqualTo("unauthenticated");
        }
        assertThat(anonymous().withBearer("not-a-jwt").get("/api/v1/tasks").status()).isEqualTo(401);
        String forged = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwidGlkIjoiMiIsInJvbGUiOiJBRE1JTiJ9.c2lnbmF0dXJl";
        assertThat(anonymous().withBearer(forged).get("/api/v1/tasks").status()).isEqualTo(401);
    }

    @Test
    void publicEndpointsWork() {
        assertThat(anonymous().get("/actuator/health").status()).isEqualTo(200);
        assertThat(anonymous().get("/v3/api-docs").status()).isEqualTo(200);
        Response demo = anonymous().get("/api/v1/auth/demo-accounts");
        assertThat(demo.body().path("accounts")).hasSize(4);
    }

    @Test
    void responsesCarryCorrelationIds() {
        Response r = as(ALICE).get("/api/v1/overview");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.correlationId()).isNotBlank();
        assertThat(r.body().path("health").path("rowLevelSecurityEnforced").asBoolean()).isTrue();
    }
}
