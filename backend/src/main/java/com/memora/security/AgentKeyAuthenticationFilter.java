package com.memora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates agents by {@code X-Agent-Key}. The key's hash identifies exactly one agent, and
 * that agent's tenant becomes the request's tenant. The lookup runs in system scope because no
 * tenant is known until the key is resolved.
 */
public class AgentKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Agent-Key";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AgentKeyAuthenticationFilter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        String hash = ApiKeys.hash(key.trim());
        List<Actor> found = TenantContext.callAsSystem(() -> {
            List<Actor> actors = jdbc.query(
                    "SELECT id, tenant_id, name FROM agents WHERE api_key_hash = ? AND status = 'ACTIVE'",
                    (rs, i) -> Actor.agent(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class),
                            rs.getString("name")),
                    hash);
            if (!actors.isEmpty()) {
                jdbc.update("UPDATE agents SET last_seen_at = now() WHERE id = ?", actors.get(0).id());
            }
            return actors;
        });
        if (found.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "status", 401, "code", "invalid_agent_key", "detail", "Unknown or disabled agent key",
                    "correlationId", String.valueOf(MDC.get("correlationId"))));
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(found.get(0)));
        chain.doFilter(request, response);
    }
}
