package com.memora.graph;

import com.memora.security.TenantContext;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** FalkorDB reachability plus projection lag (pending and failed outbox rows, age of the oldest pending row). */
@Component("falkordb")
public class GraphHealthIndicator implements HealthIndicator {

    private final GraphClient graph;
    private final GraphOutbox outbox;
    private final GraphProjector projector;

    public GraphHealthIndicator(GraphClient graph, GraphOutbox outbox, GraphProjector projector) {
        this.graph = graph;
        this.outbox = outbox;
        this.projector = projector;
    }

    @Override
    public Health health() {
        GraphOutbox.Lag lag = TenantContext.callAsSystem(outbox::lag);
        Health.Builder b;
        try {
            b = graph.ping() ? Health.up() : Health.down();
        } catch (RuntimeException e) {
            b = Health.down().withDetail("error", e.getMessage());
        }
        return b.withDetail("outboxPending", lag.pending())
                .withDetail("outboxFailed", lag.failed())
                .withDetail("oldestPendingSeconds", lag.oldestPendingSeconds() == null ? 0 : lag.oldestPendingSeconds())
                .withDetail("rowsApplied", projector.appliedCount())
                .build();
    }
}
