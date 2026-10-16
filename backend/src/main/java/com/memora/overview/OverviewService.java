package com.memora.overview;

import com.memora.agent.AgentService;
import com.memora.agent.AgentService.AgentView;
import com.memora.audit.AuditService;
import com.memora.common.RequestMetrics;
import com.memora.handoff.HandoffDtos.HandoffView;
import com.memora.handoff.HandoffService;
import com.memora.handoff.HandoffStatus;
import com.memora.security.Actor;
import com.memora.task.TaskService;
import com.memora.task.TaskStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dashboard numbers. Every count is a live query scoped to the caller's tenant; nothing is hardcoded. */
@Service
public class OverviewService {

    private final JdbcTemplate jdbc;
    private final AgentService agents;
    private final TaskService tasks;
    private final HandoffService handoffs;
    private final AuditService audit;
    private final RequestMetrics metrics;

    public OverviewService(JdbcTemplate jdbc, AgentService agents, TaskService tasks, HandoffService handoffs,
                           AuditService audit, RequestMetrics metrics) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.tasks = tasks;
        this.handoffs = handoffs;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public Overview overview(Actor actor) {
        Map<String, Map<String, Long>> memoryCounts = new LinkedHashMap<>();
        for (String type : List.of("EPISODIC", "SEMANTIC", "PROCEDURAL")) {
            Map<String, Long> byStatus = new LinkedHashMap<>();
            for (String status : List.of("CURRENT", "SUPERSEDED", "RETRACTED")) {
                byStatus.put(status, 0L);
            }
            memoryCounts.put(type, byStatus);
        }
        jdbc.query("SELECT memory_type, status, count(*) AS n FROM memories WHERE tenant_id = ? GROUP BY 1, 2",
                rs -> {
                    memoryCounts.get(rs.getString("memory_type")).put(rs.getString("status"), rs.getLong("n"));
                }, actor.tenantId());
        Long conversations = jdbc.queryForObject("SELECT count(*) FROM conversations WHERE tenant_id = ?", Long.class,
                actor.tenantId());
        Long messages = jdbc.queryForObject("SELECT count(*) FROM messages WHERE tenant_id = ?", Long.class, actor.tenantId());
        Long projects = jdbc.queryForObject("SELECT count(*) FROM projects WHERE tenant_id = ?", Long.class, actor.tenantId());

        List<AgentView> agentList = agents.list(actor);
        Instant activeSince = Instant.now().minus(Duration.ofMinutes(15));
        long activeAgents = agentList.stream().filter(a -> a.lastSeenAt() != null && a.lastSeenAt().isAfter(activeSince)).count();

        long t0 = System.nanoTime();
        jdbc.queryForObject("SELECT 1", Integer.class);
        double dbPingMs = RequestMetrics.round((System.nanoTime() - t0) / 1_000_000.0);
        Boolean rlsEnforced = jdbc.queryForObject(
                "SELECT NOT (rolsuper OR rolbypassrls) FROM pg_roles WHERE rolname = current_user", Boolean.class);

        return new Overview(
                new Counts(projects, conversations, messages, agentList.size(), activeAgents),
                memoryCounts, tasks.countByStatus(actor), handoffs.countByStatus(actor), agentList,
                handoffs.recent(actor, null, 8), audit.recent(actor, null, null, 12),
                new Health("UP", dbPingMs, Boolean.TRUE.equals(rlsEnforced)), metrics.summary(actor.tenantId()));
    }

    public record Counts(long projects, long conversations, long messages, long agents, long agentsActiveLast15m) {
    }

    public record Health(String database, double dbPingMs, boolean rowLevelSecurityEnforced) {
    }

    public record Overview(Counts counts, Map<String, Map<String, Long>> memories, Map<TaskStatus, Long> tasks,
                           Map<HandoffStatus, Long> handoffs, List<AgentView> agents, List<HandoffView> recentHandoffs,
                           List<AuditService.AuditEventView> recentActivity, Health health,
                           RequestMetrics.Summary apiLatency) {
    }
}
