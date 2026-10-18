package com.memora.graph;

import com.memora.graph.GraphAlerts.Alert;
import com.memora.graph.GraphExplainService.Explanation;
import com.memora.graph.GraphExplainService.GraphView;
import com.memora.graph.GraphRebuildService.RebuildResult;
import com.memora.security.Actor;
import com.memora.security.Authz;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The memory graph: explanations, stale-knowledge alerts, the explorer view and rebuild. Always the caller's tenant. */
@RestController
@Tag(name = "Graph", description = "FalkorDB memory graph (one graph per tenant)")
public class GraphController {

    private final GraphExplainService explain;
    private final GraphAlerts alerts;
    private final GraphRebuildService rebuild;
    private final GraphProjector projector;

    public GraphController(GraphExplainService explain, GraphAlerts alerts, GraphRebuildService rebuild, GraphProjector projector) {
        this.explain = explain;
        this.alerts = alerts;
        this.rebuild = rebuild;
        this.projector = projector;
    }

    @GetMapping({"/api/graph/explain", "/api/v1/graph/explain"})
    @Operation(summary = "Why is this memory known: shortest graph path from a task, agent or entity, plus provenance")
    public Explanation explain(Actor actor, @RequestParam UUID memoryId, @RequestParam(required = false) String from) {
        return explain.explain(actor, memoryId, from);
    }

    @GetMapping({"/api/graph/alerts", "/api/v1/graph/alerts"})
    @Operation(summary = "Stale-knowledge alerts: who read, used or carries a fact that has since changed")
    public List<Alert> alerts(Actor actor, @RequestParam(defaultValue = "50") int limit) {
        projector.catchUp(actor.tenantId());
        return alerts.list(actor, Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping({"/api/graph/view", "/api/v1/graph/view"})
    @Operation(summary = "Nodes and edges of the tenant graph for the explorer (personal memories of others excluded)")
    public GraphView view(Actor actor, @RequestParam(required = false) String label,
                          @RequestParam(defaultValue = "300") int limit) {
        return explain.view(actor, label == null || label.isBlank() ? null : label, Math.max(1, Math.min(limit, 2000)));
    }

    @GetMapping({"/api/graph/neighbours", "/api/v1/graph/neighbours"})
    @Operation(summary = "Direct neighbours of one node (by id, or entity key)")
    public GraphView neighbours(Actor actor, @RequestParam String id) {
        return explain.neighbourhood(actor, id);
    }

    @PostMapping({"/api/admin/graph/rebuild", "/api/v1/admin/graph/rebuild"})
    @Operation(summary = "Delete and rebuild the caller's tenant graph from PostgreSQL (admin)")
    public RebuildResult rebuild(Actor actor) {
        Authz.requireAdmin(actor);
        return rebuild.rebuild(actor);
    }
}
