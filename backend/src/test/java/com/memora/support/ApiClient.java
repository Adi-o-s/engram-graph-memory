package com.memora.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Minimal JSON HTTP client for black-box API tests (java.net.http, supports PATCH). */
public class ApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;
    private final Map<String, String> headers;

    public ApiClient(String base, Map<String, String> headers) {
        this.base = base;
        this.headers = headers;
    }

    public static ApiClient anonymous(int port) {
        return new ApiClient("http://localhost:" + port, Map.of());
    }

    public ApiClient withBearer(String token) {
        return new ApiClient(base, Map.of("Authorization", "Bearer " + token));
    }

    public ApiClient withAgentKey(String key) {
        return new ApiClient(base, Map.of("X-Agent-Key", key));
    }

    public Response get(String path) {
        return send("GET", path, null, null);
    }

    public Response post(String path, Object body) {
        return send("POST", path, body, null);
    }

    public Response post(String path, Object body, String idempotencyKey) {
        return send("POST", path, body, idempotencyKey);
    }

    public Response patch(String path, Object body) {
        return send("PATCH", path, body, null);
    }

    public Response send(String method, String path, Object body, String idempotencyKey) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : MAPPER.writeValueAsString(body)));
            headers.forEach(b::header);
            if (idempotencyKey != null) {
                b.header("Idempotency-Key", idempotencyKey);
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = r.body() == null || r.body().isBlank() ? MAPPER.nullNode() : MAPPER.readTree(r.body());
            return new Response(r.statusCode(), json, r.headers().firstValue("X-Correlation-Id").orElse(null));
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " failed: " + e.getMessage(), e);
        }
    }

    public record Response(int status, JsonNode body, String correlationId) {
        public String text(String field) {
            return body.path(field).asText();
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }
}
